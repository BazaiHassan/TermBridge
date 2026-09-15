package io.termbridge.feature.terminal

import android.net.Network
import io.termbridge.core.crypto.MachineStore
import io.termbridge.core.crypto.PairedMachine
import io.termbridge.core.terminal.TerminalEmulator
import io.termbridge.core.transport.ConnectTarget
import io.termbridge.core.transport.ConnectionState
import io.termbridge.core.transport.Endpoint
import io.termbridge.core.transport.LanDiscovery
import io.termbridge.core.transport.NetworkMonitor
import io.termbridge.core.transport.RemoteException
import io.termbridge.core.transport.RemoteSession
import io.termbridge.core.transport.SessionListener
import io.termbridge.core.transport.TermBridgeClient
import io.termbridge.core.transport.TermBridgeConnection
import io.termbridge.core.transport.reconnectDelayMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

sealed interface TerminalStatus {
    data object Connecting : TerminalStatus
    data class Live(val hostname: String, val os: String) : TerminalStatus

    /** The link dropped. The shell keeps running on the computer and is re-attached on the next try. */
    data class Reconnecting(val reason: String, val attempt: Int) : TerminalStatus
    data class Exited(val exitCode: Int) : TerminalStatus

    /** Retrying cannot help: not paired any more, revoked, or no shell could start. */
    data class Lost(val reason: String) : TerminalStatus
}

/** What the terminal screen shows about its machine. */
data class SessionState(
    val title: String,
    val endpoint: String = "",
    val status: TerminalStatus = TerminalStatus.Connecting,
    val rttMillis: Long? = null,
)

/**
 * One paired machine's connection, shell and screen. Owned by [TerminalSessions], so it outlives
 * the terminal screen and the app going to the background.
 *
 * When the link drops it reconnects with backoff, sooner when the phone's network changes or the
 * computer shows up on the LAN, and re-attaches the shell, which kept running on the computer
 * (PROTOCOL.md §4.8); output missed meanwhile is replayed into the emulator.
 */
