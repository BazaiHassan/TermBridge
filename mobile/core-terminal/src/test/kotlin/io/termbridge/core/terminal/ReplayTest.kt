package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real sessions of vim 9.2 and less, recorded in an 80x24 PTY with TERM=xterm-256color and
 * replayed through the emulator one scripted keystroke at a time. Each fixture in
 * resources/replays holds the raw output and the byte offset where every step's output ended.
 */
class ReplayTest {
    private class Replay(name: String) {
        val bytes = ReplayTest::class.java.getResourceAsStream("/replays/$name.bin")!!.readBytes()
        private val steps: Map<String, Int> = Regex("\"step\": \"(\\w+)\",\\s*\"offset\": (\\d+)")
            .findAll(ReplayTest::class.java.getResourceAsStream("/replays/$name.json")!!.readBytes().decodeToString())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val emu = TerminalEmulator(80, 24, 1000)
        private var fed = 0

        fun until(step: String): TerminalEmulator {
            val end = steps.getValue(step)
            emu.feed(bytes, fed, end - fed)
            fed = end
            return emu
        }
    }

    private fun TerminalEmulator.row(r: Int) = line(r).textString()
    private fun text(vararg cps: Int) = String(cps, 0, cps.size)
    private fun fox(n: Int) = "line %02d: the quick brown fox jumps over the lazy dog".format(n)
    private val cjk = text(0x4F60, 0x597D, 0x4E16, 0x754C)
    private val hello = "hello ${text(0x4F60, 0x597D)} world"

    @Test
    fun vimSession() {
        val r = Replay("vim")
        r.until("opened").let { t ->
            assertTrue(t.isAltScreen)
            assertEquals(fox(1), t.row(0))
            assertTrue(t.row(10).startsWith("long: 0123456789")) // vim wraps the 126-column line itself
            assertTrue(t.row(11).startsWith("456789"))
            assertTrue(t.row(21).startsWith("wide: $cjk and farsi: "))
            assertTrue(t.row(23).startsWith("\"sample.txt\" 32L, 1756B"))
            assertEquals(0 to 0, t.cursorRow to t.cursorCol)
        }
        r.until("end").let { t ->
            assertEquals(fox(30), t.row(22))
            assertTrue(t.row(23).endsWith("Bot"))
            assertEquals(22 to 0, t.cursorRow to t.cursorCol)
        }
        r.until("inserted").let { t ->
            assertEquals(hello, t.row(21))
            assertEquals("~", t.row(22))
        }
        r.until("numbered").let { t ->
            assertEquals(" 12 ${fox(11)}", t.row(0))
            assertEquals(" 33 $hello", t.row(21))
        }
        r.until("quit").let { t ->
            assertFalse(t.isAltScreen)
            assertTrue((0 until t.rows).all { t.row(it).isEmpty() }, "the shell's screen comes back as it was")
        }
    }

    @Test
    fun lessSession() {
        val r = Replay("less")
        r.until("opened").let { t ->
            assertTrue(t.isAltScreen)
            assertEquals("wide: $cjk and farsi: ${text(0x633, 0x644, 0x627, 0x645)}", t.row(21))
            assertEquals("sample.txt", t.row(23))
        }
        r.until("end").let { t ->
            assertEquals(fox(30), t.row(22))
            assertEquals("(END)", t.row(23))
        }
        r.until("quit").let { t -> assertFalse(t.isAltScreen) }
    }

    @Test
    fun rotatingWhileVimRunsBreaksNothing() {
        val r = Replay("vim")
        r.until("opened")
        r.emu.resize(40, 12) // vim keeps drawing for 80x24 until it handles SIGWINCH
        r.until("numbered")
        r.emu.resize(80, 24)
        assertFalse(r.until("quit").isAltScreen)
    }
}
