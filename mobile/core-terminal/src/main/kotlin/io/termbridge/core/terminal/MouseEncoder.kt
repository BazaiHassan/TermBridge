package io.termbridge.core.terminal

/** Which mouse events the application wants reported. */
enum class MouseMode {
    NONE,

    /** DECSET 1000: presses and releases (and the wheel). */
    CLICK,

    /** DECSET 1002: also motion while a button is held. */
    DRAG,

    /** DECSET 1003: all motion. */
    MOTION,
}

/**
 * Encodes mouse events as xterm reports: SGR (`CSI < b ; x ; y M/m`, DECSET 1006) or the legacy
 * X10 form (`CSI M` and three bytes, each value + 32). Columns and rows are 0-based.
 */
object MouseEncoder {
    const val LEFT = 0
    const val MIDDLE = 1
    const val RIGHT = 2
    const val WHEEL_UP = 64
    const val WHEEL_DOWN = 65

    private val ESC = 27.toChar()

    fun press(button: Int, col: Int, row: Int, sgr: Boolean, mods: Int = Mods.NONE): ByteArray =
        encode(button or modBits(mods), col, row, sgr, release = false)

    /** Legacy reports cannot say which button was released: they send button 3. */
    fun release(button: Int, col: Int, row: Int, sgr: Boolean, mods: Int = Mods.NONE): ByteArray =
        encode((if (sgr) button else 3) or modBits(mods), col, row, sgr, release = true)

    /** Motion with [button] held (DRAG and MOTION modes). */
    fun drag(button: Int, col: Int, row: Int, sgr: Boolean, mods: Int = Mods.NONE): ByteArray =
        encode(button or 32 or modBits(mods), col, row, sgr, release = false)

    private fun encode(code: Int, col: Int, row: Int, sgr: Boolean, release: Boolean): ByteArray {
        if (sgr) return "$ESC[<$code;${col + 1};${row + 1}${if (release) 'm' else 'M'}".encodeToByteArray()
        // X10 fits a position in one byte: columns and rows past 223 are clamped.
        val x = minOf(col + 1, 223) + 32
        val y = minOf(row + 1, 223) + 32
        return byteArrayOf(27, '['.code.toByte(), 'M'.code.toByte(), (code + 32).toByte(), x.toByte(), y.toByte())
    }

    private fun modBits(mods: Int): Int =
        (if (mods and Mods.SHIFT != 0) 4 else 0) or
            (if (mods and Mods.ALT != 0) 8 else 0) or
            (if (mods and Mods.CTRL != 0) 16 else 0)
}
