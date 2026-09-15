package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyEncoderTest {
    private fun enc(key: Key, mods: Int = Mods.NONE, app: Boolean = false) = KeyEncoder.encode(key, mods, app).decodeToString()
    private fun chr(c: Char, mods: Int) = KeyEncoder.encodeChar(c.code, mods).decodeToString()

    @Test
    fun arrowsFollowCursorKeyMode() {
        assertEquals("\u001b[A", enc(Key.UP))
        assertEquals("\u001bOA", enc(Key.UP, app = true))
        assertEquals("\u001b[1;5D", enc(Key.LEFT, Mods.CTRL, app = true))
    }

    @Test
    fun editingAndFunctionKeys() {
        assertEquals("\r", enc(Key.ENTER))
        assertEquals("\u007f", enc(Key.BACKSPACE))
        assertEquals("\u001b[Z", enc(Key.TAB, Mods.SHIFT))
        assertEquals("\u001b[3~", enc(Key.DELETE))
        assertEquals("\u001bOP", enc(Key.F1))
        assertEquals("\u001b[15~", enc(Key.F5))
        assertEquals("\u001b[24;2~", enc(Key.F12, Mods.SHIFT))
    }

    @Test
    fun ctrlAndAltCharacters() {
        assertEquals("\u0003", chr('c', Mods.CTRL))
        assertEquals("\u0003", chr('C', Mods.CTRL))
        assertEquals("\u001b", chr('[', Mods.CTRL))
        assertEquals("\u001bx", chr('x', Mods.ALT))
        assertEquals("\u001b\u0018", chr('x', Mods.CTRL or Mods.ALT))
        assertEquals("ش", KeyEncoder.encodeChar('ش'.code).decodeToString())
    }

    @Test
    fun stickyModifierCycle() {
        assertEquals(Sticky.ONCE, Sticky.OFF.tapped())
        assertEquals(Sticky.LOCKED, Sticky.ONCE.tapped())
        assertEquals(Sticky.OFF, Sticky.LOCKED.tapped())
        assertEquals(Sticky.OFF, Sticky.ONCE.consumed())
        assertEquals(Sticky.LOCKED, Sticky.LOCKED.consumed())
    }
}
