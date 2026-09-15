package io.termbridge.core.proto

import io.termbridge.core.proto.ProtocolException.Reason
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/** Encodes and decodes typed [Message]s. Byte-compatible with os/internal/proto. */
object MessageCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: Message): ByteArray = when (message) {
        is Message.Data -> frame(Opcode.DATA, message.session, message.bytes, message.offset, message.length)
        is Message.Resize -> fixed(Opcode.RESIZE, message.session) {
            putShort(message.cols.toShort())
            putShort(message.rows.toShort())
        }
        is Message.SessionOpen -> json(
            Opcode.SESSION_OPEN,
            Protocol.CONTROL_SESSION,
            SessionOpenJson.serializer(),
            SessionOpenJson(message.shell, message.cwd, message.cols, message.rows),
        )
        is Message.SessionOpened -> fixed(Opcode.SESSION_OPENED, Protocol.CONTROL_SESSION) {
            put(message.newSession.toByte())
        }
        is Message.SessionClose -> frame(Opcode.SESSION_CLOSE, message.session, EMPTY)
        is Message.SessionExit -> fixed(Opcode.SESSION_EXIT, message.session) { putInt(message.exitCode) }
        is Message.Ping -> fixed(Opcode.PING, Protocol.CONTROL_SESSION) { putLong(message.timestamp) }
        is Message.Pong -> fixed(Opcode.PONG, Protocol.CONTROL_SESSION) { putLong(message.timestamp) }
        is Message.Hello -> json(
            Opcode.HELLO,
            Protocol.CONTROL_SESSION,
            HelloJson.serializer(),
            HelloJson(message.version, message.client),
        )
        is Message.HelloAck -> json(
            Opcode.HELLO_ACK,
            Protocol.CONTROL_SESSION,
            HelloAckJson.serializer(),
            HelloAckJson(
                message.version, message.agent, message.os, message.hostname,
                addrs = if (message.lanAddrs.isEmpty() && message.wanAddrs.isEmpty()) null else AddrsJson(message.lanAddrs, message.wanAddrs),
            ),
        )
        is Message.Error -> json(
            Opcode.ERROR,
            message.session,
            ErrorJson.serializer(),
            ErrorJson(message.code, message.message),
        )
        is Message.Unknown -> FrameCodec.encode(message.op, message.session, message.payload)
    }

    fun decode(wire: ByteArray, offset: Int = 0, length: Int = wire.size - offset): Message =
        decode(FrameCodec.decode(wire, offset, length))

    fun decode(frame: Frame): Message {
        val op = frame.opcode ?: return Message.Unknown(frame.op, frame.session, frame.payload())
        if (op.fixedPayload >= 0 && frame.length != op.fixedPayload) {
            throw ProtocolException(Reason.BAD_PAYLOAD, "$op payload is ${frame.length} bytes, want ${op.fixedPayload}")
        }
        val buf = ByteBuffer.wrap(frame.buffer, frame.offset, frame.length) // big-endian
        val sid = frame.session
        return when (op) {
            Opcode.DATA -> Message.Data(sid, frame.buffer, frame.offset, frame.length)
            Opcode.RESIZE -> Message.Resize(sid, buf.short.toInt() and 0xFFFF, buf.short.toInt() and 0xFFFF)
            Opcode.SESSION_OPEN -> parse(frame, SessionOpenJson.serializer()).let {
                if (it.cols !in 0..0xFFFF || it.rows !in 0..0xFFFF) {
                    throw ProtocolException(Reason.BAD_PAYLOAD, "SESSION_OPEN size out of range")
                }
                Message.SessionOpen(it.shell, it.cwd, it.cols, it.rows)
            }
            Opcode.SESSION_OPENED -> {
                val id = buf.get().toInt() and 0xFF
                if (id == Protocol.CONTROL_SESSION) throw ProtocolException(Reason.BAD_PAYLOAD, "session ID 0 is reserved")
                Message.SessionOpened(id)
            }
            Opcode.SESSION_CLOSE -> Message.SessionClose(sid)
            Opcode.SESSION_EXIT -> Message.SessionExit(sid, buf.int)
            Opcode.PING -> Message.Ping(buf.long)
            Opcode.PONG -> Message.Pong(buf.long)
            Opcode.HELLO -> parse(frame, HelloJson.serializer()).let { Message.Hello(it.v, it.client) }
            Opcode.HELLO_ACK -> parse(frame, HelloAckJson.serializer()).let {
                Message.HelloAck(it.v, it.agent, it.os, it.hostname, it.addrs?.lan.orEmpty(), it.addrs?.wan.orEmpty())
            }
            Opcode.ERROR -> parse(frame, ErrorJson.serializer()).let { Message.Error(sid, it.code, it.msg) }
        }
    }

    private val EMPTY = ByteArray(0)

    private fun frame(op: Opcode, session: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset) =
        FrameCodec.encode(op.code, session, payload, offset, length)

    private inline fun fixed(op: Opcode, session: Int, write: ByteBuffer.() -> Unit): ByteArray {
        val payload = ByteBuffer.allocate(op.fixedPayload).apply(write).array()
        return frame(op, session, payload)
    }

    private fun <T> json(op: Opcode, session: Int, serializer: KSerializer<T>, value: T): ByteArray =
        frame(op, session, json.encodeToString(serializer, value).encodeToByteArray())

    private fun <T> parse(frame: Frame, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, frame.buffer.decodeToString(frame.offset, frame.offset + frame.length))
    } catch (e: SerializationException) {
        throw ProtocolException(Reason.BAD_PAYLOAD, "invalid ${frame.opcode} JSON", e)
    } catch (e: IllegalArgumentException) {
        throw ProtocolException(Reason.BAD_PAYLOAD, "invalid ${frame.opcode} JSON", e)
    }
}

@Serializable
private data class HelloJson(val v: Int, val client: String = "")

@Serializable
private data class HelloAckJson(
    val v: Int,
    val agent: String = "",
    val os: String = "",
    val hostname: String = "",
    val addrs: AddrsJson? = null,
)

@Serializable
private data class AddrsJson(val lan: List<String> = emptyList(), val wan: List<String> = emptyList())

@Serializable
private data class SessionOpenJson(val shell: String = "", val cwd: String = "", val cols: Int = 0, val rows: Int = 0)

@Serializable
private data class ErrorJson(val code: String, @SerialName("msg") val msg: String = "")
