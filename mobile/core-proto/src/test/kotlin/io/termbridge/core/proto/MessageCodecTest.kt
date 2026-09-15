package io.termbridge.core.proto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class MessageCodecTest {
    @Test
    fun dataDecodesWithoutCopying() {
        val wire = byteArrayOf(0x01, 0x03) + "ls\r".encodeToByteArray()
        val data = assertIs<Message.Data>(MessageCodec.decode(wire))
        assertSame(wire, data.bytes)
        assertEquals(2, data.offset)
        assertContentEquals("ls\r".encodeToByteArray(), data.copyBytes())
    }

    @Test
    fun dataSliceEncodesOnlyTheView() {
        val buf = "xxhixx".encodeToByteArray()
        val wire = MessageCodec.encode(Message.Data(1, buf, offset = 2, length = 2))
        assertContentEquals(byteArrayOf(0x01, 0x01, 'h'.code.toByte(), 'i'.code.toByte()), wire)
    }

    @Test
    fun unknownOpcodeIsPreserved() {
        val msg = assertIs<Message.Unknown>(MessageCodec.decode(byteArrayOf(0x7F, 0x00, 0x01)))
        assertEquals(0x7F, msg.op)
        assertContentEquals(byteArrayOf(0x7F, 0x00, 0x01), MessageCodec.encode(msg))
    }

    @Test
    fun unknownJsonFieldsAreIgnored() {
        val payload = """{"v":1,"agent":"a","os":"linux","hostname":"h","future":true}""".encodeToByteArray()
        val msg = MessageCodec.decode(byteArrayOf(0x31, 0x00) + payload)
        assertEquals(Message.HelloAck(1, "a", "linux", "h"), msg)
    }

    @Test
    fun resizeRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { Message.Resize(1, 70_000, 24) }
    }

    @Test
    fun opcodeLookupCoversAllCodes() {
        for (op in Opcode.entries) assertEquals(op, Opcode.of(op.code))
        assertEquals(null, Opcode.of(0x7F))
    }
}
