package io.termbridge.core.terminal

/** Receives the actions recognized by [VtParser]. */
interface VtHandler {
    fun print(codePoint: Int)

    /** A C0 control (BEL, BS, HT, LF, VT, FF, CR, SO, SI, …). */
    fun execute(control: Int)

    /** `CSI [marker] params [intermediates] final`; [marker] is `?`, `>`, `<`, `=` or 0. */
    fun csiDispatch(params: CsiParams, intermediates: Int, final: Int, marker: Int)

    fun escDispatch(intermediates: Int, final: Int)

    /** The OSC string, e.g. `0;window title`. */
    fun oscDispatch(command: String)
}

/** CSI parameters. Empty parameters read as -1 from [raw]. */
class CsiParams {
    private val values = IntArray(MAX)
    private var subparams = 0L
    private var current = -1

    var size = 0
        private set

    /** The parameter, or -1 when absent or empty. */
    fun raw(index: Int): Int = if (index < size) values[index] else -1

    /** The parameter, or [default] when absent, empty or 0 (VT convention). */
    fun get(index: Int, default: Int): Int = raw(index).let { if (it <= 0) default else it }

    /** True when the parameter was introduced by `:` (e.g. SGR `38:2:r:g:b`). */
    fun isSubparam(index: Int): Boolean = index in 0 until MAX && (subparams ushr index) and 1L == 1L

    internal fun reset() {
        size = 0
        subparams = 0
        current = -1
    }

    internal fun digit(d: Int) {
        current = if (current < 0) d else minOf(current * 10 + d, 0xFFFF)
    }

    internal fun separator(colon: Boolean) {
        push()
        if (colon && size < MAX) subparams = subparams or (1L shl size)
    }

    internal fun finish() {
        if (size > 0 || current >= 0) push()
    }

    private fun push() {
        if (size < MAX) values[size++] = current
        current = -1
    }

    private companion object {
        const val MAX = 32
    }
}

/**
 * A byte-stream parser for VT/xterm escape sequences, following Paul Williams' DEC ANSI parser
 * state machine, with streaming UTF-8 decoding in the ground state. A multi-byte character or an
 * escape sequence split across two [feed] calls decodes exactly as if it arrived whole.
 */
class VtParser(private val handler: VtHandler) {
    private var state = GROUND
    private val params = CsiParams()
    private var intermediates = 0
    private var marker = 0

    private var osc = ByteArray(256)
    private var oscLength = 0

