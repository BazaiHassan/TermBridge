package io.termbridge.feature.terminal

/** ARGB colors for the terminal canvas: 16 theme colors plus the xterm 256-color table. */
class TerminalPalette(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    ansi: IntArray,
) {
    private val table = IntArray(256)

    init {
        require(ansi.size == 16) { "need 16 ANSI colors" }
        ansi.copyInto(table)
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) {
            table[16 + i] = argb(levels[i / 36], levels[(i / 6) % 6], levels[i % 6])
        }
        for (i in 0 until 24) {
            val v = 8 + i * 10
            table[232 + i] = argb(v, v, v)
        }
    }

    fun indexed(index: Int): Int = table[index and 0xFF]

    companion object {
        private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        /** "TermBridge Night": matches the app's ink background and mint accent. */
        val Night = TerminalPalette(
            background = 0xFF0B0E14.toInt(),
            foreground = 0xFFD6DEEB.toInt(),
            cursor = 0xFF3DDC97.toInt(),
            ansi = intArrayOf(
                0xFF1B2029.toInt(), 0xFFFF5F6D.toInt(), 0xFF3DDC97.toInt(), 0xFFFFB454.toInt(),
                0xFF59C2FF.toInt(), 0xFFD2A6FF.toInt(), 0xFF5CE1E6.toInt(), 0xFFC7CED9.toInt(),
                0xFF4A5366.toInt(), 0xFFFF7A85.toInt(), 0xFF6BE9B0.toInt(), 0xFFFFCB7D.toInt(),
                0xFF82D2FF.toInt(), 0xFFE0C0FF.toInt(), 0xFF86EEF2.toInt(), 0xFFF0F4F8.toInt(),
            ),
        )
    }
}
