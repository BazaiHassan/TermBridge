package io.termbridge.core.terminal

/**
 * Colors packed in 26 bits: `0` is the default color, bit 24 marks a palette index (0–255)
 * and bit 25 a 24-bit RGB value.
 */
object TermColor {
    const val DEFAULT = 0
    private const val KIND_MASK = 3 shl 24
    private const val INDEXED = 1 shl 24
    private const val RGB = 2 shl 24

    fun indexed(index: Int): Int = INDEXED or (index and 0xFF)
    fun rgb(r: Int, g: Int, b: Int): Int = RGB or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isIndexed(color: Int): Boolean = color and KIND_MASK == INDEXED
    fun isRgb(color: Int): Boolean = color and KIND_MASK == RGB
    fun index(color: Int): Int = color and 0xFF
    fun rgbValue(color: Int): Int = color and 0xFFFFFF
}

/**
 * A cell style packed into one Long — foreground (26 bits) | background (26 bits) | flags
 * (12 bits) — so a row's styles are a single LongArray and printing allocates nothing.
 */
object Style {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val HIDDEN = 64
    const val STRIKE = 128

    const val DEFAULT = 0L
    private const val MASK26 = (1L shl 26) - 1

    fun pack(fg: Int, bg: Int, flags: Int): Long =
        (fg.toLong() and MASK26) or ((bg.toLong() and MASK26) shl 26) or ((flags.toLong() and 0xFFF) shl 52)

    fun fg(style: Long): Int = (style and MASK26).toInt()
    fun bg(style: Long): Int = ((style ushr 26) and MASK26).toInt()
    fun flags(style: Long): Int = ((style ushr 52) and 0xFFF).toInt()

    fun withFg(style: Long, fg: Int): Long = pack(fg, bg(style), flags(style))
    fun withBg(style: Long, bg: Int): Long = pack(fg(style), bg, flags(style))
    fun withFlags(style: Long, flags: Int): Long = pack(fg(style), bg(style), flags)

    /** Style used to clear cells: keeps only the background, as xterm does (BCE). */
    fun erase(style: Long): Long = pack(TermColor.DEFAULT, bg(style), 0)
}
