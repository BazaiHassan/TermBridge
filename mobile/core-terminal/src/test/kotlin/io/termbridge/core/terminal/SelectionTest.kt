package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionTest {
    private val esc = 27.toChar()
    private val ni = String(Character.toChars(0x4F60)) // a wide CJK character

    private fun emu(cols: Int = 12, rows: Int = 3) = TerminalEmulator(cols, rows, 100)
    private fun TerminalEmulator.feed(s: String) = feed(s.encodeToByteArray())

    @Test
    fun singleRow() {
        val t = emu()
        t.feed("hello world")
        assertEquals("hello", Selection(0, 0, 0, 4).text(t, 0))
        assertEquals("world", Selection(0, 6, 0, 20).text(t, 0))
    }

    @Test
    fun rowsJoinWithNewlinesAndDropTrailingBlanks() {
        val t = emu()
        t.feed("abc\r\ndef")
        assertEquals("bc\nde", Selection(0, 1, 1, 1).text(t, 0))
    }

    @Test
    fun wrappedRowsJoinWithoutANewline() {
        val t = emu(cols = 5)
        t.feed("abcdefg")
        assertEquals("abcdefg", Selection(0, 0, 1, 4).text(t, 0))
    }

    @Test
    fun draggingBackwardsSelectsTheSameCells() {
        val t = emu()
        t.feed("abc\r\ndef")
        val backwards = Selection(1, 1, 0, 1)
        assertEquals("bc\nde", backwards.text(t, 0))
        assertTrue(backwards.contains(0, 2))
        assertFalse(backwards.contains(0, 0))
        assertTrue(backwards.contains(1, 0))
        assertFalse(backwards.contains(1, 2))
    }

    @Test
    fun longPressSelectsAWholePath() {
        val t = emu(cols = 30)
        t.feed("ls /usr/local/bin now")
        val word = Selection.word(t, 0, 8, 0)
        assertEquals("/usr/local/bin", word.text(t, 0))
    }

    @Test
    fun longPressOnBlankSelectsOneCell() {
        val t = emu()
        t.feed("a  b")
        assertEquals(Selection(0, 1, 0, 1), Selection.word(t, 0, 1, 0))
    }

    @Test
    fun wideCharactersAndMarksCopyWhole() {
        val t = emu()
        t.feed("a${ni}b")
        assertEquals(ni, Selection(0, 2, 0, 2).text(t, 0)) // the right half alone
        assertEquals("a${ni}b", Selection(0, 0, 0, 3).text(t, 0))
        t.feed("${esc}[2;1He${String(Character.toChars(0x301))}")
        assertEquals("e${String(Character.toChars(0x301))}", Selection(1, 0, 1, 0).text(t, 0))
    }

    @Test
    fun selectionFollowsTheScrollOffset() {
        val t = emu(cols = 6, rows = 2)
        t.feed("one\r\ntwo\r\nthree")
        assertEquals("one", Selection(0, 0, 0, 5).text(t, 1)) // scrolled back one line
        assertEquals("three", Selection(1, 0, 1, 5).text(t, 0))
    }
}
