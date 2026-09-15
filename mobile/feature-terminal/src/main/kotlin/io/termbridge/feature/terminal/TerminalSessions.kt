package io.termbridge.feature.terminal

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.termbridge.core.crypto.MachineStore
import io.termbridge.core.transport.LanDiscovery
import io.termbridge.core.transport.NetworkMonitor
import io.termbridge.core.transport.TermBridgeClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Every open [MachineSession], app-wide: screens come and go, shells stay. Main thread only. */
@Singleton
class TerminalSessions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TermBridgeClient,
    private val machines: MachineStore,
    private val discovery: LanDiscovery,
    private val network: NetworkMonitor,
) {
    private val _active = MutableStateFlow<Map<String, MachineSession>>(emptyMap())

    /** Open sessions by agent ID. */
    val active: StateFlow<Map<String, MachineSession>> = _active.asStateFlow()

    /** Agent IDs with a shell running or being reached. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val running: Flow<Set<String>> = _active
        .flatMapLatest { open ->
            if (open.isEmpty()) {
                flowOf(emptySet())
            } else {
                combine(open.values.map { s -> s.state.map { st -> s.agentId.takeIf { st.isRunning } } }) { ids -> ids.filterNotNull().toSet() }
            }
        }
        .distinctUntilChanged()

    /** The session with [agentId], started if new; one whose shells all ended, or that gave up, starts over. */
    fun open(agentId: String, name: String): MachineSession {
        _active.value[agentId]?.let { existing ->
            if (!existing.state.value.isRunning) existing.start()
            return existing
        }
        val session = MachineSession(agentId, name, client, machines, discovery, network, onStart = ::keepAlive) { ended ->
            _active.update { if (it[ended.agentId] === ended) it - ended.agentId else it }
        }
        _active.update { it + (agentId to session) }
        session.start()
        return session
    }

    /** Ends every shell, e.g. from the notification. */
    fun endAll() = _active.value.values.toList().forEach { it.disconnect() }

    /** Keeps the process, and with it the shells, alive in the background. */
    private fun keepAlive() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        } catch (e: IllegalStateException) {
            // Not allowed from the background (Android 12+); sessions start from the UI, and a
            // running service already covers the rest.
        }
    }
}