class MachineSession internal constructor(
    val agentId: String,
    val name: String,
    private val client: TermBridgeClient,
    private val machines: MachineStore,
    private val discovery: LanDiscovery,
    private val network: NetworkMonitor,
    private val onDisconnected: (MachineSession) -> Unit,
) : SessionListener, TerminalEmulator.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val emulator = TerminalEmulator(cols = 80, rows = 24).also { it.listener = this }

    private val _state = MutableStateFlow(SessionState(title = name))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Installed by the view: schedules a redraw; safe from any thread. */
    @Volatile
    var renderRequest: (() -> Unit)? = null

    /** Kept across screens; the view reads it when recreated. */
    var fontSizeSp = 13f

    private val viewport = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Cuts the backoff wait short. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var connection: TermBridgeConnection? = null

    @Volatile private var shell: RemoteSession? = null

    /** The shell to re-attach after a reconnect; null once it exited or was closed. */
    @Volatile private var resumeId: Int? = null

    /** Why retrying cannot help; ends the loop with [TerminalStatus.Lost]. */
    @Volatile private var fatal: String? = null

    private var loop: Job? = null
    private var resizeJob: Job? = null

    /** Starts; after an exit opens a new shell; while waiting to reconnect retries now. */
    fun start() {
        if (loop?.isActive == true) {
            val conn = connection
            if (_state.value.status is TerminalStatus.Exited && conn != null) scope.launch { attachOrOpen(conn) } else wake.trySend(Unit)
            return
        }
        fatal = null
        loop = scope.launch { run() }
    }

    /** Ends the shell on the computer and forgets this session. */
    fun disconnect() {
        shell?.close() // SESSION_CLOSE is queued ahead of the WebSocket close frame
        shell = null
        resumeId = null
        connection?.close()
        scope.cancel()
        onDisconnected(this)
    }

    private suspend fun run() = coroutineScope {
        val watchers = launch {
            launch { watchNetwork() }
            watchLan()
        }
        var attempt = 0
        while (true) {
            val machine = machines.get(agentId)
            if (machine == null) {
                fatal = "$name is no longer paired with this phone"
                break
            }
            if (attempt == 0) _state.update { it.copy(status = TerminalStatus.Connecting, rttMillis = null) }
            val ending = connectOnce(machine)
            if (fatal != null || _state.value.status is TerminalStatus.Exited) break
            attempt = if (ending.wasConnected) 1 else attempt + 1
            _state.update { it.copy(status = TerminalStatus.Reconnecting(ending.reason, attempt), rttMillis = null) }
            withTimeoutOrNull(reconnectDelayMillis(attempt)) { wake.receive() }
        }
        watchers.cancel()
        fatal?.let { reason ->
            shell = null
            _state.update { it.copy(status = TerminalStatus.Lost(reason), rttMillis = null) }
        }
    }

    private class Ending(val reason: String, val wasConnected: Boolean)

    /** One connection, from dialing until it ends. */
    private suspend fun connectOnce(machine: PairedMachine): Ending = coroutineScope {
        val endpoints = Endpoint.all(machine.addresses, machine.wan, machine.relay, machine.agentId)
        val conn = try {
            client.connect(ConnectTarget(machine.name, machine.agentId, endpoints), scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@coroutineScope Ending(e.message ?: "Could not connect", false).also { fatal = it.reason }
        }
        connection = conn
        if (_state.value.endpoint.isEmpty()) _state.update { it.copy(endpoint = endpoints.firstOrNull()?.label.orEmpty()) }
        var connected = false
        try {
            launch { conn.rttMillis.collect { rtt -> _state.update { it.copy(rttMillis = rtt) } } }
            val end = conn.state.first { state ->
                if (state is ConnectionState.Connected) {
                    connected = true
                    _state.update { it.copy(endpoint = conn.endpoint?.label.orEmpty()) }
                    machines.markConnected(
                        agentId,
                        via = (conn.endpoint as? Endpoint.Direct)?.label,
                        lan = state.agent.lanAddrs,
                        wan = state.agent.wanAddrs,
                    )
                    launch { attachOrOpen(conn) }
                }
                state.isTerminal
            }
            if (end is ConnectionState.Failed && !end.retryable) fatal = end.reason
            Ending((end as? ConnectionState.Failed)?.reason ?: (end as? ConnectionState.Closed)?.reason.orEmpty(), connected)
        } finally {
            connection = null
            conn.close()
            coroutineContext.cancelChildren()
        }
    }

    /** Re-attaches the shell that survived the disconnect, or opens a new one. */
    private suspend fun attachOrOpen(conn: TermBridgeConnection) {
        val (cols, rows) = viewport.filterNotNull().first() // the real size, not 80×24
        _state.update { if (it.status is TerminalStatus.Live) it else it.copy(status = TerminalStatus.Connecting) }
        val previous = resumeId
        if (previous != null) {
            try {
                shell = conn.attachSession(previous, cols, rows, this)
                live(conn)
                return
            } catch (e: RemoteException) {
                resumeId = null
                notice(if (e.isUnknownSession) "the shell ended while the phone was away · new shell" else "$name can't resume shells · new shell", leaveAltScreen = true)
            } catch (e: IOException) {
                return // dropped again; the loop retries and keeps resumeId
            }
        }
        try {
            val opened = conn.openSession(cols, rows, this)
            shell = opened
            resumeId = opened.id
            live(conn)
        } catch (e: RemoteException) {
            fatal = "$name could not start a shell: ${e.message}"
            conn.close()
        } catch (e: IOException) {
            // dropped while opening; the loop retries
        }
    }

    private fun live(conn: TermBridgeConnection) {
        val agent = (conn.state.value as? ConnectionState.Connected)?.agent ?: return
        // The shell may already have exited during the replay.
        _state.update { if (it.status is TerminalStatus.Exited) it else it.copy(status = TerminalStatus.Live(agent.hostname, agent.os)) }
    }

    /** A network switch may leave the old path dead: probe it, and retry at once if waiting. */
    private suspend fun watchNetwork() {
        var current: Network? = null
        var first = true
        network.defaultNetwork.collect { net ->
            val changed = !first && net != current
            first = false
            current = net
            if (changed && net != null) {
                connection?.probe()
                wake.trySend(Unit)
            }
        }
    }

    /** The computer's current LAN address, even after DHCP gave it a new one (§9.5). */
    private suspend fun watchLan() {
        discovery.addressesOf(agentId).collect { found ->
            machines.addLanAddresses(agentId, found)
            connection?.let { conn -> found.forEach { runCatching { conn.addEndpoint(Endpoint.parse(it)) } } }
            wake.trySend(Unit) // back on the computer's network: no need to wait
        }
    }

    /** Prints a dim local marker line into the terminal. */
    private fun notice(text: String, leaveAltScreen: Boolean = false) {
        val prefix = if (leaveAltScreen) "\u001b[?1049l" else ""
        synchronized(emulator) { emulator.feed("$prefix\r\n\u001b[0;2m── $text ──\u001b[0m\r\n".encodeToByteArray()) }
        renderRequest?.invoke()
    }

    // ---- From the view -----------------------------------------------------------------

    fun onViewportChanged(cols: Int, rows: Int) {
        synchronized(emulator) { emulator.resize(cols, rows) }
        viewport.value = cols to rows
        renderRequest?.invoke()
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(RESIZE_DEBOUNCE_MS) // keyboard animations and pinches resize many times a second
            shell?.resize(cols, rows)
        }
    }

    fun sendInput(bytes: ByteArray) {
        shell?.write(bytes)
    }

    // ---- SessionListener (network thread) ------------------------------------------------

    override fun onOutput(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(emulator) { emulator.feed(bytes, offset, length) }
        renderRequest?.invoke()
    }

    override fun onExit(exitCode: Int) {
        shell = null
        resumeId = null
        notice("session ended · exit $exitCode")
        _state.update { it.copy(status = TerminalStatus.Exited(exitCode)) }
    }

    override fun onClosed() {
        shell = null
    }

    // ---- TerminalEmulator.Listener (called under the emulator lock) ----------------------

    override fun onTitleChanged(title: String) = _state.update { it.copy(title = title.ifBlank { name }) }

    override fun onResponse(bytes: ByteArray) {
        shell?.write(bytes)
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 120L
    }
}
