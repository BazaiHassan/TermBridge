package io.termbridge.core.terminal

/** Keys that are not plain characters. */
enum class Key {
    ENTER, TAB, BACKSPACE, ESCAPE,
    UP, DOWN, LEFT, RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** Modifier bit set. */
object Mods {
    const val NONE = 0
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
}

/**
 * Sticky modifier on the extra-keys row (architecture §7.4): one tap arms it for the next key,
 * a second tap locks it, a third releases it.
 */
enum class Sticky {
    OFF, ONCE, LOCKED;

    val active: Boolean get() = this != OFF

    fun tapped(): Sticky = when (this) {
        OFF -> ONCE
        ONCE -> LOCKED
        LOCKED -> OFF
    }

    /** State after a key has used the modifier. */
    fun consumed(): Sticky = if (this == ONCE) OFF else this
}

/** Translates keys into the byte sequences xterm sends. */
object KeyEncoder {
    private const val ESC = ""

    /** [appCursor] is DECCKM (set by vim, less, …): arrows then send `ESC O x`. */
    fun encode(key: Key, mods: Int = Mods.NONE, appCursor: Boolean = false): ByteArray {
        val m = if (mods == Mods.NONE) 0 else 1 + mods // xterm modifier parameter
        val seq = when (key) {
            Key.ENTER -> if (mods and Mods.ALT != 0) "$ESC\r" else "\r"
            Key.TAB -> if (mods and Mods.SHIFT != 0) "$ESC[Z" else "\t"
            Key.BACKSPACE -> when {
                mods and Mods.CTRL != 0 -> ""
                mods and Mods.ALT != 0 -> "$ESC"
                else -> ""
            }
            Key.ESCAPE -> ESC
            Key.UP -> cursor('A', m, appCursor)
            Key.DOWN -> cursor('B', m, appCursor)
            Key.RIGHT -> cursor('C', m, appCursor)
            Key.LEFT -> cursor('D', m, appCursor)
            Key.HOME -> cursor('H', m, appCursor)
            Key.END -> cursor('F', m, appCursor)
            Key.INSERT -> tilde(2, m)
            Key.DELETE -> tilde(3, m)
            Key.PAGE_UP -> tilde(5, m)
            Key.PAGE_DOWN -> tilde(6, m)
            Key.F1 -> ss3('P', m)
            Key.F2 -> ss3('Q', m)
            Key.F3 -> ss3('R', m)
            Key.F4 -> ss3('S', m)
            Key.F5 -> tilde(15, m)
            Key.F6 -> tilde(17, m)
            Key.F7 -> tilde(18, m)
            Key.F8 -> tilde(19, m)
            Key.F9 -> tilde(20, m)
            Key.F10 -> tilde(21, m)
            Key.F11 -> tilde(23, m)
            Key.F12 -> tilde(24, m)
        }
        return seq.encodeToByteArray()
    }

    /** A typed character with optional Ctrl / Alt. */
    fun encodeChar(codePoint: Int, mods: Int = Mods.NONE): ByteArray {
        var cp = codePoint
        if (mods and Mods.CTRL != 0) cp = ctrl(cp)
        val utf8 = String(Character.toChars(cp)).encodeToByteArray()
        return if (mods and Mods.ALT != 0) byteArrayOf(0x1b) + utf8 else utf8
    }

    private fun ctrl(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        '@'.code, ' '.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code -> 31
        '8'.code, '?'.code -> 127
        else -> cp
    }

    private fun cursor(final: Char, m: Int, appCursor: Boolean): String = when {
        m != 0 -> "$ESC[1;$m$final"
        appCursor -> "${ESC}O$final"
        else -> "$ESC[$final"
    }

    private fun ss3(final: Char, m: Int) = if (m != 0) "$ESC[1;$m$final" else "${ESC}O$final"

    private fun tilde(code: Int, m: Int) = if (m != 0) "$ESC[$code;$m~" else "$ESC[$code~"
}
