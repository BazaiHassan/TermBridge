package io.termbridge.core.proto

/** Constants of TermBridge protocol v1 (docs/PROTOCOL.md). Mirrors os/internal/proto. */
object Protocol {
    const val VERSION = 1
    const val SUBPROTOCOL = "termbridge.v1"
    const val PATH = "/v1"
    const val DEFAULT_PORT = 7423

    const val HEADER_SIZE = 2

    /** Header + payload + 16-byte AEAD tag fit one 65 535-byte Noise message (ADR 0003). */
    const val MAX_PAYLOAD = 65_535 - 16 - HEADER_SIZE
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD

    /** Session ID of connection-level frames. */
    const val CONTROL_SESSION = 0
}

enum class Opcode(val code: Int) {
    DATA(0x01),
    RESIZE(0x02),
    SESSION_OPEN(0x10),
    SESSION_OPENED(0x11),
    SESSION_CLOSE(0x12),
    SESSION_EXIT(0x13),
    PING(0x20),
    PONG(0x21),
    HELLO(0x30),
    HELLO_ACK(0x31),
    ERROR(0x40),
    ;

    /** Payload length mandated by the spec, or -1 when variable. */
    val fixedPayload: Int
        get() = when (this) {
            RESIZE, SESSION_EXIT -> 4
            SESSION_OPENED -> 1
            SESSION_CLOSE -> 0
            PING, PONG -> 8
            else -> -1
        }

    companion object {
        private val byCode = arrayOfNulls<Opcode>(256).also { table -> entries.forEach { table[it.code] = it } }

        /** The opcode for [code], or null when protocol v1 does not define it. */
        fun of(code: Int): Opcode? = byCode[code and 0xFF]
    }
}

/** Error codes carried in ERROR frames (PROTOCOL.md §3.2). */
object ErrorCodes {
    const val HELLO_REQUIRED = "hello_required"
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val BAD_FRAME = "bad_frame"
    const val UNSUPPORTED_OPCODE = "unsupported_opcode"
    const val SESSION_OPEN_FAILED = "session_open_failed"
    const val TOO_MANY_SESSIONS = "too_many_sessions"
}

class ProtocolException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { SHORT_FRAME, FRAME_TOO_LARGE, BAD_PAYLOAD }
}
