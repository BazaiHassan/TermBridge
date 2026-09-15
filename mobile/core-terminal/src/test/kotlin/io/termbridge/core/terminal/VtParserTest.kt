package io.termbridge.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VtParserTest {
    private class Recorder : VtHandler {
        val events = mutableListOf<String>()
        override fun print(codePoint: Int) {
            events += "print ${String(Character.toChars(codePoint))}"
        }
        override fun execute(control: Int) {
            events += "exec $control"
        }
        override fun csiDispatch(params: CsiParams, intermediates: Int, final: Int, marker: Int) {
            val ps = (0 until params.size).joinToString(",") { i ->
                (if (params.isSubparam(i)) ":" else "") + params.raw(i)
            }
            val m = if (marker == 0) "" else marker.toChar().toString()
            events += "csi $m[$ps] ${final.toChar()}"
        }
        override fun escDispatch(intermediates: Int, final: Int) {
            events += "esc $intermediates ${final.toChar()}"
        }
        override fun oscDispatch(command: String) {
            events += "osc $command"
        }
    }

    private fun parse(s: String): List<String> = Recorder().also { VtParser(it).feed(s.encodeToByteArray()) }.events

    @Test
    fun csiParamsWithEmptyAndPrivateMarker() {
        assertEquals(listOf("csi [-1,5] H"), parse("[;5H"))
        assertEquals(listOf("csi ?[1049] h"), parse("[?1049h"))
        assertEquals(listOf("csi [] m"), parse("[m"))
    }

    @Test
    fun colonSubparameters() {
        assertEquals(listOf("csi [38,:2,:-1,:1,:2,:3] m"), parse("[38:2::1:2:3m"))
    }

    @Test
    fun controlsExecuteInsideCsi() {
        assertEquals(listOf("exec 13", "csi [2] J"), parse("[2\rJ"))
    }

    @Test
    fun dcsIsSwallowedUntilStringTerminator() {
        assertEquals(listOf("esc 0 \\", "print x"), parse("Pq#0;2;0;0;0\\x"))
    }

    @Test
    fun canAbortsSequence() {
        assertEquals(listOf("print A"), parse("[12A"))
    }

    @Test
    fun invalidUtf8BecomesReplacementCharacter() {
        val events = Recorder().also { VtParser(it).feed(byteArrayOf(0xC3.toByte(), 'a'.code.toByte(), 0xFF.toByte())) }.events
        assertEquals(listOf("print �", "print a", "print �"), events)
    }

    @Test
    fun overlongOscIsTruncatedNotUnbounded() {
        val events = parse("]0;" + "x".repeat(10_000) + "")
        assertTrue(events.single().length < 4200)
    }
}
