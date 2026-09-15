package io.termbridge.core.terminal

/**
 * An xterm-compatible screen model driven by [VtParser] (docs/adr/0002).
 *
 * Not thread-safe: the network thread [feed]s it and the UI thread renders it, both while holding
 * the emulator's monitor (`synchronized(emulator)`).
 */
class TerminalEmulator(cols: Int, rows: Int, scrollbackLines: Int = DEFAULT_SCROLLBACK) : VtHandler {

    interface Listener {
        fun onTitleChanged(title: String) {}
        fun onBell() {}

        /** Bytes the terminal must send back to the host (DSR / DA replies). */
        fun onResponse(bytes: ByteArray) {}
    }

    var listener: Listener? = null

    var cols = cols
        private set
    var rows = rows
        private set

    private var main = Array(rows) { TerminalLine(cols) }
    private var alt = Array(rows) { TerminalLine(cols) }
    private var screen = main
    val scrollback = Scrollback(scrollbackLines)

    var isAltScreen = false
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    private var pendingWrap = false // DEC "last column" flag
    private var style = Style.DEFAULT
    private var top = 0
    private var bottom = rows - 1
    private var lastPrinted = ' '.code
    private var lineDrawing = false // G0 = DEC special graphics

    /** DECCKM: arrow keys send `ESC O x`. */
    var appCursorKeys = false
        private set
    var bracketedPaste = false
        private set
    var cursorVisible = true
        private set
    private var autowrap = true

    var title = ""
        private set

    private var saved = SavedCursor()
    private var dirty = BooleanArray(rows) { true }

    /** Lines ever pushed into scrollback; a scrolled-back view uses it to stay anchored. */
    var scrollCounter = 0L
        private set

    /** Bumped on every [feed]; renderers compare it to skip idle frames. */
    var generation = 0L
        private set

