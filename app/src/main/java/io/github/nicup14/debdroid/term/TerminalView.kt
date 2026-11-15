package io.github.nicup14.debdroid.term

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Draws a TerminalSession and turns touch, keys and the soft keyboard into input for it
//
// Rendering is one drawText per run of cells sharing a style, not one per
// cell, which is what keeps a full screen of htop cheap on 2015 hardware. A
// glyph whose advance is not one cell (a wide character, a combining
// sequence, or a fallback font's box drawing) is drawn on its own, fitted to
// its cells, so it cannot push the rest of the line out of alignment.
//
// Touch: a vertical drag scrolls the scrollback (with fling), or on the
// alternate screen sends arrow keys, so less, man and vim scroll by touch. A
// tap shows the keyboard, a long press copies the visible screen.
class TerminalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var session: TerminalSession? = null
        private set

    // The latching Ctrl and Alt the extra key row binds to
    val modifiers = Modifiers()

    // Volume down arms Ctrl for the next key, and no longer changes the volume
    var volumeDownIsCtrl: Boolean = false

    // A short press of Back sends ESC; a long press is left to the activity
    var backSendsEsc: Boolean = false

    // Called on a long press of Back when backSendsEsc is on; when unset the long press goes to the activity
    var onBackLongPress: (() -> Unit)? = null

    var textSizeSp: Float = DEFAULT_TEXT_SIZE
        set(value) {
            val clamped = value.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
            if (clamped == field) return
            field = clamped
            updateMetrics()
            updateSize()
            invalidate()
        }

    var scheme: TerminalScheme = TerminalScheme.DEBDROID
        set(value) {
            field = value
            invalidate()
        }

    // The terminal's size in cells, as last computed from the view's size
    var columns: Int = 0
        private set
    var rows: Int = 0
        private set

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fillPaint = Paint()
    private var cellWidth = 1f
    private var cellHeight = 1
    private var baseline = 0
    private var defaultForeground = 0
    private var defaultBackground = 0
    private var chars = CharArray(256)

    // NOTE: Glyph advances, measured once per text size. Direct-indexed below
    //       0x3000 (Latin, Greek, Cyrillic, box drawing, blocks), which covers
    //       nearly every cell, so the lookup allocates nothing per frame.
    private val nearWidths = FloatArray(0x3000)
    private val farWidths = HashMap<Int, Float>()

    // Lines scrolled back from the bottom; 0 shows the live screen
    private var scrollOffset = 0
    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)

    // Text the keyboard is still composing; only committed text is sent
    private val composing = StringBuilder()
    private var deadAccent = 0
    private var backLongPressed = false
    private var listening = false

    private val sessionListener = object : TerminalSession.Listener {
        override fun onScreenUpdate(session: TerminalSession) {
            if (scrollOffset != 0) {
                scrollOffset = 0
                scroller.forceFinished(true)
            }
            invalidate()
        }

        override fun onExit(session: TerminalSession, code: Int) {
            invalidate()
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollRemainder = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            copyToClipboard()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollByPixels(distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            fling(velocityY)
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Terminal"
        updateMetrics()
    }

    // Shows a session, or nothing for null; resizes the session to fit the view
    fun attach(session: TerminalSession?) {
        if (session === this.session) return
        if (listening) this.session?.removeListener(sessionListener)
        listening = false
        this.session = session
        scrollOffset = 0
        composing.setLength(0)
        if (session != null && isAttachedToWindow) {
            session.addListener(sessionListener)
            listening = true
        }
        updateSize()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val s = session
        if (s != null && !listening) {
            s.addListener(sessionListener)
            listening = true
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        if (listening) session?.removeListener(sessionListener)
        listening = false
        super.onDetachedFromWindow()
    }

    // ---- Metrics and size ------------------------------------------------

    private fun updateMetrics() {
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics)
        textPaint.textScaleX = 1f
        cellWidth = textPaint.measureText("M").coerceAtLeast(1f)
        val metrics = textPaint.fontMetricsInt
        cellHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1)
        baseline = -metrics.ascent
        nearWidths.fill(-1f)
        farWidths.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize()
    }

    private fun updateSize() {
        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = height - paddingTop - paddingBottom
        if (usableWidth <= 0 || usableHeight <= 0) return
        columns = max(1, (usableWidth / cellWidth).toInt())
        rows = max(1, usableHeight / cellHeight)
        session?.resize(columns, rows)
        invalidate()
    }

    // ---- Drawing ---------------------------------------------------------

    // NOTE: The one allocation here grows the run buffer, and happens only
    //       when the terminal gets wider than it has been before.
    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val emulator = session?.emulator
        if (emulator == null) {
            canvas.drawColor(scheme.background)
            return
        }
        synchronized(emulator) {
            val reverse = emulator.reverseVideo
            defaultForeground = if (reverse) scheme.background else scheme.foreground
            defaultBackground = if (reverse) scheme.foreground else scheme.background
            canvas.drawColor(defaultBackground)
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            val offset = min(scrollOffset, emulator.scrollbackRows)
            if (chars.size < emulator.columns * 2) chars = CharArray(emulator.columns * 2)
            for (i in 0 until emulator.rows) drawRow(canvas, emulator.row(i - offset), i, emulator.columns)
            if (offset == 0) {
                if (emulator.cursorVisible) drawCursor(canvas, emulator)
                if (composing.isNotEmpty()) drawComposing(canvas, emulator)
            } else {
                drawScrollbar(canvas, emulator, offset)
            }
            canvas.restore()
        }
    }

    private fun drawRow(canvas: Canvas, row: TerminalRow, screenRow: Int, columns: Int) {
        val top = (screenRow * cellHeight).toFloat()
        val count = min(row.columns, columns)
        val styles = row.styles
        var x = 0
        while (x < count) {
            val style = styles[x]
            var end = x + 1
            while (end < count && styles[end] == style) end++
            drawRun(canvas, row, x, end, style, top)
            x = end
        }
    }

    // Draws the cells [start, end) of one row, which share a style
    private fun drawRun(canvas: Canvas, row: TerminalRow, start: Int, end: Int, style: Long, top: Float) {
        val effects = TextStyle.effects(style)
        val backgroundIndex = TextStyle.background(style)
        var fg = resolve(TextStyle.foreground(style), backgroundIndex != TextStyle.COLOR_DEFAULT_BG)
        var bg = resolve(backgroundIndex, true)
        if (effects and TextStyle.INVERSE != 0) {
            val swap = fg
            fg = bg
            bg = swap
        }
        if (bg != defaultBackground) {
            fillPaint.color = bg
            canvas.drawRect(start * cellWidth, top, end * cellWidth, top + cellHeight, fillPaint)
        }
        if (effects and TextStyle.HIDDEN != 0) return

        val text = row.text
        val decorated = effects and (TextStyle.UNDERLINE or TextStyle.STRIKETHROUGH) != 0
        if (!decorated) {
            // NOTE: A run of nothing but blanks has nothing to draw past its
            //       background, and most of a terminal is blanks.
            var blank = true
            for (i in start until end) {
                if (text[i] != TerminalRow.BLANK || row.combiningAt(i) != null) {
                    blank = false
                    break
                }
            }
            if (blank) return
        }

        // NOTE: Only on a coloured background. On the scheme's own, its
        //       palette is already chosen for contrast, and text a program
        //       hides on purpose by matching colours uses HIDDEN, not this.
        if (bg != defaultBackground) fg = Contrast.readable(fg, bg)
        if (effects and TextStyle.DIM != 0) fg = blend(fg, bg)
        applyEffects(fg, effects)

        val baselineY = top + baseline
        var runStart = -1
        var length = 0
        for (col in start until end) {
            val cp = text[col]
            if (cp == TerminalRow.WIDE_TAIL) continue
            val marks = row.combiningAt(col)
            val wide = row.isWideHead(col)
            if (!wide && marks == null && fitsCell(cp)) {
                if (runStart < 0) runStart = col
                length += Character.toChars(cp, chars, length)
                continue
            }
            if (runStart >= 0) {
                canvas.drawText(chars, 0, length, runStart * cellWidth, baselineY, textPaint)
                runStart = -1
                length = 0
            }
            drawGlyph(canvas, cp, marks, col, if (wide) 2 else 1, baselineY)
        }
        if (runStart >= 0) canvas.drawText(chars, 0, length, runStart * cellWidth, baselineY, textPaint)
    }

    private fun applyEffects(color: Int, effects: Int) {
        textPaint.color = color
        textPaint.isFakeBoldText = effects and TextStyle.BOLD != 0
        textPaint.textSkewX = if (effects and TextStyle.ITALIC != 0) -0.25f else 0f
        textPaint.isUnderlineText = effects and TextStyle.UNDERLINE != 0
        textPaint.isStrikeThruText = effects and TextStyle.STRIKETHROUGH != 0
    }

    // Draws one character fitted to its cells: centred if narrower, squeezed if wider
    private fun drawGlyph(canvas: Canvas, cp: Int, marks: String?, column: Int, cells: Int, baselineY: Float) {
        val glyph = if (marks == null) String(Character.toChars(cp)) else String(Character.toChars(cp)) + marks
        val target = cells * cellWidth
        val measured = textPaint.measureText(glyph)
        val x = column * cellWidth
        if (measured > target * 1.05f) {
            textPaint.textScaleX = target / measured
            canvas.drawText(glyph, x, baselineY, textPaint)
            textPaint.textScaleX = 1f
        } else {
            canvas.drawText(glyph, x + (target - measured) / 2f, baselineY, textPaint)
        }
    }

    // True when a code point's glyph is exactly one cell wide in the monospace font
    private fun fitsCell(cp: Int): Boolean {
        if (cp < 0x7F) return true
        val advance = if (cp < nearWidths.size) {
            var cached = nearWidths[cp]
            if (cached < 0f) {
                cached = textPaint.measureText(String(Character.toChars(cp)))
                nearWidths[cp] = cached
            }
            cached
        } else {
            farWidths[cp] ?: textPaint.measureText(String(Character.toChars(cp))).also { farWidths[cp] = it }
        }
        return abs(advance - cellWidth) < cellWidth * 0.05f
    }

    private fun drawCursor(canvas: Canvas, emulator: TerminalEmulator) {
        val x = emulator.cursorX
        val y = emulator.cursorY
        val row = emulator.row(y)
        if (x >= row.columns) return
        val cells = if (row.isWideHead(x)) 2 else 1
        val left = x * cellWidth
        val top = (y * cellHeight).toFloat()
        fillPaint.color = scheme.cursor
        canvas.drawRect(left, top, left + cells * cellWidth, top + cellHeight, fillPaint)
        val cp = row.text[x]
        if (cp != TerminalRow.BLANK && cp != TerminalRow.WIDE_TAIL) {
            applyEffects(defaultBackground, TextStyle.effects(row.styles[x]) and TextStyle.BOLD)
            drawGlyph(canvas, cp, row.combiningAt(x), x, cells, top + baseline)
        }
    }

    // The text the keyboard is composing, underlined at the cursor
    private fun drawComposing(canvas: Canvas, emulator: TerminalEmulator) {
        val text = composing.toString()
        applyEffects(defaultForeground, TextStyle.UNDERLINE)
        val left = emulator.cursorX * cellWidth
        val top = (emulator.cursorY * cellHeight).toFloat()
        fillPaint.color = defaultBackground
        canvas.drawRect(left, top, left + textPaint.measureText(text), top + cellHeight, fillPaint)
        canvas.drawText(text, left, top + baseline, textPaint)
    }

    // A thin thumb on the right edge while scrolled back, showing where in the scrollback the view is
    private fun drawScrollbar(canvas: Canvas, emulator: TerminalEmulator, offset: Int) {
        val total = emulator.scrollbackRows + emulator.rows
        val height = (emulator.rows * cellHeight).toFloat()
        val density = resources.displayMetrics.density
        val thumb = max(height * emulator.rows / total, 16 * density)
        val position = (emulator.scrollbackRows - offset).toFloat() / max(1, emulator.scrollbackRows) * (height - thumb)
        val right = emulator.columns * cellWidth
        fillPaint.color = scheme.dim
        canvas.drawRect(right - 3 * density, position, right, position + thumb, fillPaint)
    }

    private fun resolve(color: Int, onColour: Boolean): Int = when {
        color == TextStyle.COLOR_DEFAULT_FG -> defaultForeground
        color == TextStyle.COLOR_DEFAULT_BG -> defaultBackground
        TextStyle.isRgb(color) -> (0xFF shl 24) or (color and 0xFFFFFF)
        onColour -> scheme.colorOnColour(color)
        else -> scheme.color(color)
    }

    // Halfway between two colours, for faint text
    private fun blend(a: Int, b: Int): Int {
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
        val bl = ((a and 0xFF) + (b and 0xFF)) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    // ---- Scrolling -------------------------------------------------------

    fun scrollToBottom() {
        scroller.forceFinished(true)
        setScrollOffset(0)
    }

    private fun setScrollOffset(lines: Int) {
        val emulator = session?.emulator ?: return
        val limit = synchronized(emulator) { emulator.scrollbackRows }
        val clamped = lines.coerceIn(0, limit)
        if (clamped != scrollOffset) {
            scrollOffset = clamped
            invalidate()
        }
    }

    private fun scrollByPixels(distance: Float) {
        val s = session ?: return
        scrollRemainder += distance
        val lines = (scrollRemainder / cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellHeight
        val (alternate, applicationCursor) = synchronized(s.emulator) {
            Pair(s.emulator.isAlternateScreen, s.emulator.applicationCursorKeys)
        }
        if (alternate) {
            // NOTE: The alternate screen has no scrollback. A drag there sends
            //       arrow keys instead, which is how less, man and vim scroll.
            val bytes = KeyEncoder.key(if (lines > 0) TermKey.DOWN else TermKey.UP, 0, applicationCursor)
            repeat(min(abs(lines), MAX_ARROWS_PER_EVENT)) { s.write(bytes) }
        } else {
            setScrollOffset(scrollOffset - lines)
        }
    }

    private fun fling(velocity: Float) {
        val emulator = session?.emulator ?: return
        val (alternate, limit) = synchronized(emulator) { Pair(emulator.isAlternateScreen, emulator.scrollbackRows) }
        if (alternate || limit == 0) return
        scroller.fling(0, scrollOffset * cellHeight, 0, velocity.toInt(), 0, 0, 0, limit * cellHeight)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setScrollOffset(scroller.currY / cellHeight)
            if (!scroller.isFinished) postInvalidateOnAnimation()
        }
    }

    // NOTE: performClick is called, from the gesture detector's single tap,
    //       which lint cannot see through.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        showKeyboard()
        return true
    }

    // A mouse wheel scrolls three lines a notch
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            scrollRemainder = 0f
            scrollByPixels(-event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 3 * cellHeight)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- Copy ------------------------------------------------------------

    // The screen as shown, scroll position included, as plain text
    fun copyScreen(): String {
        val emulator = session?.emulator ?: return ""
        val builder = StringBuilder()
        synchronized(emulator) {
            val offset = min(scrollOffset, emulator.scrollbackRows)
            for (i in 0 until emulator.rows) {
                if (i > 0) builder.append('\n')
                builder.append(emulator.row(i - offset).toText(true))
            }
        }
        return builder.toString().trimEnd('\n')
    }

    private fun copyToClipboard() {
        val text = copyScreen()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(context, "Copied the screen", Toast.LENGTH_SHORT).show()
    }

    // ---- Input -----------------------------------------------------------

    // Sends a key, applying and consuming the latched modifiers
    fun sendKey(key: TermKey) {
        val s = session ?: return
        val mods = modifiers.consume()
        val applicationCursor = synchronized(s.emulator) { s.emulator.applicationCursorKeys }
        s.write(KeyEncoder.key(key, mods, applicationCursor))
        scrollToBottom()
    }

    // Sends typed text; the latched modifiers apply to its first character only
    fun sendText(text: String) {
        val s = session ?: return
        if (text.isEmpty()) return
        var mods = modifiers.consume()
        val out = ByteArrayOutputStream(text.length + 8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val bytes = if (cp == '\n'.code || cp == '\r'.code) {
                KeyEncoder.key(TermKey.ENTER, mods, false)
            } else {
                KeyEncoder.char(cp, mods)
            }
            out.write(bytes, 0, bytes.size)
            mods = 0
        }
        s.write(out.toByteArray())
        scrollToBottom()
    }

    fun paste(text: String) {
        val s = session ?: return
        if (text.isEmpty()) return
        val bracketed = synchronized(s.emulator) { s.emulator.bracketedPaste }
        s.write(KeyEncoder.paste(text, bracketed))
        scrollToBottom()
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    // NOTE: Autocorrect, autocapitalisation and suggestions are always off.
    //       That is a decision, not a default: a shell command is not prose.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    // The soft keyboard's connection: committed text goes to the session, composing text waits
    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // NOTE: A commit replaces whatever was being composed.
            composing.setLength(0)
            if (!text.isNullOrEmpty()) sendText(text.toString())
            invalidate()
            return true
        }

        // NOTE: Gboard on old devices composes even in a password field. The
        //       composition is shown at the cursor but sent only once committed,
        //       or the program would see every intermediate state of the word.
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing.setLength(0)
            if (text != null) composing.append(text)
            invalidate()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) {
                val text = composing.toString()
                composing.setLength(0)
                sendText(text)
                invalidate()
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleteBefore(beforeLength)
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            deleteBefore(beforeLength)
            return true
        }

        // NOTE: ACTION_MULTIPLE is deprecated, but old keyboards still send it
        @Suppress("DEPRECATION")
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                KeyEvent.ACTION_MULTIPLE -> onKeyMultiple(event.keyCode, event.repeatCount, event)
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendKey(TermKey.ENTER)
            return true
        }
    }

    // Backspace from the keyboard: trims the composition if there is one, otherwise DEL per character
    private fun deleteBefore(count: Int) {
        if (count <= 0) return
        if (composing.isNotEmpty()) {
            composing.setLength(max(0, composing.length - count))
            invalidate()
            return
        }
        val s = session ?: return
        s.write(ByteArray(count) { 0x7f })
        scrollToBottom()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volumeDownIsCtrl) {
            if (event.repeatCount == 0) modifiers.ctrl = true
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!backSendsEsc) return super.onKeyDown(keyCode, event)
            if (event.repeatCount == 0) {
                backLongPressed = false
                event.startTracking()
                return true
            }
            // NOTE: Held: not ours. Returning false lets the activity see it.
            backLongPressed = true
            return false
        }
        return handleKey(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && backSendsEsc) {
            backLongPressed = true
            val callback = onBackLongPress ?: return false
            callback()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volumeDownIsCtrl) return true
        if (keyCode == KeyEvent.KEYCODE_BACK && backSendsEsc) {
            val shortPress = !backLongPressed && event.isTracking && !event.isCanceled
            backLongPressed = false
            if (shortPress) {
                sendKey(TermKey.ESCAPE)
                return true
            }
            return false
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val characters = event.characters
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && characters != null) {
            sendText(characters)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    // A hardware (or IME-sent) key: a TermKey, or a character with its modifiers
    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return false
        var mods = 0
        if (event.isCtrlPressed) mods = mods or KeyEncoder.CTRL
        if (event.isAltPressed) mods = mods or KeyEncoder.ALT
        if (event.isShiftPressed) mods = mods or KeyEncoder.SHIFT

        val key = termKeyFor(keyCode)
        if (key != null) {
            mods = mods or modifiers.consume()
            val applicationCursor = synchronized(s.emulator) { s.emulator.applicationCursorKeys }
            s.write(KeyEncoder.key(key, mods, applicationCursor))
            scrollToBottom()
            return true
        }

        val meta = event.metaState
        val plain = event.getUnicodeChar(meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        var cp = plain
        // NOTE: Right Alt alone is AltGr on many layouts, where it types
        //       characters such as @ or the euro. It is a modifier only when it
        //       produces nothing different from the key without it.
        if (meta and KeyEvent.META_ALT_RIGHT_ON != 0 && meta and KeyEvent.META_ALT_LEFT_ON == 0) {
            val alted = event.getUnicodeChar(meta and KeyEvent.META_CTRL_MASK.inv())
            if (alted != 0 && alted != plain) {
                cp = alted
                mods = mods and KeyEncoder.ALT.inv()
            }
        }
        if (cp == 0) return false

        if (cp and KeyCharacterMap.COMBINING_ACCENT != 0) {
            deadAccent = cp and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (deadAccent != 0) {
            val combined = KeyCharacterMap.getDeadChar(deadAccent, cp)
            if (combined != 0) cp = combined
            deadAccent = 0
        }

        // Shift is already in the character
        mods = (mods and KeyEncoder.SHIFT.inv()) or modifiers.consume()
        s.write(KeyEncoder.char(cp, mods))
        scrollToBottom()
        return true
    }

    private fun termKeyFor(keyCode: Int): TermKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> TermKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> TermKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> TermKey.END
        KeyEvent.KEYCODE_PAGE_UP -> TermKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> TermKey.INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.DELETE
        KeyEvent.KEYCODE_ESCAPE -> TermKey.ESCAPE
        KeyEvent.KEYCODE_TAB -> TermKey.TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TermKey.ENTER
        KeyEvent.KEYCODE_DEL -> TermKey.BACKSPACE
        KeyEvent.KEYCODE_F1 -> TermKey.F1
        KeyEvent.KEYCODE_F2 -> TermKey.F2
        KeyEvent.KEYCODE_F3 -> TermKey.F3
        KeyEvent.KEYCODE_F4 -> TermKey.F4
        KeyEvent.KEYCODE_F5 -> TermKey.F5
        KeyEvent.KEYCODE_F6 -> TermKey.F6
        KeyEvent.KEYCODE_F7 -> TermKey.F7
        KeyEvent.KEYCODE_F8 -> TermKey.F8
        KeyEvent.KEYCODE_F9 -> TermKey.F9
        KeyEvent.KEYCODE_F10 -> TermKey.F10
        KeyEvent.KEYCODE_F11 -> TermKey.F11
        KeyEvent.KEYCODE_F12 -> TermKey.F12
        else -> null
    }

    private companion object {
        const val DEFAULT_TEXT_SIZE = 14f
        const val MIN_TEXT_SIZE = 9f
        const val MAX_TEXT_SIZE = 28f
        const val MAX_ARROWS_PER_EVENT = 20
    }
}
