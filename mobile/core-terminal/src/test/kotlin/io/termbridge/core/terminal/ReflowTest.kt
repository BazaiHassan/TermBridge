package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReflowTest {
    private val esc = 27.toChar()
    private val ni = String(Character.toChars(0x4F60)) // a wide CJK character

    private fun emu(cols: Int, rows: Int, scrollback: Int = 100) = TerminalEmulator(cols, rows, scrollback)
    private fun TerminalEmulator.feed(s: String) = feed(s.encodeToByteArray())
    private fun TerminalEmulator.row(r: Int) = line(r).textString()
    private fun TerminalEmulator.rows() = (0 until rows).map { row(it) }

    @Test
    fun narrowingWrapsLongLinesInsteadOfCuttingThem() {
        val t = emu(cols = 10, rows = 4)
        t.feed("abcdefgh\r\nxy")
        t.resize(4, 4)
        assertEquals(listOf("abcd", "efgh", "xy", ""), t.rows())
        assertTrue(t.line(0).wrapped)
        assertFalse(t.line(1).wrapped)
        assertEquals(2 to 2, t.cursorRow to t.cursorCol)
    }

    @Test
    fun wideningJoinsWrappedRows() {
        val t = emu(cols = 4, rows = 4)
        t.feed("abcdefgh")
        t.resize(10, 4)
        assertEquals("abcdefgh", t.row(0))
        assertFalse(t.line(0).wrapped)
        assertEquals(0 to 8, t.cursorRow to t.cursorCol)
    }

    @Test
    fun roundTripRestoresTheOriginalLayout() {
        val t = emu(cols = 12, rows = 4)
        t.feed("hello world\r\nsecond line\r\n$ ")
        val before = t.rows()
        t.resize(5, 4)
        t.resize(12, 4)
        assertEquals(before, t.rows())
        assertEquals(2 to 2, t.cursorRow to t.cursorCol)
    }

    @Test
    fun wideCharactersAreNeverSplit() {
        val t = emu(cols = 6, rows = 4)
        t.feed("ab${ni}cd")
        t.resize(3, 4)
        assertEquals(listOf("ab", "${ni}c", "d", ""), t.rows())
        t.resize(6, 4)
        assertEquals("ab${ni}cd", t.row(0))
    }

    @Test
    fun rowsThatNoLongerFitMoveIntoScrollback() {
        val t = emu(cols = 6, rows = 2)
        t.feed("hello\r\nworld")
        t.resize(3, 2)
        assertEquals(listOf("wor", "ld"), t.rows())
        assertEquals(2, t.scrollback.size)
        assertEquals("hel", t.scrollback[0].textString())
        assertEquals("lo", t.scrollback[1].textString())
        assertEquals(1 to 2, t.cursorRow to t.cursorCol)
    }

    @Test
    fun scrollbackIsReflowedToo() {
        val t = emu(cols = 4, rows = 2)
        t.feed("abcdefgh\r\nx\r\ny")
        t.resize(8, 2)
        assertEquals(listOf("x", "y"), t.rows())
        assertEquals("abcdefgh", t.scrollback[t.scrollback.size - 1].textString())
    }

    @Test
    fun stylesAndMarksTravelWithTheirCharacters() {
        val t = emu(cols = 4, rows = 3)
        t.feed("ab${esc}[31mc${String(Character.toChars(0x301))}d")
        val red = t.line(0).styles[2]
        t.resize(2, 3)
        assertEquals("c${String(Character.toChars(0x301))}d", t.row(1))
        assertEquals(red, t.line(1).styles[0])
    }

    @Test
    fun cursorAfterTrailingSpacesStaysPut() {
        val t = emu(cols = 10, rows = 3)
        t.feed("ab   ")
        t.resize(20, 3)
        assertEquals(0 to 5, t.cursorRow to t.cursorCol)
    }

    @Test
    fun heightOnlyChangesDoNotReflow() {
        val t = emu(cols = 4, rows = 4)
        t.feed("abcdefgh")
        t.resize(4, 3)
        assertEquals(listOf("abcd", "efgh", ""), t.rows())
    }

    @Test
    fun altScreenIsLeftAloneButTheMainScreenUnderItReflows() {
        val t = emu(cols = 10, rows = 3)
        t.feed("abcdefgh")
        t.feed("$esc[?1049h$esc[HALT") // 1049 keeps the cursor where it was: home it first
        t.resize(4, 3)
        assertEquals("ALT", t.row(0))
        t.feed("$esc[?1049l")
        assertEquals(listOf("abcd", "efgh", ""), t.rows())
        assertEquals(1 to 3, t.cursorRow to t.cursorCol)
    }
}
