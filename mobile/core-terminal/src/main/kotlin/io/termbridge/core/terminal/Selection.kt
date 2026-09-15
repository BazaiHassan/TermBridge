package io.termbridge.core.terminal

/**
 * Selected cells in viewport coordinates, inclusive, as the rows appear at the scroll offset the
 * view had while selecting. [anchor] is where the selection started; the focus follows the finger
 * and may lie before the anchor.
 */
data class Selection(val anchorRow: Int, val anchorCol: Int, val focusRow: Int, val focusCol: Int) {
    private val forward: Boolean
        get() = anchorRow < focusRow || (anchorRow == focusRow && anchorCol <= focusCol)

    val startRow: Int get() = if (forward) anchorRow else focusRow
    val startCol: Int get() = if (forward) anchorCol else focusCol
    val endRow: Int get() = if (forward) focusRow else anchorRow
    val endCol: Int get() = if (forward) focusCol else anchorCol

    fun withFocus(row: Int, col: Int) = copy(focusRow = row, focusCol = col)

    /** Rows strictly between the first and the last are selected whole. */
    fun contains(row: Int, col: Int): Boolean = when {
        row < startRow || row > endRow -> false
        startRow == endRow -> col in startCol..endCol
        row == startRow -> col >= startCol
        row == endRow -> col <= endCol
        else -> true
    }

    /**
     * The selected text at scroll [offset]. Rows that wrapped join without a newline; trailing
     * blanks are dropped from rows that end a line. Call it under the emulator's lock.
     */
    fun text(emu: TerminalEmulator, offset: Int): String {
        val sb = StringBuilder()
        for (r in maxOf(startRow, 0)..minOf(endRow, emu.rows - 1)) {
            val line = emu.visibleLine(r, offset)
            val from = if (r == startRow) startCol else 0
            val to = if (r == endRow) endCol + 1 else line.cols
            val continues = r != endRow && line.wrapped
            sb.append(line.textRange(from, to, trim = !continues))
            if (r != endRow && !line.wrapped) sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** The word under ([row], [col]): the run of non-blank cells around it, so paths and URLs come whole. */
        fun word(emu: TerminalEmulator, row: Int, col: Int, offset: Int): Selection {
            val line = emu.visibleLine(row, offset)
            fun blank(c: Int) = line.text[c] == 0 || line.text[c] == ' '.code
            if (col !in 0 until line.cols || blank(col)) return Selection(row, col, row, col)
            var start = col
            while (start > 0 && !blank(start - 1)) start--
            var end = col
            while (end < line.cols - 1 && !blank(end + 1)) end++
            return Selection(row, start, row, end)
        }
    }
}
