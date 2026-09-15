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
import java.util.concurrent.atomic.AtomicBoolean

sealed interface TerminalStatus {
    data object Connecting : TerminalStatus
    data class Live(val hostname: String, val os: String) : TerminalStatus

    /** The link dropped. Shells keep running on the computer and are re-attached on the next try. */
    data class Reconnecting(val reason: String, val attempt: Int) : TerminalStatus
    data class Exited(val exitCode: Int) : TerminalStatus

    /** The computer would not start this shell (e.g. too many open); the others are unaffected. */
    data class ShellFailed(val reason: String) : TerminalStatus

    /** Retrying cannot help: not paired any more, revoked, or no shell could start. */
    data class Lost(val reason: String) : TerminalStatus
}

/** The connection to one machine, as the screen and the notification see it. */
data class SessionState(
    val endpoint: String = "",
    /** Connection level: connecting, live, reconnecting or lost. */
    val status: TerminalStatus = TerminalStatus.Connecting,
    val rttMillis: Long? = null,
    /** Shells running, or being opened or re-attached. */
    val openShells: Int = 0,
)

/** Something may still happen in a shell, so the app should stay alive. */
val SessionState.isRunning: Boolean get() = status !is TerminalStatus.Lost && openShells > 0

sealed interface ShellPhase {
    /** Being opened, or waiting to be re-attached after a drop. */
    data object Starting : ShellPhase
    data object Live : ShellPhase
    data class Exited(val code: Int) : ShellPhase
    data class Failed(val reason: String) : ShellPhase
}

data class ShellState(val title: String = "", val phase: ShellPhase = ShellPhase.Starting) {
    val isOpen: Boolean get() = phase == ShellPhase.Starting || phase == ShellPhase.Live
}

/** One shell on the computer, shown as a tab, with its own screen and scrollback. */
class Shell internal constructor(val key: Int, private val owner: MachineSession) : SessionListener, TerminalEmulator.Listener {
    val emulator = TerminalEmulator(cols = 80, rows = 24).also { it.listener = this }

    private val _state = MutableStateFlow(ShellState())
    val state: StateFlow<ShellState> = _state.asStateFlow()

    @Volatile internal var remote: RemoteSession? = null

    /** The remote session ID to re-attach after a drop; null once it exited or was closed. */
    @Volatile internal var resumeId: Int? = null

    /** An attach or open is in flight. */
    internal val busy = AtomicBoolean(false)

    internal fun setPhase(phase: ShellPhase) {
        _state.update { it.copy(phase = phase) }
        owner.shellsChanged()
    }

    /** Live, unless it already exited during the replay. */
    internal fun markLive() {
        _state.update { if (it.phase == ShellPhase.Starting) it.copy(phase = ShellPhase.Live) else it }
        owner.shellsChanged()
    }

    /** The link dropped: wait for the re-attach. */
    internal fun detached() {
        remote = null
        _state.update { if (it.phase == ShellPhase.Live) it.copy(phase = ShellPhase.Starting) else it }
    }

    /** After an exit or a failure, this tab gets a new shell. */
    internal fun reset() {
        if (_state.value.isOpen) return
        remote = null
        resumeId = null
        setPhase(ShellPhase.Starting)
    }

    /** Prints a dim local marker line into this shell's screen. */
    internal fun notice(text: String, leaveAltScreen: Boolean = false) {
        val prefix = if (leaveAltScreen) "\u001b[?1049l" else ""
        synchronized(emulator) { emulator.feed("$prefix\r\n\u001b[0;2m── $text ──\u001b[0m\r\n".encodeToByteArray()) }
        owner.render(this)
    }

    // ---- SessionListener (network thread) ------------------------------------------------

    override fun onOutput(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(emulator) { emulator.feed(bytes, offset, length) }
        owner.render(this)
    }

    override fun onExit(exitCode: Int) {
        remote = null
        resumeId = null
        notice("session ended · exit $exitCode")
        setPhase(ShellPhase.Exited(exitCode))
    }

    override fun onClosed() {
        remote = null
    }

    // ---- TerminalEmulator.Listener (called under the emulator lock) ----------------------

