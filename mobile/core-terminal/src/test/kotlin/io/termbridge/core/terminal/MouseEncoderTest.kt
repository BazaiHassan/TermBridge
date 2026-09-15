package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MouseEncoderTest {
    private val esc = 27.toChar()

    @Test
    fun sgrReportsAreOneBasedAndSayPressOrRelease() {
        assertEquals("$esc[<0;5;3M", MouseEncoder.press(MouseEncoder.LEFT, 4, 2, sgr = true).decodeToString())
        assertEquals("$esc[<0;5;3m", MouseEncoder.release(MouseEncoder.LEFT, 4, 2, sgr = true).decodeToString())
        assertEquals("$esc[<64;1;1M", MouseEncoder.press(MouseEncoder.WHEEL_UP, 0, 0, sgr = true).decodeToString())
        assertEquals("$esc[<32;2;2M", MouseEncoder.drag(MouseEncoder.LEFT, 1, 1, sgr = true).decodeToString())
        assertEquals("$esc[<16;1;1M", MouseEncoder.press(MouseEncoder.LEFT, 0, 0, sgr = true, mods = Mods.CTRL).decodeToString())
        assertEquals("$esc[<0;300;1M", MouseEncoder.press(MouseEncoder.LEFT, 299, 0, sgr = true).decodeToString())
    }

    @Test
    fun legacyReportsAddThirtyTwoAndClamp() {
        assertContentEquals(byteArrayOf(27, 91, 77, 32, 37, 35), MouseEncoder.press(MouseEncoder.LEFT, 4, 2, sgr = false))
        assertContentEquals(byteArrayOf(27, 91, 77, 35, 33, 33), MouseEncoder.release(MouseEncoder.LEFT, 0, 0, sgr = false))
        val far = MouseEncoder.press(MouseEncoder.LEFT, 500, 500, sgr = false)
        assertEquals(255, far[4].toInt() and 0xFF)
        assertEquals(255, far[5].toInt() and 0xFF)
    }

    @Test
    fun emulatorFollowsMouseModes() {
        val t = TerminalEmulator(10, 3)
        assertEquals(MouseMode.NONE, t.mouseMode)
        t.feed("$esc[?1002h$esc[?1006h".encodeToByteArray())
        assertEquals(MouseMode.DRAG, t.mouseMode)
        assertTrue(t.mouseSgr)
        t.feed("$esc[?1002l".encodeToByteArray())
        assertEquals(MouseMode.NONE, t.mouseMode)
        t.feed("$esc[?1000h${esc}c".encodeToByteArray()) // RIS resets everything
        assertEquals(MouseMode.NONE, t.mouseMode)
        assertFalse(t.mouseSgr)
    }
}
