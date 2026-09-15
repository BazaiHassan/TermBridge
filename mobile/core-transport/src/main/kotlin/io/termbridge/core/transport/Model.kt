package io.termbridge.core.transport

import io.termbridge.core.proto.ErrorCodes
import io.termbridge.core.proto.Protocol
import java.util.Base64

/** One way to reach an agent: directly (LAN or a forwarded public port), or through a relay. */
sealed interface Endpoint {
    val url: String

    /** Short form for messages, e.g. `192.168.1.20:7423` or `relay.example.com`. */
    val label: String

    /** True for paths without a relay; those are tried first (PROTOCOL.md §9.4). */
    val direct: Boolean

    data class Direct(val host: String, val port: Int = Protocol.DEFAULT_PORT) : Endpoint {
        init {
            require(host.isNotBlank()) { "host is blank" }
            require(port in 1..65535) { "port $port out of range" }
        }

        /** IPv6 literals need brackets inside a URL. */
        override val url: String
            get() = "ws://${if (':' in host && !host.startsWith("[")) "[$host]" else host}:$port${Protocol.PATH}"
        override val label: String get() = "$host:$port"
        override val direct: Boolean get() = true

        override fun toString(): String = label
    }

    /** `<relay>/client?agent=<id as base64url>` (PROTOCOL.md §9.1). */
    data class Relay(val base: String, val agentId: String) : Endpoint {
        init {
            require(base.startsWith("wss://") || base.startsWith("ws://")) { "relay URL must start with wss://" }
        }

        override val url: String
            get() {
                val id = Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(agentId))
                return "${base.trimEnd('/')}/client?agent=$id"
            }
        override val label: String get() = "relay ${base.substringAfter("://").substringBefore('/')}"
        override val direct: Boolean get() = false

        override fun toString(): String = label
    }

    companion object {
        /** Parses `host:port` as found in the pairing QR code. */
        fun parse(hostPort: String): Direct {
            val sep = hostPort.lastIndexOf(':')
            require(sep > 0) { "missing port in $hostPort" }
            return Direct(hostPort.substring(0, sep).removeSurrounding("[", "]"), hostPort.substring(sep + 1).toInt())
        }

        /** Every known path to an agent, in dial order: LAN, public, relay. */
        fun all(lan: List<String>, wan: List<String>, relay: String?, agentId: String): List<Endpoint> =
            (lan + wan).distinct().mapNotNull { runCatching { parse(it) }.getOrNull() } +
                listOfNotNull(relay?.let { runCatching { Relay(it, agentId) }.getOrNull() })
    }
}

/** What the agent said about itself in HELLO_ACK, including its current addresses. */
data class AgentInfo(
    val agent: String,
    val os: String,
    val hostname: String,
    val lanAddrs: List<String> = emptyList(),
    val wanAddrs: List<String> = emptyList(),
)

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val agent: AgentInfo) : ConnectionState
    /**
     * [retryable] is false when trying again cannot help (this phone is not recognized, the
     * computer speaks another protocol); an auto-reconnect loop must stop then.
     */
    data class Failed(val reason: String, val retryable: Boolean = true) : ConnectionState
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
class RemoteException(val code: String, message: String) : Exception(message) {
    /** The session to re-attach ended, or the resume window passed (PROTOCOL.md §4.8). */
    val isUnknownSession: Boolean get() = code == ErrorCodes.UNKNOWN_SESSION
}