    override fun onTitleChanged(title: String) = _state.update { it.copy(title = title) }

    override fun onResponse(bytes: ByteArray) {
        remote?.write(bytes)
    }
}

/**
 * One paired machine: a connection carrying any number of [Shell]s. Owned by [TerminalSessions],
 * so it outlives the terminal screen and the app going to the background.
 *
 * When the link drops it reconnects with backoff, sooner when the phone's network changes or the
 * computer shows up on the LAN, and re-attaches every shell, which kept running on the computer
 * (PROTOCOL.md §4.8); output missed meanwhile is replayed into each emulator.
 */
class MachineSession internal constructor(
    val agentId: String,
    val name: String,
    private val client: TermBridgeClient,
    private val machines: MachineStore,
    private val discovery: LanDiscovery,
    private val network: NetworkMonitor,
    /** Called whenever the session starts working; keeps [SessionService] up. */
    private val onStart: () -> Unit,
    private val onDisconnected: (MachineSession) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SessionState(openShells = 1))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var nextKey = 1
    private val firstShell = Shell(nextKey++, this)
    private val _shells = MutableStateFlow(listOf(firstShell))

    /** In tab order; never empty — closing the last shell ends the session. */
    val shells: StateFlow<List<Shell>> = _shells.asStateFlow()

    private val _selected = MutableStateFlow(firstShell)
    val selected: StateFlow<Shell> = _selected.asStateFlow()

    /** Installed by the view: schedules a redraw; safe from any thread. */
    @Volatile
    var renderRequest: (() -> Unit)? = null

    private val viewport = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Cuts the backoff wait short. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var connection: TermBridgeConnection? = null

    /** Why retrying cannot help; ends the loop with [TerminalStatus.Lost]. */
    @Volatile private var fatal: String? = null

    private var loop: Job? = null
    private var resizeJob: Job? = null

    /**
     * Starts. Also: gives the current tab a new shell after it exited, opens shells waiting for
     * one, or, while waiting to reconnect, retries now.
     */
    fun start() {
        onStart()
        _selected.value.reset()
        val conn = connection
        when {
            loop?.isActive != true -> {
                fatal = null
                loop = scope.launch { run() }
            }
            conn != null && conn.state.value is ConnectionState.Connected -> syncShells(conn)
            else -> wake.trySend(Unit)
        }
    }

    /** Opens another shell in a new tab and shows it. */
    fun newShell() {
        val shell = Shell(nextKey++, this)
        viewport.value?.let { (cols, rows) -> synchronized(shell.emulator) { shell.emulator.resize(cols, rows) } }
        _shells.update { it + shell }
        _selected.value = shell
        shellsChanged()
        start()
    }

    fun select(shell: Shell) {
        if (shell in _shells.value) _selected.value = shell
    }

    /** Ends [shell] on the computer and removes its tab. False when it was the last: the session ended. */
    fun closeShell(shell: Shell): Boolean {
        val all = _shells.value
        if (all.size <= 1) {
            disconnect()
            return false
        }
        shell.remote?.close()
        shell.remote = null
        shell.resumeId = null
        val index = all.indexOf(shell)
        val rest = all - shell
        _shells.value = rest
        if (_selected.value === shell) _selected.value = rest[index.coerceAtMost(rest.lastIndex)]
        shellsChanged()
        return true
    }

    /** Ends every shell on the computer and forgets this session. */
    fun disconnect() {
        _shells.value.forEach { it.remote?.close() } // SESSION_CLOSE is queued ahead of the WebSocket close frame
        connection?.close()
        scope.cancel()
        onDisconnected(this)
    }

    internal fun render(shell: Shell) {
        if (_selected.value === shell) renderRequest?.invoke()
    }

    internal fun shellsChanged() {
        _state.update { it.copy(openShells = _shells.value.count { s -> s.state.value.isOpen }) }
    }

    private suspend fun run() = coroutineScope {
        val watchers = launch {
            launch { watchNetwork() }
            watchLan()
        }
        var attempt = 0
        var reason = ""
        while (true) {
            val machine = machines.get(agentId)
            if (machine == null) {
                fatal = "$name is no longer paired with this phone"
                break
            }
            if (attempt == 0) _state.update { it.copy(status = TerminalStatus.Connecting, rttMillis = null) }
            val ending = connectOnce(machine)
            reason = ending.reason
            if (fatal != null || _shells.value.none { it.state.value.isOpen }) break
            attempt = if (ending.wasConnected) 1 else attempt + 1
            _state.update { it.copy(status = TerminalStatus.Reconnecting(ending.reason, attempt), rttMillis = null) }
            withTimeoutOrNull(reconnectDelayMillis(attempt)) { wake.receive() }
        }
        watchers.cancel()
        _state.update { it.copy(status = TerminalStatus.Lost(fatal ?: reason), rttMillis = null) }
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
                    _state.update {
                        it.copy(endpoint = conn.endpoint?.label.orEmpty(), status = TerminalStatus.Live(state.agent.hostname, state.agent.os))
                    }
                    machines.markConnected(
                        agentId,
                        via = (conn.endpoint as? Endpoint.Direct)?.label,
                        lan = state.agent.lanAddrs,
                        wan = state.agent.wanAddrs,
                    )
                    syncShells(conn)
                }
                state.isTerminal
            }
            if (end is ConnectionState.Failed && !end.retryable) fatal = end.reason
            Ending((end as? ConnectionState.Failed)?.reason ?: (end as? ConnectionState.Closed)?.reason.orEmpty(), connected)
        } finally {
            connection = null
            conn.close()
            _shells.value.forEach { it.detached() }
            coroutineContext.cancelChildren()
        }
    }

    /** Attaches or opens every shell that is waiting for one. */
    private fun syncShells(conn: TermBridgeConnection) {
        _shells.value
            .filter { it.state.value.phase == ShellPhase.Starting && it.remote == null }
            .forEach { shell -> scope.launch { attachOrOpen(shell, conn) } }
    }

    /** Re-attaches the shell that survived the disconnect, or opens a new one. */
    private suspend fun attachOrOpen(shell: Shell, conn: TermBridgeConnection) {
        if (!shell.busy.compareAndSet(false, true)) return
        try {
            val (cols, rows) = viewport.filterNotNull().first() // the real size, not 80×24
            val previous = shell.resumeId
            if (previous != null) {
                try {
                    shell.remote = conn.attachSession(previous, cols, rows, shell)
                    shell.markLive()
                    return
                } catch (e: RemoteException) {
                    shell.resumeId = null
                    val why = if (e.isUnknownSession) "the shell ended while the phone was away" else "$name can't resume shells"
                    shell.notice("$why · new shell", leaveAltScreen = true)
                } catch (e: IOException) {
                    return // dropped again; the loop retries and keeps resumeId
                }
            }
            try {
                val opened = conn.openSession(cols, rows, shell)
                if (shell !in _shells.value) { // closed while opening
                    opened.close()
                    return
                }
                shell.remote = opened
                shell.resumeId = opened.id
                shell.markLive()
            } catch (e: RemoteException) {
                if (_shells.value.any { it !== shell && it.state.value.isOpen }) {
                    shell.setPhase(ShellPhase.Failed(e.message ?: "The computer refused"))
                } else {
                    fatal = "$name could not start a shell: ${e.message}"
                    conn.close()
                }
            } catch (e: IOException) {
                // dropped while opening; the loop retries
            }
        } finally {
            shell.busy.set(false)
        }
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

    // ---- From the view -----------------------------------------------------------------

    fun onViewportChanged(cols: Int, rows: Int) {
        _shells.value.forEach { shell -> synchronized(shell.emulator) { shell.emulator.resize(cols, rows) } }
        viewport.value = cols to rows
        renderRequest?.invoke()
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(RESIZE_DEBOUNCE_MS) // keyboard animations and pinches resize many times a second
            _shells.value.forEach { it.remote?.resize(cols, rows) }
        }
    }

    fun sendInput(bytes: ByteArray) {
        _selected.value.remote?.write(bytes)
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 120L
    }
}
