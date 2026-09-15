package io.termbridge.feature.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.termbridge.core.crypto.MachineStore
import io.termbridge.core.terminal.Mods
import io.termbridge.core.terminal.Sticky
import io.termbridge.core.terminal.TerminalEmulator
import io.termbridge.core.transport.ConnectTarget
import io.termbridge.core.transport.ConnectionState
import io.termbridge.core.transport.Endpoint
import io.termbridge.core.transport.LanDiscovery
import io.termbridge.core.transport.RemoteSession
import io.termbridge.core.transport.SessionListener
import io.termbridge.core.transport.TermBridgeClient
import io.termbridge.core.transport.TermBridgeConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Opens a shell on a paired machine, identified by its agent ID. */
@Serializable
data class TerminalDestination(val agentId: String, val name: String)

sealed interface TerminalStatus {
    data object Connecting : TerminalStatus
    data class Live(val hostname: String, val os: String) : TerminalStatus
    data class Exited(val exitCode: Int) : TerminalStatus
    data class Lost(val reason: String) : TerminalStatus
}

data class TerminalUiState(
    val title: String,
    val endpoint: String = "",
    val status: TerminalStatus = TerminalStatus.Connecting,
    val rttMillis: Long? = null,
    val ctrl: Sticky = Sticky.OFF,
    val alt: Sticky = Sticky.OFF,
)

/**
 * Owns the connection, the remote shell and the emulator, so all three survive configuration
 * changes. Terminal output arrives on OkHttp's thread and is fed to the emulator under its lock.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val client: TermBridgeClient,
    private val machines: MachineStore,
    private val discovery: LanDiscovery,
) : ViewModel(), SessionListener, TerminalEmulator.Listener {

    private val route = savedStateHandle.toRoute<TerminalDestination>()

    val emulator = TerminalEmulator(cols = 80, rows = 24).also { it.listener = this }

    private val _ui = MutableStateFlow(TerminalUiState(title = route.name))
    val ui: StateFlow<TerminalUiState> = _ui.asStateFlow()

    /** Installed by the view: schedules a redraw; safe from any thread. */
    @Volatile
    var renderRequest: (() -> Unit)? = null

    /** Survives rotation; the view reads it when recreated. */
    var fontSizeSp = 13f

    private val viewport = MutableStateFlow<Pair<Int, Int>?>(null)
    private var connection: TermBridgeConnection? = null
    private var connectionJob: Job? = null
    private var resizeJob: Job? = null

    @Volatile
    private var session: RemoteSession? = null

    init {
        connect()
    }

    /** Reconnects, or opens a fresh shell when the connection is still up. */
    fun restart() {
        val conn = connection
        if (conn != null && conn.state.value is ConnectionState.Connected) openSession(conn) else connect()
    }

    fun disconnect() {
        connection?.close()
    }

    private fun connect() {
        connection?.close()
        connectionJob?.cancel()
        session = null
        _ui.update { it.copy(status = TerminalStatus.Connecting, rttMillis = null) }
        connectionJob = viewModelScope.launch {
            val machine = machines.get(route.agentId)
            if (machine == null) {
                lost("${route.name} is no longer paired with this phone")
                return@launch
            }
            _ui.update { it.copy(endpoint = machine.addresses.firstOrNull().orEmpty()) }
            val conn = try {
                client.connect(
                    ConnectTarget(machine.name, machine.agentId, Endpoint.all(machine.addresses, machine.wan, machine.relay, machine.agentId)),
                    viewModelScope,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lost(e.message ?: "Could not connect")
                return@launch
            }
            connection = conn
            launch { conn.rttMillis.collect { rtt -> _ui.update { it.copy(rttMillis = rtt) } } }
            launch {
                // The computer's current LAN address, even after DHCP gave it a new one (§9.5).
                discovery.addressesOf(machine.agentId).collect { found ->
                    machines.addLanAddresses(machine.agentId, found)
                    found.forEach { runCatching { conn.addEndpoint(Endpoint.parse(it)) } }
                    if (_ui.value.status is TerminalStatus.Lost) connect() // it came back: reconnect
                }
            }
            conn.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _ui.update { it.copy(endpoint = conn.endpoint?.label.orEmpty()) }
                        machines.markConnected(
                            route.agentId,
                            via = (conn.endpoint as? Endpoint.Direct)?.label,
                            lan = state.agent.lanAddrs,
                            wan = state.agent.wanAddrs,
                        )
                        openSession(conn)
                    }
                    is ConnectionState.Failed -> lost(state.reason)
                    is ConnectionState.Closed -> lost(state.reason)
                    ConnectionState.Connecting -> Unit
                }
            }
        }
    }

    private fun openSession(conn: TermBridgeConnection) {
        viewModelScope.launch {
            _ui.update { it.copy(status = TerminalStatus.Connecting) }
            val (cols, rows) = viewport.filterNotNull().first() // open at the real size, not 80×24
            try {
                session = conn.openSession(cols, rows, this@TerminalViewModel)
                val agent = (conn.state.value as? ConnectionState.Connected)?.agent
                _ui.update { it.copy(status = TerminalStatus.Live(agent?.hostname.orEmpty(), agent?.os.orEmpty())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lost(e.message ?: "The computer could not start a shell")
            }
        }
    }

    private fun lost(reason: String) {
        session = null
        if (_ui.value.status is TerminalStatus.Exited) return
        if (_ui.value.status is TerminalStatus.Live) notice("connection lost")
        _ui.update { it.copy(status = TerminalStatus.Lost(reason), rttMillis = null) }
    }

    /** Prints a dim local marker line into the terminal. */
    private fun notice(text: String) {
        synchronized(emulator) { emulator.feed("\r\n\u001b[0;2m── $text ──\u001b[0m\r\n".encodeToByteArray()) }
        renderRequest?.invoke()
    }

    // ---- From the view -----------------------------------------------------------------

    fun onViewportChanged(cols: Int, rows: Int) {
        synchronized(emulator) { emulator.resize(cols, rows) }
        viewport.value = cols to rows
        renderRequest?.invoke()
        resizeJob?.cancel()
        resizeJob = viewModelScope.launch {
            delay(RESIZE_DEBOUNCE_MS) // keyboard animations and pinches resize many times a second
            session?.resize(cols, rows)
        }
    }

    fun sendInput(bytes: ByteArray) {
        session?.write(bytes)
    }

    fun toggleCtrl() = _ui.update { it.copy(ctrl = it.ctrl.tapped()) }
    fun toggleAlt() = _ui.update { it.copy(alt = it.alt.tapped()) }

    fun stickyModifiers(): Int = _ui.value.let {
        (if (it.ctrl.active) Mods.CTRL else 0) or (if (it.alt.active) Mods.ALT else 0)
    }

    fun consumeSticky() = _ui.update { it.copy(ctrl = it.ctrl.consumed(), alt = it.alt.consumed()) }

    // ---- SessionListener (network thread) ------------------------------------------------

    override fun onOutput(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(emulator) { emulator.feed(bytes, offset, length) }
        renderRequest?.invoke()
    }

    override fun onExit(exitCode: Int) {
        session = null
        notice("session ended · exit $exitCode")
        _ui.update { it.copy(status = TerminalStatus.Exited(exitCode)) }
    }

    override fun onClosed() {
        session = null
    }

    // ---- TerminalEmulator.Listener (called under the emulator lock) ----------------------

    override fun onTitleChanged(title: String) = _ui.update { it.copy(title = title.ifBlank { route.name }) }

    override fun onResponse(bytes: ByteArray) {
        session?.write(bytes)
    }

    override fun onCleared() {
        connection?.close()
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 120L
    }
}
