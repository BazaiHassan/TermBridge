package io.termbridge.core.proto

import io.termbridge.core.proto.ProtocolException.Reason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Replays docs/vectors/frames.json — the same vectors the Go codec runs — so the two
 * implementations cannot drift apart.
 */
class VectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(
        File(checkNotNull(System.getProperty("termbridge.vectors")) { "termbridge.vectors not set" }).readText(),
    ).jsonObject

    private fun vectors(section: String) = root.getValue(section).jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    @Test
    fun maxPayloadMatchesSpec() {
        assertEquals(Protocol.MAX_PAYLOAD, root.getValue("max_payload").jsonPrimitive.int)
    }

    @Test
    fun rawFrames() {
        for (v in vectors("raw")) {
            val name = v.str("name")
            val wire = hex(v.str("wire_hex"))
            val payload = hex(v.str("payload_hex"))
            val frame = FrameCodec.decode(wire)
            assertEquals(v.int("op"), frame.op, name)
            assertEquals(v.int("sid"), frame.session, name)
            assertContentEquals(payload, frame.payload(), name)
            assertContentEquals(wire, FrameCodec.encode(v.int("op"), v.int("sid"), payload), name)
        }
    }

    @Test
    fun typedMessages() {
        for (v in vectors("typed")) {
            val name = v.str("name")
            val wire = hex(v.str("wire_hex"))
            val expected = build(v)
            val encoded = MessageCodec.encode(expected)
            if (v["json"]?.jsonPrimitive?.boolean != true) {
                assertContentEquals(wire, encoded, name) // binary kinds: byte for byte
            }
            assertEquals(expected, MessageCodec.decode(wire), name)
            assertEquals(expected, MessageCodec.decode(encoded), name)
        }
    }

    @Test
    fun invalidFrames() {
        for (v in vectors("invalid")) {
            val name = v.str("name")
            val e = assertFailsWith<ProtocolException>(name) { MessageCodec.decode(hex(v.str("wire_hex"))) }
            assertEquals(reason(v.str("error")), e.reason, name)
        }
    }

    @Test
    fun generatedFrames() {
        for (v in vectors("generated")) {
            val name = v.str("name")
            val payload = ByteArray(v.int("payload_len")) { v.int("fill").toByte() }
            val wire = byteArrayOf(v.int("op").toByte(), v.int("sid").toByte()) + payload
            val error = v["error"]?.jsonPrimitive?.content
            if (error == null) {
                assertIs<Message.Data>(MessageCodec.decode(wire), name)
                FrameCodec.encode(v.int("op"), v.int("sid"), payload)
            } else {
                val dec = assertFailsWith<ProtocolException>(name) { FrameCodec.decode(wire) }
                val enc = assertFailsWith<ProtocolException>(name) { FrameCodec.encode(v.int("op"), v.int("sid"), payload) }
                assertEquals(reason(error), dec.reason, name)
                assertEquals(reason(error), enc.reason, name)
            }
        }
    }

    private fun build(v: JsonObject): Message {
        val sid = v.int("sid")
        return when (val kind = v.str("kind")) {
            "resize" -> Message.Resize(sid, v.int("cols"), v.int("rows"))
            "session_opened" -> Message.SessionOpened(v.int("new_sid"))
            "session_close" -> Message.SessionClose(sid)
            "session_attach" -> Message.SessionAttach(sid, v.int("cols"), v.int("rows"))
            "session_attached" -> Message.SessionAttached(sid)
            "session_exit" -> Message.SessionExit(sid, v.int("code"))
            "ping" -> Message.Ping(v.str("ts").toULong().toLong())
            "pong" -> Message.Pong(v.str("ts").toULong().toLong())
            "hello" -> Message.Hello(v.int("v"), v.str("client"))
            "hello_ack" -> Message.HelloAck(v.int("v"), v.str("agent"), v.str("os"), v.str("hostname"))
            "session_open" -> Message.SessionOpen(v.str("shell"), v.str("cwd"), v.int("cols"), v.int("rows"))
            "error" -> Message.Error(sid, v.str("code"), v.str("msg"))
            else -> error("unknown vector kind $kind")
        }
    }

    private fun reason(name: String) = when (name) {
        "short_frame" -> Reason.SHORT_FRAME
        "frame_too_large" -> Reason.FRAME_TOO_LARGE
        "bad_payload" -> Reason.BAD_PAYLOAD
        else -> error("unknown error $name")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
