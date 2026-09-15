package io.termbridge.core.proto

import io.termbridge.core.proto.ProtocolException.Reason

/**
 * A raw frame. The payload is the view `[offset, offset + length)` of [buffer], so decoding a
 * DATA frame never copies the terminal bytes.
 */
class Frame(
    val op: Int,
    val session: Int,
    val buffer: ByteArray,
    val offset: Int = 0,
    val length: Int = buffer.size - offset,
) {
    val opcode: Opcode? get() = Opcode.of(op)

    fun payload(): ByteArray = buffer.copyOfRange(offset, offset + length)
}

/** Wire framing: `u8 opcode | u8 sessionID | payload` (PROTOCOL.md §2). */
object FrameCodec {
    fun decode(wire: ByteArray, offset: Int = 0, length: Int = wire.size - offset): Frame {
        if (length < Protocol.HEADER_SIZE) {
            throw ProtocolException(Reason.SHORT_FRAME, "frame of $length bytes is shorter than the header")
        }
        if (length > Protocol.MAX_FRAME) {
            throw ProtocolException(Reason.FRAME_TOO_LARGE, "payload of ${length - Protocol.HEADER_SIZE} bytes")
        }
        return Frame(
            op = wire[offset].toInt() and 0xFF,
            session = wire[offset + 1].toInt() and 0xFF,
            buffer = wire,
            offset = offset + Protocol.HEADER_SIZE,
            length = length - Protocol.HEADER_SIZE,
        )
    }

    fun encode(op: Int, session: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): ByteArray {
        require(op in 0..0xFF) { "opcode $op out of range" }
        require(session in 0..0xFF) { "session $session out of range" }
        if (length > Protocol.MAX_PAYLOAD) {
            throw ProtocolException(Reason.FRAME_TOO_LARGE, "payload of $length bytes")
        }
        val out = ByteArray(Protocol.HEADER_SIZE + length)
        out[0] = op.toByte()
        out[1] = session.toByte()
        System.arraycopy(payload, offset, out, Protocol.HEADER_SIZE, length)
        return out
    }
}
