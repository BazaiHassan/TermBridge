package io.termbridge.feature.terminal

import io.termbridge.core.crypto.MachineStore
import io.termbridge.core.transport.LanDiscovery
import io.termbridge.core.transport.NetworkMonitor
import io.termbridge.core.transport.TermBridgeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Every open [MachineSession], app-wide: screens come and go, shells stay. Main thread only. */
@Singleton
class TerminalSessions @Inject constructor(
    private val client: TermBridgeClient,
    private val machines: MachineStore,
    private val discovery: LanDiscovery,
    private val network: NetworkMonitor,
) {
    private val _active = MutableStateFlow<Map<String, MachineSession>>(emptyMap())

    /** Open sessions by agent ID. */
    val active: StateFlow<Map<String, MachineSession>> = _active.asStateFlow()

    /** The session with [agentId], started if new; one that exited or gave up starts over. */
    fun open(agentId: String, name: String): MachineSession {
        _active.value[agentId]?.let { existing ->
            val status = existing.state.value.status
            if (status is TerminalStatus.Exited || status is TerminalStatus.Lost) existing.start()
            return existing
        }
        val session = MachineSession(agentId, name, client, machines, discovery, network) { ended ->
            _active.update { if (it[ended.agentId] === ended) it - ended.agentId else it }
        }
        _active.update { it + (agentId to session) }
        session.start()
        return session
    }
}
