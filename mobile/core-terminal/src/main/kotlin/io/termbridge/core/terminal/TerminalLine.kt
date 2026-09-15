package io.termbridge.core.terminal

/**
 * One row of cells: code points (0 = empty) and packed [Style]s. A wide character (CJK, emoji)
 * fills two cells: its code point, then [WIDE_TAIL]. Zero-width code points (combining marks,
 * ZWJ, variation selectors) are kept per cell as [marks].
 */
class TerminalLine(cols: Int) {
    var text = IntArray(cols)
        private set
    var styles = LongArray(cols)
        private set

    /** Column → zero-width code points drawn with that cell; null while there are none. */
    private var marks: HashMap<Int, String>? = null

    /** True when the row continues on the next one (autowrap), for copy and future reflow. */
    var wrapped = false

    val cols: Int get() = text.size

    /** Zero-width code points attached to [col], or null. */
    fun marks(col: Int): String? = marks?.get(col)

    /** True when any cell has zero-width marks. */
    val hasMarks: Boolean get() = marks != null

    fun addMark(col: Int, cp: Int) {
        val m = marks ?: HashMap<Int, String>().also { marks = it }
        val prev = m[col].orEmpty()
        if (prev.length < MAX_MARK_CHARS) m[col] = prev + String(Character.toChars(cp))
    }

    /**
     * Writes [cp] at [col], and its right half at col + 1 when [wide]. A wide character the write
     * cuts in half is erased, as xterm does.
     */
    fun put(col: Int, cp: Int, style: Long, wide: Boolean) {
        val end = if (wide) col + 2 else col + 1
        if (col > 0 && text[col] == WIDE_TAIL) text[col - 1] = 0
        if (end < cols && text[end] == WIDE_TAIL) text[end] = 0
        text[col] = cp
        styles[col] = style
        if (wide) {
            text[col + 1] = WIDE_TAIL
            styles[col + 1] = style
        }
        dropMarks(col, end)
    }

    fun clear(style: Long) {
        text.fill(0)
        styles.fill(style)
        marks = null
        wrapped = false
    }

    /** Blanks [from, to); a wide character on either edge goes entirely. */
    fun clear(from: Int, to: Int, style: Long) {
        var start = from
        var end = minOf(to, cols)
        if (start >= end) return
        if (start > 0 && text[start] == WIDE_TAIL) start--
        if (end < cols && text[end] == WIDE_TAIL) end++
        text.fill(0, start, end)
        styles.fill(style, start, end)
        dropMarks(start, end)
    }

    fun resize(cols: Int) {
        if (cols == text.size) return
        text = text.copyOf(cols)
        styles = styles.copyOf(cols)
        marks?.keys?.removeAll { it >= cols }
        repairWide()
    }

    /** Shifts cells right from [at], dropping those pushed past the edge (ICH). */
    fun insertCells(at: Int, count: Int, style: Long) {
        if (at >= cols) return
        val n = minOf(count, cols - at)
        text.copyInto(text, at + n, at, cols - n)
        styles.copyInto(styles, at + n, at, cols - n)
        shiftMarks(at, n)
        clear(at, at + n, style)
        repairWide()
    }

    /** Shifts cells left onto [at], blanking the vacated right edge (DCH). */
    fun deleteCells(at: Int, count: Int, style: Long) {
        if (at >= cols) return
        val n = minOf(count, cols - at)
        text.copyInto(text, at, at + n, cols)
        styles.copyInto(styles, at, at + n, cols)
        dropMarks(at, at + n)
        shiftMarks(at + n, -n)
        clear(cols - n, cols, style)
        repairWide()
    }

    /** The row as text with trailing blanks removed (as terminals copy it); empty cells read as spaces. */
    fun textString(): String = textRange(0, cols)

    /**
     * Text of cells [from, to): a wide character once (even when only its right half is in range),
     * with its marks. Trailing blanks are dropped when [trim].
     */
    fun textRange(from: Int, to: Int, trim: Boolean = true): String {
        var start = from.coerceIn(0, cols)
        var end = to.coerceIn(0, cols)
        if (start in 1 until end && text[start] == WIDE_TAIL) start--
        if (trim) while (end > start && (text[end - 1] == 0 || text[end - 1] == ' '.code)) end--
        val sb = StringBuilder(maxOf(end - start, 0))
        for (i in start until end) {
            val cp = text[i]
            if (cp == WIDE_TAIL) continue
            sb.appendCodePoint(if (cp == 0) ' '.code else cp)
            marks?.get(i)?.let(sb::append)
        }
        return sb.toString()
    }

    private fun dropMarks(from: Int, to: Int) {
        val m = marks ?: return
        m.keys.removeAll { it in from until to }
        if (m.isEmpty()) marks = null
    }

    /** Moves marks at columns ≥ [from] by [delta], dropping those that leave the row. */
    private fun shiftMarks(from: Int, delta: Int) {
        val m = marks ?: return
        val moved = HashMap<Int, String>()
        for ((col, s) in m) {
            val to = if (col >= from) col + delta else col
            if (to in 0 until cols) moved[to] = s
        }
        marks = moved.ifEmpty { null }
    }

    /** Blanks halves of wide characters that a shift or resize split apart. */
    private fun repairWide() {
        for (c in 0 until cols) {
            val cp = text[c]
            if (cp == WIDE_TAIL) {
                if (c == 0 || text[c - 1] == WIDE_TAIL || CharWidth.of(text[c - 1]) != 2) text[c] = 0
            } else if (cp >= 0x1100 && CharWidth.of(cp) == 2 && (c + 1 >= cols || text[c + 1] != WIDE_TAIL)) {
                text[c] = 0
            }
        }
    }

    companion object {
        /** The right half of a wide character. */
        const val WIDE_TAIL = -1

        /** Caps a cell's zero-width code points (a stream of combining marks can't grow memory). */
        private const val MAX_MARK_CHARS = 16
    }
}

/**
 * Ring buffer of lines scrolled off the top of the main screen. When full, [push] returns the
 * evicted line so the caller can recycle it: scrolling stops allocating once warmed up.
 */
class Scrollback(val capacity: Int) {
    private val lines = arrayOfNulls<TerminalLine>(capacity)
    private var start = 0

    var size = 0
        private set

    fun push(line: TerminalLine): TerminalLine? {
        if (capacity == 0) return line
        if (size < capacity) {
            lines[(start + size) % capacity] = line
            size++
            return null
        }
        val evicted = lines[start]
        lines[start] = line
        start = (start + 1) % capacity
        return evicted
    }

    /** Index 0 is the oldest line. */
    operator fun get(index: Int): TerminalLine {
        if (index !in 0 until size) throw IndexOutOfBoundsException("scrollback index $index of $size")
        return lines[(start + index) % capacity]!!
    }

    fun clear() {
        lines.fill(null)
        start = 0
        size = 0
    }
}
