package io.termbridge.core.terminal

/** One row of cells: code points (0 = empty) and packed [Style]s. */
class TerminalLine(cols: Int) {
    var text = IntArray(cols)
        private set
    var styles = LongArray(cols)
        private set

    /** True when the row continues on the next one (autowrap), for copy and future reflow. */
    var wrapped = false

    val cols: Int get() = text.size

    fun clear(style: Long) {
        text.fill(0)
        styles.fill(style)
        wrapped = false
    }

    fun clear(from: Int, to: Int, style: Long) {
        val end = minOf(to, cols)
        if (from >= end) return
        text.fill(0, from, end)
        styles.fill(style, from, end)
    }

    fun resize(cols: Int) {
        if (cols == text.size) return
        text = text.copyOf(cols)
        styles = styles.copyOf(cols)
    }

    /** Shifts cells right from [at], dropping those pushed past the edge (ICH). */
    fun insertCells(at: Int, count: Int, style: Long) {
        if (at >= cols) return
        val n = minOf(count, cols - at)
        text.copyInto(text, at + n, at, cols - n)
        styles.copyInto(styles, at + n, at, cols - n)
        clear(at, at + n, style)
    }

    /** Shifts cells left onto [at], blanking the vacated right edge (DCH). */
    fun deleteCells(at: Int, count: Int, style: Long) {
        if (at >= cols) return
        val n = minOf(count, cols - at)
        text.copyInto(text, at, at + n, cols)
        styles.copyInto(styles, at, at + n, cols)
        clear(cols - n, cols, style)
    }

    /** The row as text with trailing blanks removed (as terminals copy it); empty cells read as spaces. */
    fun textString(): String {
        var end = cols
        while (end > 0 && (text[end - 1] == 0 || text[end - 1] == ' '.code)) end--
        val sb = StringBuilder(end)
        for (i in 0 until end) sb.appendCodePoint(if (text[i] == 0) ' '.code else text[i])
        return sb.toString()
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
