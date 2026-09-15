package io.termbridge.core.transport

import io.termbridge.core.proto.Protocol

/** Where an agent listens. Phase 1: a LAN address typed by the user. */
data class Endpoint(val host: String, val port: Int = Protocol.DEFAULT_PORT) {
    init {
        require(host.isNotBlank()) { "host is blank" }
        require(port in 1..65535) { "port $port out of range" }
    }

    /** IPv6 literals need brackets inside a URL. */
    val url: String
        get() = "ws://${if (':' in host && !host.startsWith("[")) "[$host]" else host}:$port${Protocol.PATH}"

    override fun toString(): String = "$host:$port"

    companion object {
        /** Parses `host:port` as found in the pairing QR code. */
        fun parse(hostPort: String): Endpoint {
            val sep = hostPort.lastIndexOf(':')
            require(sep > 0) { "missing port in $hostPort" }
            return Endpoint(hostPort.substring(0, sep).removeSurrounding("[", "]"), hostPort.substring(sep + 1).toInt())
        }
    }
}

data class AgentInfo(val agent: String, val os: String, val hostname: String)

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val agent: AgentInfo) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
    data class Closed(val reason: String) : ConnectionState

    val isTerminal: Boolean get() = this is Failed || this is Closed
}

/** Receives a session's traffic. Called on the network thread: keep it short and thread-safe. */
interface SessionListener {
    /** Terminal output; [bytes] is only valid during the call. */
    fun onOutput(bytes: ByteArray, offset: Int, length: Int)
    fun onExit(exitCode: Int)

    /** Session ended without an exit status (connection lost, agent shutdown). */
    fun onClosed()
}

/** The agent refused a request (PROTOCOL.md §3.2). */
class RemoteException(val code: String, message: String) : Exception(message)
