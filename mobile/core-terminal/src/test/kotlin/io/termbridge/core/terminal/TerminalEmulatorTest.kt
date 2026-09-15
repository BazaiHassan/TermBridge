package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    private fun emu(cols: Int = 10, rows: Int = 4, scrollback: Int = 100) = TerminalEmulator(cols, rows, scrollback)

    private fun TerminalEmulator.feed(s: String) = feed(s.encodeToByteArray())
    private fun TerminalEmulator.row(r: Int) = line(r).textString()

    @Test
    fun printsTextAndHandlesCrLf() {
        val t = emu()
        t.feed("hello\r\nworld")
        assertEquals("hello", t.row(0))
        assertEquals("world", t.row(1))
        assertEquals(1 to 5, t.cursorRow to t.cursorCol)
    }

    @Test
    fun autowrapsAtRightEdge() {
        val t = emu(cols = 5)
        t.feed("abcdefg")
        assertEquals("abcde", t.row(0))
        assertTrue(t.line(0).wrapped)
        assertEquals("fg", t.row(1))
    }

    @Test
    fun fullLineThenCrLfDoesNotLeaveBlankLine() {
        val t = emu(cols = 5)
        t.feed("abcde\r\nx")
        assertEquals("abcde", t.row(0))
        assertEquals("x", t.row(1))
    }

    @Test
    fun cursorPositionAndErase() {
        val t = emu()
        t.feed("0123456789\u001b[1;4H\u001b[K")
        assertEquals("012", t.row(0))
        t.feed("\u001b[2;1Hxyz\u001b[2J")
        assertEquals("", t.row(0))
        assertEquals("", t.row(1))
    }

    @Test
    fun sgrColorsAndAttributes() {
        val t = emu()
        t.feed("\u001b[1;31mA\u001b[38;2;10;20;30mB\u001b[38:2::1:2:3mC\u001b[48;5;200mD\u001b[0mE\u001b[94mF")
        val s = t.line(0).styles
        assertEquals(Style.BOLD, Style.flags(s[0]))
        assertEquals(TermColor.indexed(1), Style.fg(s[0]))
        assertEquals(TermColor.rgb(10, 20, 30), Style.fg(s[1]))
        assertEquals(TermColor.rgb(1, 2, 3), Style.fg(s[2]))
        assertEquals(TermColor.indexed(200), Style.bg(s[3]))
        assertEquals(Style.DEFAULT, s[4])
        assertEquals(TermColor.indexed(12), Style.fg(s[5]))
    }

    @Test
    fun linesScrollIntoScrollback() {
        val t = emu(rows = 3)
        t.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(listOf("3", "4", "5"), (0..2).map { t.row(it) })
        assertEquals(2, t.scrollback.size)
        assertEquals("1", t.scrollback[0].textString())
        assertEquals("2", t.visibleLine(0, offset = 1).textString())
        assertEquals(2L, t.scrollCounter)
    }

    @Test
    fun scrollbackRecyclesLinesWhenFull() {
        val t = emu(rows = 2, scrollback = 3)
        repeat(20) { t.feed("line$it\r\n") }
        assertEquals(3, t.scrollback.size)
        assertEquals("line18", t.scrollback[2].textString())
        assertEquals("line19", t.row(0))
    }

    @Test
    fun scrollRegionLeavesOtherRowsAndScrollback() {
        val t = emu(rows = 4)
        t.feed("top\u001b[2;3r\u001b[2;1Ha\r\nb\r\nc")
        assertEquals(listOf("top", "b", "c", ""), (0..3).map { t.row(it) })
        assertEquals(0, t.scrollback.size)
    }

    @Test
    fun alternateScreenRestoresMainScreen() {
        val t = emu()
        t.feed("shell$ ")
        t.feed("\u001b[?1049hVIM\u001b[?1049l")
        assertEquals("shell$", t.row(0))
        assertEquals(0 to 7, t.cursorRow to t.cursorCol)
        assertFalse(t.isAltScreen)
    }

    @Test
    fun utf8SplitAcrossFeeds() {
        val t = emu()
        val bytes = "سلام".encodeToByteArray()
        for (b in bytes) t.feed(byteArrayOf(b))
        assertEquals("سلام", t.row(0))
    }

    @Test
    fun oscTitleWithBelAndSt() {
        val t = emu()
        t.feed("\u001b]0;first\u0007")
        assertEquals("first", t.title)
        t.feed("\u001b]2;second\u001b\\x")
        assertEquals("second", t.title)
        assertEquals("x", t.row(0))
    }

    @Test
    fun cursorPositionReport() {
        val t = emu()
        var reply = ""
        t.listener = object : TerminalEmulator.Listener {
            override fun onResponse(bytes: ByteArray) {
                reply = bytes.decodeToString()
            }
        }
        t.feed("\u001b[3;5H\u001b[6n")
        assertEquals("\u001b[3;5R", reply)
    }

    @Test
    fun repeatInsertAndDeleteCharacters() {
        val t = emu()
        t.feed("-\u001b[4b")
        assertEquals("-----", t.row(0))
        t.feed("\r\u001b[2@ab")
        assertEquals("ab-----", t.row(0))
        t.feed("\r\u001b[3P")
        assertEquals("----", t.row(0))
    }

    @Test
    fun resizeKeepsCursorLineVisible() {
        val t = emu(rows = 4)
        t.feed("a\r\nb\r\nc\r\nd")
        t.resize(10, 2)
        assertEquals(listOf("c", "d"), (0..1).map { t.row(it) })
        assertEquals(1, t.cursorRow)
        assertEquals("b", t.scrollback[t.scrollback.size - 1].textString())
    }

    @Test
    fun decModesAndLineDrawing() {
        val t = emu()
        t.feed("\u001b[?1h\u001b[?2004h\u001b[?25l")
        assertTrue(t.appCursorKeys)
        assertTrue(t.bracketedPaste)
        assertFalse(t.cursorVisible)
        t.feed("\u001b(0lqk\u001b(Bq")
        assertEquals("┌─┐q", t.row(0))
    }

    @Test
    fun dirtyTrackingMarksOnlyTouchedRows() {
        val t = emu()
        t.clearDirty()
        t.feed("\u001b[3;1Hx")
        assertEquals(listOf(false, false, true, false), (0..3).map { t.isDirty(it) })
    }
}