    private var utf8Remaining = 0
    private var utf8CodePoint = 0
    private var utf8Min = 0

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        for (i in offset until offset + length) advance(data[i].toInt() and 0xFF)
    }

    private fun advance(b: Int) {
        if (utf8Remaining > 0) {
            if (b and 0xC0 == 0x80) {
                utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
                if (--utf8Remaining == 0) {
                    val cp = utf8CodePoint
                    val valid = cp >= utf8Min && cp <= 0x10FFFF && cp !in 0xD800..0xDFFF
                    handler.print(if (valid) cp else REPLACEMENT)
                }
                return
            }
            utf8Remaining = 0
            handler.print(REPLACEMENT) // truncated sequence; handle b afresh
        }

        // Transitions valid from any state.
        when (b) {
            ESC -> {
                if (state == OSC_STRING) dispatchOsc() // ESC \ (ST) terminates OSC
                enterEscape()
                return
            }
            CAN, SUB -> {
                if (state == GROUND) handler.execute(b) else state = GROUND
                return
            }
        }

        when (state) {
            GROUND -> ground(b)
            ESCAPE -> escape(b)
            ESCAPE_INTERMEDIATE -> escapeIntermediate(b)
            CSI_ENTRY -> csiEntry(b)
            CSI_PARAM -> csiParam(b)
            CSI_INTERMEDIATE -> csiIntermediate(b)
            CSI_IGNORE -> if (b < 0x20) handler.execute(b) else if (b in 0x40..0x7E) state = GROUND
            OSC_STRING -> oscString(b)
            STRING_IGNORE -> Unit // DCS / SOS / PM / APC payloads are consumed until ST
        }
    }

    private fun ground(b: Int) {
        when {
            b < 0x20 -> handler.execute(b)
            b == DEL -> Unit
            b < 0x80 -> handler.print(b)
            b in 0xC2..0xDF -> startUtf8(1, b and 0x1F, 0x80)
            b in 0xE0..0xEF -> startUtf8(2, b and 0x0F, 0x800)
            b in 0xF0..0xF4 -> startUtf8(3, b and 0x07, 0x10000)
            else -> handler.print(REPLACEMENT)
        }
    }

    private fun startUtf8(remaining: Int, bits: Int, min: Int) {
        utf8Remaining = remaining
        utf8CodePoint = bits
        utf8Min = min
    }

    private fun enterEscape() {
        state = ESCAPE
        intermediates = 0
    }

    private fun escape(b: Int) {
        when (b) {
            in 0x00..0x1F -> handler.execute(b)
            in 0x20..0x2F -> {
                collect(b)
                state = ESCAPE_INTERMEDIATE
            }
            '['.code -> {
                params.reset()
                marker = 0
                state = CSI_ENTRY
            }
            ']'.code -> {
                oscLength = 0
                state = OSC_STRING
            }
            'P'.code, 'X'.code, '^'.code, '_'.code -> state = STRING_IGNORE
            in 0x30..0x7E -> {
                handler.escDispatch(intermediates, b)
                state = GROUND
            }
        }
    }

    private fun escapeIntermediate(b: Int) {
        when (b) {
            in 0x00..0x1F -> handler.execute(b)
            in 0x20..0x2F -> collect(b)
            in 0x30..0x7E -> {
                handler.escDispatch(intermediates, b)
                state = GROUND
            }
        }
    }

    private fun csiEntry(b: Int) {
        if (b in 0x3C..0x3F) {
            marker = b
            state = CSI_PARAM
        } else {
            state = CSI_PARAM
            csiParam(b)
        }
    }

    private fun csiParam(b: Int) {
        when (b) {
            in 0x00..0x1F -> handler.execute(b)
            in '0'.code..'9'.code -> params.digit(b - '0'.code)
            ';'.code -> params.separator(colon = false)
            ':'.code -> params.separator(colon = true)
            in 0x3C..0x3F -> state = CSI_IGNORE
            in 0x20..0x2F -> {
                collect(b)
                state = CSI_INTERMEDIATE
            }
            in 0x40..0x7E -> dispatchCsi(b)
        }
    }

    private fun csiIntermediate(b: Int) {
        when (b) {
            in 0x00..0x1F -> handler.execute(b)
            in 0x20..0x2F -> collect(b)
            in 0x30..0x3F -> state = CSI_IGNORE
            in 0x40..0x7E -> dispatchCsi(b)
        }
    }

    private fun dispatchCsi(final: Int) {
        params.finish()
        handler.csiDispatch(params, intermediates, final, marker)
        state = GROUND
    }

    private fun collect(b: Int) {
        if (intermediates <= 0xFF) intermediates = (intermediates shl 8) or b
    }

    private fun oscString(b: Int) {
        when {
            b == BEL -> dispatchOsc()
            b < 0x20 -> Unit
            oscLength < MAX_OSC -> {
                if (oscLength == osc.size) osc = osc.copyOf(minOf(osc.size * 2, MAX_OSC))
                osc[oscLength++] = b.toByte()
            }
        }
    }

    private fun dispatchOsc() {
        handler.oscDispatch(osc.decodeToString(0, oscLength))
        oscLength = 0
        state = GROUND
    }

    private companion object {
        const val GROUND = 0
        const val ESCAPE = 1
        const val ESCAPE_INTERMEDIATE = 2
        const val CSI_ENTRY = 3
        const val CSI_PARAM = 4
        const val CSI_INTERMEDIATE = 5
        const val CSI_IGNORE = 6
        const val OSC_STRING = 7
        const val STRING_IGNORE = 8

        const val BEL = 0x07
        const val CAN = 0x18
        const val SUB = 0x1A
        const val ESC = 0x1B
        const val DEL = 0x7F
        const val MAX_OSC = 4096
        const val REPLACEMENT = 0xFFFD
    }
}
