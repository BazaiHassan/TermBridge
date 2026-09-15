package io.termbridge.feature.pairing

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.termbridge.core.crypto.AgentKeys
import io.termbridge.core.crypto.MachineStore
import io.termbridge.core.crypto.PairedMachine
import io.termbridge.core.proto.HandshakePayload
import io.termbridge.core.proto.PairingQr
import io.termbridge.core.transport.ConnectTarget
import io.termbridge.core.transport.ConnectionState
import io.termbridge.core.transport.Endpoint
import io.termbridge.core.transport.TermBridgeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PairingState {
    /** Camera open. [hint] explains why the last code was not accepted. */
    data class Scanning(val hint: String? = null) : PairingState
    data class Confirm(val qr: PairingQr, val fingerprint: String, val deviceName: String) : PairingState
    data class Pairing(val qr: PairingQr) : PairingState
    data class Paired(val machine: PairedMachine) : PairingState
    data class Failed(val message: String) : PairingState
}

/**
 * Scan → confirm → pair. Pairing is one real connection whose Noise msg1 redeems the QR's
 * one-time code (PROTOCOL.md §8); the machine is saved only once the agent accepted it.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val client: TermBridgeClient,
    private val machines: MachineStore,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingState>(PairingState.Scanning())
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var lastInvalid: String? = null

    /** Called for every QR the camera decodes, many times per second. */
    fun onScanned(text: String) {
        if (_state.value !is PairingState.Scanning || text == lastInvalid) return
        PairingQr.parse(text)
            .mapCatching { qr -> PairingState.Confirm(qr, AgentKeys.fingerprint(qr.agentId), defaultDeviceName()) }
            .onSuccess { _state.value = it }
            .onFailure {
                lastInvalid = text
                _state.value = PairingState.Scanning(hint = it.message)
            }
    }

    fun setDeviceName(name: String) {
        (_state.value as? PairingState.Confirm)?.let { _state.value = it.copy(deviceName = name.take(40)) }
    }

    fun pair() {
        val confirm = _state.value as? PairingState.Confirm ?: return
        val qr = confirm.qr
        _state.value = PairingState.Pairing(qr)
        viewModelScope.launch {
            try {
                val connection = client.connect(
                    ConnectTarget(
                        name = qr.name,
                        agentId = qr.agentId,
                        endpoints = Endpoint.all(qr.lan, qr.wan, qr.relay, qr.agentId),
                        handshakePayload = HandshakePayload.pair(qr.code, confirm.deviceName.ifBlank { defaultDeviceName() }),
                    ),
                    scope = viewModelScope,
                )
                when (val result = connection.state.first { it is ConnectionState.Connected || it.isTerminal }) {
                    is ConnectionState.Connected -> {
                        val winner = (connection.endpoint as? Endpoint.Direct)?.label
                        val now = System.currentTimeMillis()
                        val lan = result.agent.lanAddrs.ifEmpty { qr.lan }
                        val machine = PairedMachine(
                            agentId = qr.agentId,
                            name = qr.name,
                            addresses = listOfNotNull(winner?.takeIf { it in lan }) + lan.filter { it != winner },
                            pairedAt = now,
                            lastConnectedAt = now,
                            wan = result.agent.wanAddrs.ifEmpty { qr.wan },
                            relay = qr.relay,
                        )
                        machines.upsert(machine)
                        connection.close()
                        _state.value = PairingState.Paired(machine)
                    }
                    is ConnectionState.Failed -> _state.value = PairingState.Failed(result.reason)
                    is ConnectionState.Closed -> _state.value = PairingState.Failed(result.reason)
                    ConnectionState.Connecting -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PairingState.Failed(e.message ?: "Pairing failed")
            }
        }
    }

    fun scanAgain() {
        lastInvalid = null
        _state.value = PairingState.Scanning()
    }

    private fun defaultDeviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android phone"
}
