package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyEncoderTest {
    private fun enc(key: Key, mods: Int = Mods.NONE, app: Boolean = false) = KeyEncoder.encode(key, mods, app).decodeToString()
    private fun chr(c: Char, mods: Int) = KeyEncoder.encodeChar(c.code, mods).decodeToString()

    @Test
    fun arrowsFollowCursorKeyMode() {
        assertEquals("[A", enc(Key.UP))
        assertEquals("OA", enc(Key.UP, app = true))
        assertEquals("[1;5D", enc(Key.LEFT, Mods.CTRL, app = true))
    }

    @Test
    fun editingAndFunctionKeys() {
        assertEquals("\r", enc(Key.ENTER))
        assertEquals("", enc(Key.BACKSPACE))
        assertEquals("[Z", enc(Key.TAB, Mods.SHIFT))
        assertEquals("[3~", enc(Key.DELETE))
        assertEquals("OP", enc(Key.F1))
        assertEquals("[15~", enc(Key.F5))
        assertEquals("[24;2~", enc(Key.F12, Mods.SHIFT))
    }

    @Test
    fun ctrlAndAltCharacters() {
        assertEquals("", chr('c', Mods.CTRL))
        assertEquals("", chr('C', Mods.CTRL))
        assertEquals("", chr('[', Mods.CTRL))
        assertEquals("x", chr('x', Mods.ALT))
        assertEquals("", chr('x', Mods.CTRL or Mods.ALT))
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
