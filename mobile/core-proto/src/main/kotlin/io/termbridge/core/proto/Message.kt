package io.termbridge.core.proto

/** A decoded TermBridge v1 frame. [session] is 0 for connection-level messages. */
sealed interface Message {
    val session: Int

    /** Raw terminal bytes: the view `[offset, offset + length)` of [bytes]. */
    class Data(
        override val session: Int,
        val bytes: ByteArray,
        val offset: Int = 0,
        val length: Int = bytes.size - offset,
    ) : Message {
        fun copyBytes(): ByteArray = bytes.copyOfRange(offset, offset + length)

        override fun equals(other: Any?): Boolean =
            other is Data && other.session == session && other.copyBytes().contentEquals(copyBytes())

        override fun hashCode(): Int = 31 * session + copyBytes().contentHashCode()

        override fun toString(): String = "Data(session=$session, length=$length)"
    }

    data class Resize(override val session: Int, val cols: Int, val rows: Int) : Message {
        init {
            require(cols in 0..0xFFFF && rows in 0..0xFFFF) { "size ${cols}x$rows out of u16 range" }
        }
    }

    /** Empty [shell] / [cwd] select the agent defaults. */
    data class SessionOpen(val shell: String = "", val cwd: String = "", val cols: Int, val rows: Int) : Message {
        override val session: Int get() = Protocol.CONTROL_SESSION
    }

    data class SessionOpened(val newSession: Int) : Message {
        override val session: Int get() = Protocol.CONTROL_SESSION
    }

    data class SessionClose(override val session: Int) : Message

    /** Re-attach a session that survived a disconnect (PROTOCOL.md §4.8), at this size. */
    data class SessionAttach(override val session: Int, val cols: Int, val rows: Int) : Message {
        init {
            require(cols in 0..0xFFFF && rows in 0..0xFFFF) { "size ${cols}x$rows out of u16 range" }
        }
    }

    /** The agent accepted SESSION_ATTACH; buffered output follows as DATA. */
    data class SessionAttached(override val session: Int) : Message

    /** Killed by signal N → 128 + N. */
    data class SessionExit(override val session: Int, val exitCode: Int) : Message

    /** [timestamp] is a u64 carried in a Long's bits. */
    data class Ping(val timestamp: Long) : Message {
        override val session: Int get() = Protocol.CONTROL_SESSION
    }

    data class Pong(val timestamp: Long) : Message {
        override val session: Int get() = Protocol.CONTROL_SESSION
    }

    data class Hello(val version: Int = Protocol.VERSION, val client: String) : Message {
        override val session: Int get() = Protocol.CONTROL_SESSION
    }

    /** [lanAddrs] / [wanAddrs]: the agent's current addresses (PROTOCOL.md §9.4). */
    data class HelloAck(
        val version: Int,
        val agent: String,
        val os: String,
        val hostname: String,
        val lanAddrs: List<String> = emptyList(),
        val wanAddrs: List<String> = emptyList(),
    ) : Message {
        override val session: Int get() = Protocol.CONTROL_SESSION
    }

    data class Error(override val session: Int, val code: String, val message: String) : Message

    /** An opcode this client does not know; receivers ignore it (PROTOCOL.md §3). */
    class Unknown(val op: Int, override val session: Int, val payload: ByteArray) : Message {
        override fun toString(): String = "Unknown(op=0x%02X, session=$session)".format(op)
    }
}
