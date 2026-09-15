package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WideCharTest {
    private fun emu(cols: Int = 10, rows: Int = 3) = TerminalEmulator(cols, rows, 100)
    private fun TerminalEmulator.feed(s: String) = feed(s.encodeToByteArray())
    private fun TerminalEmulator.row(r: Int) = line(r).textString()

    @Test
    fun widths() {
        assertEquals(1, CharWidth.of('a'.code))
        assertEquals(1, CharWidth.of(0x0628)) // Arabic beh
        assertEquals(0, CharWidth.of(0x064E)) // fatha
        assertEquals(0, CharWidth.of(0x0301)) // combining acute
        assertEquals(0, CharWidth.of(0x200D)) // zero-width joiner
        assertEquals(0, CharWidth.of(0xFE0F)) // variation selector 16
        assertEquals(2, CharWidth.of(0x4F60)) // CJK
        assertEquals(2, CharWidth.of(0xAC00)) // Hangul syllable
        assertEquals(2, CharWidth.of(0xFF21)) // fullwidth A
        assertEquals(2, CharWidth.of(0x1F642)) // emoji
        assertEquals(1, CharWidth.of(0x2500)) // box drawing stays narrow
        assertEquals(1, CharWidth.of(0x20AC)) // euro sign
        assertEquals(1, CharWidth.of(0xAD)) // soft hyphen
    }

    @Test
    fun wideCharacterTakesTwoCells() {
        val t = emu()
        t.feed("a\u4F60b")
        assertEquals("a\u4F60b", t.row(0))
        assertEquals(TerminalLine.WIDE_TAIL, t.line(0).text[2])
        assertEquals('b'.code, t.line(0).text[3])
        assertEquals(4, t.cursorCol)
    }

    @Test
    fun emojiAdvancesTwoColumns() {
        val t = emu()
        t.feed("\uD83D\uDE42!")
        assertEquals("\uD83D\uDE42!", t.row(0))
        assertEquals(3, t.cursorCol)
    }

    @Test
    fun wideCharacterWrapsInsteadOfSplitting() {
        val t = emu(cols = 5)
        t.feed("abcd\u4F60")
        assertEquals("abcd", t.row(0))
        assertTrue(t.line(0).wrapped)
        assertEquals("\u4F60", t.row(1))
        assertEquals(1 to 2, t.cursorRow to t.cursorCol)
    }

    @Test
    fun wideCharacterFillingTheLastTwoColumnsWrapsOnTheNextPrint() {
        val t = emu(cols = 4)
        t.feed("ab\u4F60c")
        assertEquals("ab\u4F60", t.row(0))
        assertEquals("c", t.row(1))
    }

    @Test
    fun overwritingEitherHalfErasesTheWholeWideCharacter() {
        val t = emu()
        t.feed("\u4F60\u597D") // two wide characters: cells 0-1 and 2-3
        t.feed("\u001B[1;2Hx") // onto the right half of the first
        assertEquals(" x\u597D", t.row(0))
        t.feed("\u001B[1;3Hy") // onto the left half of the second
        assertEquals(" xy", t.row(0))
        assertEquals(0, t.line(0).text[3])
    }

    @Test
    fun combiningMarksJoinThePreviousCell() {
        val t = emu()
        t.feed("e\u0301x")
        assertEquals("e\u0301x", t.row(0))
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun persianDiacriticsStayOnTheirLetter() {
        val t = emu()
        t.feed("\u0628\u064E\u0631")
        assertEquals("\u0628\u064E\u0631", t.row(0))
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun markAfterAWideCharacterAttachesToItsLeftHalf() {
        val t = emu()
        t.feed("\u2764\uFE0F\u4F60\uFE0F")
        assertEquals("\u2764\uFE0F\u4F60\uFE0F", t.row(0))
    }

    @Test
    fun erasingFromTheRightHalfErasesTheWholeCharacter() {
        val t = emu()
        t.feed("a\u4F60b\u001B[1;3H\u001B[K")
        assertEquals("a", t.row(0))
    }

    @Test
    fun deletingTheLeftHalfBlanksTheOrphanedRightHalf() {
        val t = emu()
        t.feed("\u4F60b\u001B[1;1H\u001B[P")
        assertEquals(" b", t.row(0))
    }

    @Test
    fun insertingPushesAWideCharacterOffTheEdgeCleanly() {
        val t = emu(cols = 4)
        t.feed("ab\u4F60\u001B[1;1H\u001B[@")
        assertEquals(" ab", t.row(0))
        assertEquals(0, t.line(0).text[3])
    }

    @Test
    fun narrowingSplitsNoWideCharacter() {
        val t = emu(cols = 4)
        t.feed("a\u4F60")
        t.resize(2, 3)
        assertEquals("a", t.row(0))
    }
}