    private val parser = VtParser(this)

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        parser.feed(data, offset, length)
        generation++
    }

    fun line(row: Int): TerminalLine = screen[row]

    /** Row [row] of the viewport when scrolled back by [offset] lines (0 = live screen). */
    fun visibleLine(row: Int, offset: Int): TerminalLine {
        val index = row - offset
        return if (index >= 0) screen[index] else scrollback[scrollback.size + index]
    }

    fun isDirty(row: Int): Boolean = dirty[row]
    fun clearDirty() = dirty.fill(false)
    fun markAllDirty() = dirty.fill(true)

    // ---- VtHandler ------------------------------------------------------------------------

    override fun print(codePoint: Int) {
        val cp = if (lineDrawing && codePoint in 0x5F..0x7E) DEC_GRAPHICS[codePoint - 0x5F].code else codePoint
        if (cp >= 0x300 && CharWidth.of(cp) == 0) {
            attachMark(cp)
            return
        }
        if (pendingWrap) {
            if (autowrap) {
                screen[cursorRow].wrapped = true
                cursorCol = 0
                index()
            }
            pendingWrap = false
        }
        val width = CharWidth.of(cp)
        if (width == 2 && cursorCol == cols - 1) { // no room for both halves
            if (cols < 2) return
            if (autowrap) {
                screen[cursorRow].wrapped = true
                cursorCol = 0
                index()
            } else {
                cursorCol = cols - 2
            }
        }
        screen[cursorRow].put(cursorCol, cp, style, wide = width == 2)
        dirty[cursorRow] = true
        lastPrinted = cp
        val next = cursorCol + width
        if (next < cols) {
            cursorCol = next
        } else {
            cursorCol = cols - 1
            pendingWrap = true
        }
    }

    /** A zero-width code point joins the character before the cursor. */
    private fun attachMark(cp: Int) {
        val line = screen[cursorRow]
        var col = if (pendingWrap) cursorCol else cursorCol - 1
        if (col >= 0 && line.text[col] == TerminalLine.WIDE_TAIL) col--
        if (col < 0 || line.text[col] == 0) return
        line.addMark(col, cp)
        dirty[cursorRow] = true
    }

    override fun execute(control: Int) {
        when (control) {
            0x07 -> listener?.onBell()
            0x08 -> moveTo(cursorRow, cursorCol - 1)
            0x09 -> moveTo(cursorRow, minOf(cols - 1, (cursorCol / TAB_WIDTH + 1) * TAB_WIDTH))
            0x0A, 0x0B, 0x0C -> {
                pendingWrap = false
                index()
            }
            0x0D -> moveTo(cursorRow, 0)
        }
    }

    override fun escDispatch(intermediates: Int, final: Int) {
        when (intermediates) {
            0 -> when (final.toChar()) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> index()
                'E' -> {
                    moveTo(cursorRow, 0)
                    index()
                }
                'M' -> reverseIndex()
                'c' -> reset()
            }
            '('.code -> lineDrawing = final == '0'.code
        }
    }

    override fun oscDispatch(command: String) {
        val sep = command.indexOf(';')
        if (sep < 0) return
        when (command.substring(0, sep)) {
            "0", "2" -> {
                title = command.substring(sep + 1)
                listener?.onTitleChanged(title)
            }
        }
    }

    override fun csiDispatch(params: CsiParams, intermediates: Int, final: Int, marker: Int) {
        if (intermediates != 0) return // DECSCUSR and friends: not needed yet
        when (marker) {
            '?'.code -> when (final.toChar()) {
                'h' -> for (i in 0 until params.size) setPrivateMode(params.raw(i), true)
                'l' -> for (i in 0 until params.size) setPrivateMode(params.raw(i), false)
            }
            '>'.code -> if (final == 'c'.code) respond("\u001b[>0;10;1c")
            0 -> csi(params, final.toChar())
        }
    }

    private fun csi(p: CsiParams, final: Char) {
        val n = p.get(0, 1)
        when (final) {
            'A' -> moveTo(maxOf(if (cursorRow >= top) top else 0, cursorRow - n), cursorCol)
            'B' -> moveTo(minOf(if (cursorRow <= bottom) bottom else rows - 1, cursorRow + n), cursorCol)
            'C' -> moveTo(cursorRow, cursorCol + n)
            'D' -> moveTo(cursorRow, cursorCol - n)
            'E' -> moveTo(cursorRow + n, 0)
            'F' -> moveTo(cursorRow - n, 0)
            'G', '`' -> moveTo(cursorRow, n - 1)
            'H', 'f' -> moveTo(p.get(0, 1) - 1, p.get(1, 1) - 1)
            'd' -> moveTo(n - 1, cursorCol)
            'J' -> eraseInDisplay(maxOf(p.raw(0), 0))
            'K' -> eraseInLine(maxOf(p.raw(0), 0))
            '@' -> editLine { insertCells(cursorCol, n, blank()) }
            'P' -> editLine { deleteCells(cursorCol, n, blank()) }
            'X' -> editLine { clear(cursorCol, cursorCol + n, blank()) }
            'L' -> if (cursorRow in top..bottom) withRegion(cursorRow, bottom) { scrollDown(n) }
            'M' -> if (cursorRow in top..bottom) withRegion(cursorRow, bottom) { scrollUp(n) }
            'S' -> scrollUp(n)
            'T' -> scrollDown(n)
            'b' -> repeat(minOf(n, cols * rows)) { print(lastPrinted) }
            'm' -> sgr(p)
            'r' -> setScrollRegion(p.get(0, 1) - 1, p.get(1, rows) - 1)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> when (p.raw(0)) {
                5 -> respond("\u001b[0n")
                6 -> respond("\u001b[${cursorRow + 1};${cursorCol + 1}R")
            }
            'c' -> if (p.raw(0) <= 0) respond("\u001b[?62;22c")
        }
    }

    // ---- Modes ----------------------------------------------------------------------------

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> appCursorKeys = on
            7 -> autowrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, saveCursor = false)
            1049 -> switchScreen(on, saveCursor = true)
            2004 -> bracketedPaste = on
        }
    }

    private fun switchScreen(toAlt: Boolean, saveCursor: Boolean) {
        if (toAlt == isAltScreen) return
        if (toAlt) {
            if (saveCursor) saveCursor()
            screen = alt
            isAltScreen = true
            for (line in alt) line.clear(Style.DEFAULT)
        } else {
            screen = main
            isAltScreen = false
            if (saveCursor) restoreCursor()
        }
        markAllDirty()
    }

    private fun setScrollRegion(newTop: Int, newBottom: Int) {
        val t = newTop.coerceIn(0, rows - 1)
        val b = newBottom.coerceIn(0, rows - 1)
        if (t >= b) return
        top = t
        bottom = b
        moveTo(0, 0)
    }

    // ---- SGR ------------------------------------------------------------------------------

    private fun sgr(p: CsiParams) {
        if (p.size == 0) {
            style = Style.DEFAULT
            return
        }
        var fg = Style.fg(style)
        var bg = Style.bg(style)
        var flags = Style.flags(style)
        var i = 0
        while (i < p.size) {
            val code = maxOf(p.raw(i), 0)
            var next = i + 1
            when (code) {
                0 -> {
                    fg = TermColor.DEFAULT
                    bg = TermColor.DEFAULT
                    flags = 0
                }
                1 -> flags = flags or Style.BOLD
                2 -> flags = flags or Style.DIM
                3 -> flags = flags or Style.ITALIC
                4 -> flags = if (p.isSubparam(i + 1) && p.raw(i + 1) == 0) {
                    flags and Style.UNDERLINE.inv()
                } else {
                    flags or Style.UNDERLINE
                }
                5, 6 -> flags = flags or Style.BLINK
                7 -> flags = flags or Style.INVERSE
                8 -> flags = flags or Style.HIDDEN
                9 -> flags = flags or Style.STRIKE
                21 -> flags = flags or Style.UNDERLINE
                22 -> flags = flags and (Style.BOLD or Style.DIM).inv()
                23 -> flags = flags and Style.ITALIC.inv()
                24 -> flags = flags and Style.UNDERLINE.inv()
                25 -> flags = flags and Style.BLINK.inv()
                27 -> flags = flags and Style.INVERSE.inv()
                28 -> flags = flags and Style.HIDDEN.inv()
                29 -> flags = flags and Style.STRIKE.inv()
                in 30..37 -> fg = TermColor.indexed(code - 30)
                39 -> fg = TermColor.DEFAULT
                in 40..47 -> bg = TermColor.indexed(code - 40)
                49 -> bg = TermColor.DEFAULT
                in 90..97 -> fg = TermColor.indexed(code - 90 + 8)
                in 100..107 -> bg = TermColor.indexed(code - 100 + 8)
                38, 48 -> {
                    val (color, after) = extendedColor(p, i)
                    if (color != null) {
                        if (code == 38) fg = color else bg = color
                    }
                    next = after
                }
            }
            while (next < p.size && p.isSubparam(next)) next++ // unknown sub-parameters
            i = next
        }
        style = Style.pack(fg, bg, flags)
    }

    /** Parses `38;5;n`, `38;2;r;g;b` and the colon forms `38:5:n`, `38:2:[cs]:r:g:b`. */
    private fun extendedColor(p: CsiParams, i: Int): Pair<Int?, Int> {
        if (p.isSubparam(i + 1)) {
            var end = i + 1
            while (end < p.size && p.isSubparam(end)) end++
            val args = (i + 1 until end).map { maxOf(p.raw(it), 0) }
            val color = when {
                args.firstOrNull() == 5 && args.size >= 2 -> TermColor.indexed(args[1])
                args.firstOrNull() == 2 && args.size >= 4 -> args.takeLast(3).let { TermColor.rgb(it[0], it[1], it[2]) }
                else -> null
            }
            return color to end
        }
        return when (p.raw(i + 1)) {
            5 -> TermColor.indexed(maxOf(p.raw(i + 2), 0)) to i + 3
            2 -> TermColor.rgb(maxOf(p.raw(i + 2), 0), maxOf(p.raw(i + 3), 0), maxOf(p.raw(i + 4), 0)) to i + 5
            else -> null to i + 2
        }
    }

    // ---- Cursor, erase, scroll ------------------------------------------------------------

    private fun moveTo(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun index() {
        if (cursorRow == bottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == top) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    private fun blank(): Long = Style.erase(style)

    private inline fun editLine(edit: TerminalLine.() -> Unit) {
        screen[cursorRow].edit()
        dirty[cursorRow] = true
        pendingWrap = false
    }

    private fun eraseInLine(mode: Int) {
        val line = screen[cursorRow]
        when (mode) {
            0 -> line.clear(cursorCol, cols, blank())
            1 -> line.clear(0, cursorCol + 1, blank())
            2 -> line.clear(0, cols, blank())
        }
        dirty[cursorRow] = true
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorRow + 1 until rows) clearRow(r)
            }
            1 -> {
                eraseInLine(1)
                for (r in 0 until cursorRow) clearRow(r)
            }
            2 -> for (r in 0 until rows) clearRow(r)
            3 -> {
                for (r in 0 until rows) clearRow(r)
                scrollback.clear()
            }
        }
    }

    private fun clearRow(row: Int) {
        screen[row].clear(blank())
        dirty[row] = true
    }

    private inline fun withRegion(regionTop: Int, regionBottom: Int, block: () -> Unit) {
        val (t, b) = top to bottom
        top = regionTop
        bottom = regionBottom
        block()
        top = t
        bottom = b
        moveTo(cursorRow, 0)
    }

    private fun scrollUp(n: Int) {
        repeat(minOf(n, bottom - top + 1)) {
            val out = screen[top]
            val fresh = if (!isAltScreen && top == 0) {
                scrollCounter++
                scrollback.push(out)?.also { it.resize(cols) } ?: TerminalLine(cols)
            } else {
                out
            }
            System.arraycopy(screen, top + 1, screen, top, bottom - top)
            fresh.clear(blank())
            screen[bottom] = fresh
        }
        for (r in top..bottom) dirty[r] = true
    }

    private fun scrollDown(n: Int) {
        repeat(minOf(n, bottom - top + 1)) {
            val recycled = screen[bottom]
            System.arraycopy(screen, top, screen, top + 1, bottom - top)
            recycled.clear(blank())
            screen[top] = recycled
        }
        for (r in top..bottom) dirty[r] = true
    }

    // ---- Save / restore, reset, resize ----------------------------------------------------

    private class SavedCursor(
        val row: Int = 0,
        val col: Int = 0,
        val style: Long = Style.DEFAULT,
        val pendingWrap: Boolean = false,
        val lineDrawing: Boolean = false,
    )

    private fun saveCursor() {
        saved = SavedCursor(cursorRow, cursorCol, style, pendingWrap, lineDrawing)
    }

    private fun restoreCursor() {
        moveTo(saved.row, saved.col)
        style = saved.style
        pendingWrap = saved.pendingWrap
        lineDrawing = saved.lineDrawing
    }

    private fun reset() {
        switchScreen(false, saveCursor = false)
        for (line in main) line.clear(Style.DEFAULT)
        style = Style.DEFAULT
        top = 0
        bottom = rows - 1
        appCursorKeys = false
        bracketedPaste = false
        cursorVisible = true
        autowrap = true
        lineDrawing = false
        saved = SavedCursor()
        moveTo(0, 0)
        markAllDirty()
    }

    /**
     * Resizes without reflow (phase 2). When the main screen loses rows, lines above the cursor
     * move into scrollback so the cursor line stays visible.
     */
    fun resize(newCols: Int, newRows: Int) {
        require(newCols > 0 && newRows > 0) { "size ${newCols}x$newRows" }
        if (newCols == cols && newRows == rows) return
        val mainShift = if (isAltScreen) maxOf(0, lastUsedRow(main) + 1 - newRows) else maxOf(0, cursorRow + 1 - newRows)
        for (r in 0 until mainShift) {
            scrollCounter++
            scrollback.push(main[r])
        }
        main = Array(newRows) { r -> (main.getOrNull(r + mainShift) ?: TerminalLine(newCols)).also { it.resize(newCols) } }
        alt = Array(newRows) { r -> (alt.getOrNull(r) ?: TerminalLine(newCols)).also { it.resize(newCols) } }
        screen = if (isAltScreen) alt else main
        if (!isAltScreen) cursorRow -= mainShift
        cols = newCols
        rows = newRows
        top = 0
        bottom = newRows - 1
        dirty = BooleanArray(newRows) { true }
        moveTo(cursorRow, cursorCol)
    }

    private fun lastUsedRow(buffer: Array<TerminalLine>): Int =
        buffer.indexOfLast { line -> line.text.any { it != 0 } }

    private fun respond(s: String) {
        listener?.onResponse(s.encodeToByteArray())
    }

    companion object {
        const val DEFAULT_SCROLLBACK = 10_000
        private const val TAB_WIDTH = 8

        /** DEC special graphics for 0x5F..0x7E (`ESC ( 0`), as drawn by mc, tmux, dialog. */
        private const val DEC_GRAPHICS = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}
