package io.termbridge.feature.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.res.ResourcesCompat
import io.termbridge.core.terminal.Key
import io.termbridge.core.terminal.KeyEncoder
import io.termbridge.core.terminal.Mods
import io.termbridge.core.terminal.MouseEncoder
import io.termbridge.core.terminal.MouseMode
import io.termbridge.core.terminal.Style
import io.termbridge.core.terminal.TermColor
import io.termbridge.core.terminal.TerminalEmulator
import io.termbridge.core.terminal.TerminalLine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Renders a [TerminalEmulator] and turns touch and keyboard input into bytes.
 *
 * Rendering (docs/adr/0002): each row is recorded into its own [Picture] only when the emulator
 * marks it dirty; clean rows are replayed. Redraws are paced by [postOnAnimation] (Choreographer),
 * so a burst of output costs at most one frame per vsync however many frames arrive.
 *
 * Input (architecture §7.4): the view is its own IME target with `TYPE_NULL`, so the keyboard
 * sends raw keys with no autocorrect, prediction or extract UI.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, private val palette: TerminalPalette = TerminalPalette.Night) : View(context) {

    /** Called from any thread; the view locks it while drawing. */
    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            invalidateRows()
            requestRender()
        }

    /** Grid size in cells, reported whenever layout or font size changes it. */
    var onViewportChanged: ((cols: Int, rows: Int) -> Unit)? = null
    var onInput: ((ByteArray) -> Unit)? = null

    /** Sticky modifiers from the extra-keys row, and the hook to consume them. */
    var stickyModifiers: () -> Int = { Mods.NONE }
    var onStickyConsumed: () -> Unit = {}
    var onFontSizeChanged: ((Float) -> Unit)? = null

    var fontSizeSp: Float = DEFAULT_FONT_SP
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (clamped == field) return
            field = clamped
            updateMetrics()
            onFontSizeChanged?.invoke(clamped)
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, io.termbridge.core.ui.R.font.jetbrains_mono_regular)
    }
    private val fillPaint = Paint()
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f
    private val padding = dp(6f)

    private var cols = 0
    private var rows = 0
    private var rowPictures = arrayOfNulls<Picture>(0)
    private var picturesValid = false
    private var charBuf = CharArray(256)

    /** Lines scrolled back into history; 0 shows the live screen. */
    private var scrollOffset = 0
    private var drawnOffset = 0
    private var lastScrollCounter = 0L
    private var scrollAccumulator = 0f
    private val framePending = AtomicBoolean(false)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
    }

    /** Thread-safe: schedules one redraw on the next frame. */
    fun requestRender() {
        if (framePending.compareAndSet(false, true)) {
            postOnAnimation {
                framePending.set(false)
                invalidate()
            }
        }
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    /** Sends text as a paste, bracketed when the application asked for it (DECSET 2004). */
    fun paste(text: String) {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val bracketed = emulator?.bracketedPaste == true
        send((if (bracketed) "\u001b[200~$normalized\u001b[201~" else normalized).encodeToByteArray())
    }

    /** Sends a key from the extra-keys row, applying sticky modifiers. */
    fun sendKey(key: Key) = sendWithSticky { mods -> KeyEncoder.encode(key, mods, emulator?.appCursorKeys == true) }

    /** Sends a typed character, applying sticky modifiers. */
    fun sendChar(codePoint: Int) = sendWithSticky { mods -> KeyEncoder.encodeChar(codePoint, mods) }

    private inline fun sendWithSticky(encode: (Int) -> ByteArray) {
        val mods = stickyModifiers()
        send(encode(mods))
        if (mods != Mods.NONE) onStickyConsumed()
    }

    private fun send(bytes: ByteArray) {
        if (scrollOffset != 0) {
            scrollOffset = 0
            requestRender()
        }
        onInput?.invoke(bytes)
    }

    // ---- Layout & metrics -------------------------------------------------------------

    private fun updateMetrics() {
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        cellWidth = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        cellHeight = ceil(fm.descent - fm.ascent + fm.leading)
        baseline = -fm.ascent
        invalidateRows()
        recomputeGrid()
        requestRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = recomputeGrid()

    private fun recomputeGrid() {
        if (width == 0 || height == 0) return
        val newCols = maxOf(1, floor((width - 2 * padding) / cellWidth).toInt())
        val newRows = maxOf(1, floor((height - 2 * padding) / cellHeight).toInt())
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        rowPictures = arrayOfNulls(rows)
        invalidateRows()
        onViewportChanged?.invoke(cols, rows)
    }

    private fun invalidateRows() {
        picturesValid = false
    }

    // ---- Drawing ----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val emu = emulator ?: return
        synchronized(emu) {
            val counter = emu.scrollCounter
            if (scrollOffset > 0) {
                // Keep the history the user is reading still while new output arrives.
                scrollOffset = minOf(scrollOffset + (counter - lastScrollCounter).toInt(), emu.scrollback.size)
            }
            lastScrollCounter = counter
            val visibleRows = minOf(rows, emu.rows)
            val redrawAll = !picturesValid || scrollOffset != drawnOffset || scrollOffset > 0
            for (r in 0 until visibleRows) {
                var picture = rowPictures[r]
                if (picture == null || redrawAll || emu.isDirty(r)) {
                    picture = picture ?: Picture().also { rowPictures[r] = it }
                    recordRow(picture, emu.visibleLine(r, scrollOffset))
                }
                canvas.save()
                canvas.translate(padding, padding + r * cellHeight)
                canvas.drawPicture(picture)
                canvas.restore()
            }
            emu.clearDirty()
            picturesValid = true
            drawnOffset = scrollOffset
            if (scrollOffset == 0 && emu.cursorVisible && emu.cursorRow < visibleRows) drawCursor(canvas, emu)
        }
    }

    private fun recordRow(picture: Picture, line: TerminalLine) {
        val canvas = picture.beginRecording(ceil(cols * cellWidth).toInt(), cellHeight.toInt())
        val n = minOf(line.cols, cols)
        var start = 0
        while (start < n) {
            val style = line.styles[start]
            var end = start + 1
            while (end < n && line.styles[end] == style) end++
            drawRun(canvas, line, start, end, style)
            start = end
        }
        picture.endRecording()
    }

    private fun drawRun(canvas: Canvas, line: TerminalLine, start: Int, end: Int, style: Long) {
        val flags = Style.flags(style)
        var fg = resolve(Style.fg(style), foreground = true, bold = flags and Style.BOLD != 0)
        var bg = resolve(Style.bg(style), foreground = false, bold = false)
        if (flags and Style.INVERSE != 0) fg = bg.also { bg = fg }
        if (flags and Style.HIDDEN != 0) fg = bg
        if (bg != palette.background) {
            fillPaint.color = bg
            canvas.drawRect(start * cellWidth, 0f, end * cellWidth, cellHeight, fillPaint)
        }
        textPaint.color = if (flags and Style.DIM != 0) (fg and 0x00FFFFFF) or (0x99 shl 24) else fg
        textPaint.isFakeBoldText = flags and Style.BOLD != 0
        textPaint.textSkewX = if (flags and Style.ITALIC != 0) -0.2f else 0f
        textPaint.isUnderlineText = flags and Style.UNDERLINE != 0
        textPaint.isStrikeThruText = flags and Style.STRIKE != 0

        // ASCII runs are drawn in one call (the font is monospaced); anything else is placed
        // cell by cell so fallback-font glyphs cannot push the rest of the row off the grid.
        val text = line.text
        var ascii = !line.hasMarks
        var blank = true
        for (i in start until end) {
            val cp = text[i]
            if (cp > 0x7E || cp < 0) ascii = false // non-ASCII, or the right half of a wide character
            if (cp > 0x20) blank = false
        }
        if (blank && !textPaint.isUnderlineText && !textPaint.isStrikeThruText) return
        if (ascii) {
            val len = end - start
            if (charBuf.size < len) charBuf = CharArray(len)
            for (i in 0 until len) charBuf[i] = if (text[start + i] == 0) ' ' else text[start + i].toChar()
            canvas.drawText(charBuf, 0, len, start * cellWidth, baseline, textPaint)
        } else {
            for (i in start until end) {
                val cp = text[i]
                if (cp == TerminalLine.WIDE_TAIL) continue // drawn with its left half
                var len = Character.toChars(if (cp == 0) ' '.code else cp, charBuf, 0)
                line.marks(i)?.let { m ->
                    if (charBuf.size < len + m.length) charBuf = charBuf.copyOf(len + m.length + 16)
                    m.toCharArray(charBuf, len)
                    len += m.length
                }
                val cells = if (i + 1 < line.cols && text[i + 1] == TerminalLine.WIDE_TAIL) 2 else 1
                drawGlyph(canvas, len, i * cellWidth, cells)
            }
        }
    }

    /** Draws charBuf[0, len) into [cells] cells from [x]: centered, squeezed when the glyph is wider. */
    private fun drawGlyph(canvas: Canvas, len: Int, x: Float, cells: Int) {
        val slot = cells * cellWidth
        val width = textPaint.measureText(charBuf, 0, len)
        if (width <= slot + 0.5f) {
            canvas.drawText(charBuf, 0, len, x + (slot - width) / 2, baseline, textPaint)
        } else {
            val scale = textPaint.textScaleX
            textPaint.textScaleX = scale * slot / width
            canvas.drawText(charBuf, 0, len, x, baseline, textPaint)
            textPaint.textScaleX = scale
        }
    }

    private fun drawCursor(canvas: Canvas, emu: TerminalEmulator) {
        val x = padding + emu.cursorCol * cellWidth
        val y = padding + emu.cursorRow * cellHeight
        fillPaint.color = palette.cursor
        val line = emu.line(emu.cursorRow)
        val wide = emu.cursorCol + 1 < line.cols && line.text[emu.cursorCol + 1] == TerminalLine.WIDE_TAIL
        val w = if (wide) 2 * cellWidth else cellWidth
        if (hasFocus()) {
            canvas.drawRect(x, y, x + w, y + cellHeight, fillPaint)
            val cp = line.text[emu.cursorCol]
            if (cp > 0x20) {
                textPaint.color = palette.background
                textPaint.isFakeBoldText = false
                textPaint.textSkewX = 0f
                textPaint.isUnderlineText = false
                textPaint.isStrikeThruText = false
                val len = Character.toChars(cp, charBuf, 0)
                canvas.drawText(charBuf, 0, len, x, y + baseline, textPaint)
            }
        } else {
            canvas.drawRect(x, y + cellHeight - dp(2f), x + w, y + cellHeight, fillPaint)
        }
    }

    private fun resolve(color: Int, foreground: Boolean, bold: Boolean): Int = when {
        color == TermColor.DEFAULT -> if (foreground) palette.foreground else palette.background
        TermColor.isIndexed(color) -> {
            val index = TermColor.index(color)
            palette.indexed(if (foreground && bold && index < 8) index + 8 else index)
        }
        else -> TermColor.rgbValue(color) or (0xFF shl 24)
    }

    // ---- Keyboard ---------------------------------------------------------------------

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {
        private var composing: CharSequence = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            sendText(text)
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = text
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) sendText(composing)
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { sendKey(Key.BACKSPACE) }
            repeat(afterLength) { sendKey(Key.DELETE) }
            return true
        }
    }

    private fun sendText(text: CharSequence) {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (cp == '\n'.code) sendKey(Key.ENTER) else sendChar(cp)
            i += Character.charCount(cp)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val hardware = (if (event.isCtrlPressed) Mods.CTRL else 0) or
            (if (event.isAltPressed) Mods.ALT else 0) or
            (if (event.isShiftPressed) Mods.SHIFT else 0)
        val key = keyFor(keyCode)
        if (key != null) {
            val sticky = stickyModifiers()
            send(KeyEncoder.encode(key, hardware or sticky, emulator?.appCursorKeys == true))
            if (sticky != Mods.NONE) onStickyConsumed()
            return true
        }
        val cp = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (cp != 0 && cp and KeyCharacterMap.COMBINING_ACCENT == 0) {
            val sticky = stickyModifiers()
            send(KeyEncoder.encodeChar(cp, (hardware or sticky) and Mods.SHIFT.inv()))
            if (sticky != Mods.NONE) onStickyConsumed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun keyFor(keyCode: Int): Key? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
        KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
        KeyEvent.KEYCODE_TAB -> Key.TAB
        KeyEvent.KEYCODE_ESCAPE -> Key.ESCAPE
        KeyEvent.KEYCODE_DPAD_UP -> Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> Key.HOME
        KeyEvent.KEYCODE_MOVE_END -> Key.END
        KeyEvent.KEYCODE_PAGE_UP -> Key.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Key.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> Key.INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Key.entries[Key.F1.ordinal + keyCode - KeyEvent.KEYCODE_F1]
        else -> null
    }

    // ---- Touch: tap = keyboard (and a click when the app wants the mouse), drag = scroll
    //      history or the app's wheel, pinch = font size ------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val emu = emulator
            if (emu != null && emu.mouseMode != MouseMode.NONE && scrollOffset == 0) {
                val (col, row) = cellAt(e.x, e.y)
                send(MouseEncoder.press(MouseEncoder.LEFT, col, row, emu.mouseSgr))
                send(MouseEncoder.release(MouseEncoder.LEFT, col, row, emu.mouseSgr))
            }
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling) return true
            scrollAccumulator += distanceY
            val lines = (scrollAccumulator / cellHeight).toInt()
            if (lines != 0) {
                scrollAccumulator -= lines * cellHeight
                scrollBy(lines, e2.x, e2.y)
            }
            return true
        }
    })

    private var scaling = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSizeSp = (fontSizeSp * detector.scaleFactor * 2).roundToInt() / 2f // half-sp steps
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
        }
    })

    /**
     * Positive [lines] moves toward newer output. An app that asked for the mouse gets wheel
     * events at the finger ([x], [y]); other full-screen apps get arrow keys.
     */
    private fun scrollBy(lines: Int, x: Float, y: Float) {
        val emu = emulator ?: return
        if (emu.mouseMode != MouseMode.NONE && scrollOffset == 0) {
            val (col, row) = cellAt(x, y)
            val wheel = if (lines > 0) MouseEncoder.WHEEL_DOWN else MouseEncoder.WHEEL_UP
            repeat(kotlin.math.abs(lines)) { send(MouseEncoder.press(wheel, col, row, emu.mouseSgr)) }
            return
        }
        if (emu.isAltScreen) {
            repeat(kotlin.math.abs(lines)) { send(KeyEncoder.encode(if (lines > 0) Key.DOWN else Key.UP, appCursor = emu.appCursorKeys)) }
            return
        }
        val max = synchronized(emu) { emu.scrollback.size }
        val next = (scrollOffset - lines).coerceIn(0, max)
        if (next != scrollOffset) {
            scrollOffset = next
            requestRender()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    /** The cell under a touch point, clamped to the grid. */
    private fun cellAt(x: Float, y: Float): Pair<Int, Int> =
        ((x - padding) / cellWidth).toInt().coerceIn(0, maxOf(cols - 1, 0)) to
            ((y - padding) / cellHeight).toInt().coerceIn(0, maxOf(rows - 1, 0))

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        const val DEFAULT_FONT_SP = 13f
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 28f
    }
}
