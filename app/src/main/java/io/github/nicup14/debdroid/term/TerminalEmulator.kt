package io.github.nicup14.debdroid.term

import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

// The terminal's screen model: an xterm-compatible parser over a grid of cells
//
// Pure Kotlin with no android dependency, so it is tested on the host. It is
// not thread safe: the session feeds it from its reader thread and the view
// draws it from the main thread, and both hold the emulator's monitor
// (synchronized(emulator)) while they do.
//
// What it implements is the subset bash, readline, apt, nano, vim, less, man,
// htop, top, tmux and dialog use: see the CSI, ESC and mode handlers below.
// Not implemented: mouse reporting (the modes are accepted and ignored), left
// and right margins, double-width lines, and reflow on resize.
class TerminalEmulator(columns: Int, rows: Int, scrollbackLines: Int) {

    var columns: Int = max(1, columns)
        private set
    var rows: Int = max(1, rows)
        private set

    // Bytes to send back to the program, such as a cursor position report
    var onResponse: (ByteArray) -> Unit = {}
    var onTitle: (String) -> Unit = {}
    var onBell: () -> Unit = {}

    // The last title an OSC 0, 1 or 2 set
    var title: String = ""
        private set

    // Screens: the primary one feeds the scrollback, the alternate one never does
    private val history = Scrollback(scrollbackLines)
    private var primary = blankScreen(this.columns, this.rows)
    private var alternate = blankScreen(this.columns, this.rows)
    private var screen = primary

    var isAlternateScreen: Boolean = false
        private set

    // The cursor, and whether the next character wraps first (the "last column" state)
    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set
    private var aboutToWrap = false

    // The current SGR attributes
    private var foreground = TextStyle.COLOR_DEFAULT_FG
    private var background = TextStyle.COLOR_DEFAULT_BG
    private var effects = 0

    // The scroll region: top inclusive, bottom exclusive
    private var top = 0
    private var bottom = this.rows

    // Modes
    var applicationCursorKeys: Boolean = false
        private set
    var applicationKeypad: Boolean = false
        private set
    var bracketedPaste: Boolean = false
        private set
    var cursorVisible: Boolean = true
        private set
    var reverseVideo: Boolean = false
        private set
    var insertMode: Boolean = false
        private set
    var newLineMode: Boolean = false
        private set
    private var autowrap = true
    private var originMode = false

    // NOTE: Mouse reporting is not implemented. The modes are recorded so a
    //       request for them is answered truthfully, and nothing else uses them.
    private val mouseModes = HashSet<Int>()

    // Character sets G0..G3, and whether SO shifted G1 in
    private val charsets = IntArray(4)
    private var shiftOut = false

    private var tabStops = defaultTabStops(this.columns)

    // DECSC state, one per screen as in xterm
    private val savedPrimary = SavedCursor()
    private val savedAlternate = SavedCursor()

    // The last graphic character, for REP
    private var lastPrinted = -1

    // Parser state
    private var state = STATE_GROUND
    private val params = IntArray(MAX_PARAMS)
    private val subParam = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var csiPrefix = 0
    private var csiIntermediate = 0
    private var csiStarted = false
    private var csiInvalid = false
    private var escIntermediate = 0
    private val osc = StringBuilder()

    // UTF-8 decoder state, carried across append() calls
    private var utf8Remaining = 0
    private var utf8Code = 0
    private var utf8Min = 0

    // The number of scrollback lines above the screen; none while the alternate screen is up
    val scrollbackRows: Int get() = if (isAlternateScreen) 0 else history.size

    val scrollbackCapacity: Int get() = history.capacity

    // A visible row for 0 until rows, or a scrollback row for -scrollbackRows until 0
    fun row(y: Int): TerminalRow = if (y >= 0) screen[y] else history[history.size + y]

    // Feeds program output: a UTF-8 stream, which may split anywhere
    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            if (utf8Remaining == 0) {
                when {
                    b < 0x80 -> process(b)
                    b in 0xC2..0xDF -> start(b and 0x1F, 1, 0x80)
                    b in 0xE0..0xEF -> start(b and 0x0F, 2, 0x800)
                    b in 0xF0..0xF4 -> start(b and 0x07, 3, 0x10000)
                    else -> process(REPLACEMENT)
                }
                i++
            } else if (b and 0xC0 == 0x80) {
                utf8Code = (utf8Code shl 6) or (b and 0x3F)
                if (--utf8Remaining == 0) {
                    val cp = utf8Code
                    val invalid = cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF
                    process(if (invalid) REPLACEMENT else cp)
                }
                i++
            } else {
                // NOTE: A sequence cut short is one replacement character, and
                //       the byte that cut it is read again as a fresh start.
                utf8Remaining = 0
                process(REPLACEMENT)
            }
        }
    }

    private fun start(bits: Int, remaining: Int, minimum: Int) {
        utf8Code = bits
        utf8Remaining = remaining
        utf8Min = minimum
    }

    // Feeds a string, for tests and for local messages
    fun append(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        append(bytes, 0, bytes.size)
    }

    // Resizes both screens, keeping the bottom of the content and the cursor
    fun resize(columns: Int, rows: Int) {
        val newColumns = max(1, columns)
        val newRows = max(1, rows)
        if (newColumns == this.columns && newRows == this.rows) return

        val primaryCursor = if (isAlternateScreen && savedPrimary.valid) savedPrimary.y else cursorY
        val (newPrimary, primaryShift) = resizeScreen(primary, primaryCursor, history, newColumns, newRows)
        val (newAlternate, alternateShift) = resizeScreen(alternate, cursorY, null, newColumns, newRows)
        primary = newPrimary
        alternate = newAlternate
        screen = if (isAlternateScreen) alternate else primary

        this.columns = newColumns
        this.rows = newRows
        cursorY = (cursorY + if (isAlternateScreen) alternateShift else primaryShift).coerceIn(0, newRows - 1)
        cursorX = cursorX.coerceIn(0, newColumns - 1)
        aboutToWrap = false
        savedPrimary.shift(primaryShift, newColumns, newRows)
        savedAlternate.shift(alternateShift, newColumns, newRows)

        top = 0
        bottom = newRows

        val oldStops = tabStops
        tabStops = defaultTabStops(newColumns)
        System.arraycopy(oldStops, 0, tabStops, 0, min(oldStops.size, newColumns))
    }

    // Resizes one screen; returns it and how far its content moved down (negative: up)
    private fun resizeScreen(
        old: Array<TerminalRow>,
        cursorRow: Int,
        scrollback: Scrollback?,
        newColumns: Int,
        newRows: Int,
    ): Pair<Array<TerminalRow>, Int> {
        val lines = ArrayList<TerminalRow>(max(old.size, newRows))
        for (row in old) {
            row.resize(newColumns, TextStyle.DEFAULT)
            lines.add(row)
        }
        var shift = 0
        if (newRows < lines.size) {
            var excess = lines.size - newRows
            // NOTE: Blank lines below the cursor go first, so a shell prompt
            //       near the top stays where it is when the keyboard opens.
            while (excess > 0 && lines.size - 1 > cursorRow && lines[lines.size - 1].isBlank()) {
                lines.removeAt(lines.size - 1)
                excess--
            }
            while (excess > 0) {
                val row = lines.removeAt(0)
                scrollback?.push(row)
                shift--
                excess--
            }
        } else {
            var extra = newRows - lines.size
            // NOTE: Growing takes lines back from the scrollback, so closing the
            //       keyboard undoes what opening it did.
            while (extra > 0 && scrollback != null && scrollback.size > 0) {
                val row = scrollback.removeNewest() ?: break
                row.resize(newColumns, TextStyle.DEFAULT)
                lines.add(0, row)
                shift++
                extra--
            }
            while (extra > 0) {
                lines.add(TerminalRow(newColumns, TextStyle.DEFAULT))
                extra--
            }
        }
        return Pair(lines.toTypedArray(), shift)
    }

    fun setScrollback(lines: Int) {
        history.setCapacity(lines)
    }

    // The visible screen as plain text, one line per row, trailing spaces trimmed
    fun screenText(): String {
        val builder = StringBuilder()
        for (y in 0 until rows) {
            if (y > 0) builder.append('\n')
            builder.append(screen[y].toText(true))
        }
        return builder.toString()
    }

    // The scrollback and the screen as plain text
    // NOTE: Lines that autowrapped are joined back into the line the program
    //       wrote, and trailing blank lines are dropped, so a copy of it pastes
    //       as what was printed rather than as what fitted on the screen.
    fun transcript(): String {
        val builder = StringBuilder()
        if (!isAlternateScreen) {
            for (i in 0 until history.size) appendRow(builder, history[i])
        }
        for (row in screen) appendRow(builder, row)
        var end = builder.length
        while (end > 0 && builder[end - 1].isWhitespace()) end--
        builder.setLength(end)
        return builder.toString()
    }

    private fun appendRow(builder: StringBuilder, row: TerminalRow) {
        builder.append(row.toText(!row.wrapped))
        if (!row.wrapped) builder.append('\n')
    }

    // RIS: everything back to its initial state, except the scrollback
    fun reset() {
        for (row in primary) row.reset(columns, TextStyle.DEFAULT)
        for (row in alternate) row.reset(columns, TextStyle.DEFAULT)
        screen = primary
        isAlternateScreen = false
        cursorX = 0
        cursorY = 0
        aboutToWrap = false
        resetAttributes()
        top = 0
        bottom = rows
        applicationCursorKeys = false
        applicationKeypad = false
        bracketedPaste = false
        cursorVisible = true
        reverseVideo = false
        insertMode = false
        newLineMode = false
        autowrap = true
        originMode = false
        mouseModes.clear()
        charsets.fill(CHARSET_ASCII)
        shiftOut = false
        tabStops = defaultTabStops(columns)
        savedPrimary.valid = false
        savedAlternate.valid = false
        lastPrinted = -1
        state = STATE_GROUND
    }

    // DECSTR: the modes and attributes back to their defaults, the screen kept
    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autowrap = true
        applicationCursorKeys = false
        applicationKeypad = false
        top = 0
        bottom = rows
        charsets.fill(CHARSET_ASCII)
        shiftOut = false
        resetAttributes()
        savedPrimary.valid = false
        savedAlternate.valid = false
        aboutToWrap = false
    }

    // ---- The parser ------------------------------------------------------

    private fun process(cp: Int) {
        when (state) {
            STATE_GROUND -> {
                if (cp >= 0x20) {
                    // NOTE: DEL and the C1 range are not printable. C1 controls
                    //       arrive here only as UTF-8 code points, which no
                    //       program in practice uses as controls.
                    if (cp != 0x7F && (cp < 0x80 || cp > 0x9F)) print(cp)
                } else {
                    control(cp)
                }
            }
            STATE_ESCAPE -> if (cp < 0x20) control(cp) else escape(cp)
            STATE_ESCAPE_INTERMEDIATE -> if (cp < 0x20) control(cp) else escapeIntermediate(cp)
            STATE_CSI -> if (cp < 0x20) control(cp) else csi(cp)
            STATE_OSC -> oscChar(cp)
            STATE_OSC_ESCAPE -> {
                // NOTE: An ESC that is not the start of ST still ends the
                //       string, and then begins the escape it is.
                dispatchOsc()
                state = STATE_ESCAPE
                if (cp != '\\'.code) process(cp) else state = STATE_GROUND
            }
            STATE_STRING -> when (cp) {
                0x1B -> state = STATE_STRING_ESCAPE
                0x18, 0x1A -> state = STATE_GROUND
            }
            STATE_STRING_ESCAPE -> when (cp) {
                // NOTE: Anything but ST resumes the ignored string, so an
                //       escape embedded in it (tmux passthrough) cannot leak out.
                '\\'.code -> state = STATE_GROUND
                0x1B -> {}
                else -> state = STATE_STRING
            }
        }
    }

    private fun control(cp: Int) {
        when (cp) {
            0x07 -> onBell()
            0x08 -> {
                aboutToWrap = false
                if (cursorX > 0) cursorX--
            }
            0x09 -> forwardTab(1)
            0x0A, 0x0B, 0x0C -> {
                lineFeed()
                if (newLineMode) cursorX = 0
            }
            0x0D -> {
                cursorX = 0
                aboutToWrap = false
            }
            0x0E -> shiftOut = true
            0x0F -> shiftOut = false
            0x18, 0x1A -> state = STATE_GROUND
            0x1B -> {
                state = STATE_ESCAPE
                escIntermediate = 0
            }
        }
    }

    private fun escape(cp: Int) {
        state = STATE_GROUND
        when (cp.toChar()) {
            '[' -> {
                state = STATE_CSI
                paramCount = 1
                params[0] = -1
                subParam[0] = false
                csiPrefix = 0
                csiIntermediate = 0
                csiStarted = false
                csiInvalid = false
            }
            ']' -> {
                osc.setLength(0)
                state = STATE_OSC
            }
            'P', 'X', '^', '_' -> state = STATE_STRING
            '(', ')', '*', '+', '-', '.', '/', '#', '%', ' ' -> {
                escIntermediate = cp
                state = STATE_ESCAPE_INTERMEDIATE
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> {
                cursorX = 0
                lineFeed()
            }
            'M' -> reverseIndex()
            'H' -> tabStops[cursorX] = true
            'c' -> reset()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            else -> {}
        }
    }

    private fun escapeIntermediate(cp: Int) {
        if (cp in 0x20..0x2F) {
            escIntermediate = cp
            return
        }
        state = STATE_GROUND
        when (escIntermediate.toChar()) {
            '(' -> charsets[0] = charsetFor(cp)
            ')' -> charsets[1] = charsetFor(cp)
            '*' -> charsets[2] = charsetFor(cp)
            '+' -> charsets[3] = charsetFor(cp)
            '#' -> if (cp == '8'.code) screenAlignment()
            else -> {}
        }
    }

    private fun charsetFor(cp: Int): Int = when (cp.toChar()) {
        '0' -> CHARSET_DEC_GRAPHICS
        'A' -> CHARSET_UK
        else -> CHARSET_ASCII
    }

    private fun csi(cp: Int) {
        when {
            cp in 0x30..0x39 -> {
                csiStarted = true
                val index = paramCount - 1
                val current = params[index]
                params[index] = min(MAX_PARAM_VALUE, (if (current < 0) 0 else current) * 10 + (cp - 0x30))
            }
            cp == ';'.code || cp == ':'.code -> {
                csiStarted = true
                if (paramCount < MAX_PARAMS) {
                    params[paramCount] = -1
                    subParam[paramCount] = cp == ':'.code
                    paramCount++
                } else {
                    csiInvalid = true
                }
            }
            cp in 0x3C..0x3F -> {
                if (!csiStarted && csiPrefix == 0) csiPrefix = cp else csiInvalid = true
                csiStarted = true
            }
            cp in 0x20..0x2F -> csiIntermediate = cp
            cp in 0x40..0x7E -> {
                state = STATE_GROUND
                if (!csiInvalid) dispatchCsi(cp)
            }
            cp == 0x7F -> {}
            else -> state = STATE_GROUND
        }
    }

    private fun oscChar(cp: Int) {
        when (cp) {
            0x07 -> {
                dispatchOsc()
                state = STATE_GROUND
            }
            0x1B -> state = STATE_OSC_ESCAPE
            0x18, 0x1A -> state = STATE_GROUND
            else -> if (cp >= 0x20 && osc.length < OSC_LIMIT) osc.appendCodePoint(cp)
        }
    }

    private fun dispatchOsc() {
        val text = osc.toString()
        osc.setLength(0)
        val separator = text.indexOf(';')
        val code = (if (separator < 0) text else text.substring(0, separator)).toIntOrNull()
        val value = if (separator < 0) "" else text.substring(separator + 1)
        when (code) {
            0, 1, 2 -> {
                title = value
                onTitle(value)
            }
        }
    }

    // A parameter, or the default when it is missing
    private fun param(index: Int, default: Int): Int {
        if (index >= paramCount) return default
        val value = params[index]
        return if (value < 0) default else value
    }

    // A count, where both missing and 0 mean 1
    private fun count(index: Int): Int {
        val value = param(index, 1)
        return if (value == 0) 1 else value
    }

    private fun dispatchCsi(final: Int) {
        if (csiIntermediate != 0) {
            when {
                csiIntermediate == '!'.code && final == 'p'.code -> softReset()
                csiIntermediate == '$'.code && final == 'p'.code -> reportMode()
                // DECSCUSR (cursor shape) and the rest are accepted and ignored
                else -> {}
            }
            return
        }
        when (csiPrefix) {
            0 -> dispatchStandard(final)
            '?'.code -> when (final.toChar()) {
                'h' -> setModes(true)
                'l' -> setModes(false)
                'J' -> eraseInDisplay(param(0, 0))
                'K' -> eraseInLine(param(0, 0))
                'n' -> if (param(0, 0) == 6) respond("\u001b[?${reportRow()};${cursorX + 1}R")
                else -> {}
            }
            '>'.code -> when (final.toChar()) {
                // NOTE: A VT220 at a low version. A program that recognises a
                //       newer xterm from this sends queries the emulator
                //       would only ignore.
                'c' -> if (param(0, 0) == 0) respond("\u001b[>1;10;0c")
                else -> {}
            }
            else -> {}
        }
    }

    private fun dispatchStandard(final: Int) {
        when (final.toChar()) {
            '@' -> {
                aboutToWrap = false
                screen[cursorY].insertCells(cursorX, count(0), eraseStyle())
            }
            'A' -> cursorUp(count(0))
            'B', 'e' -> cursorDown(count(0))
            'C', 'a' -> setCursorX(cursorX + count(0))
            'D' -> setCursorX(cursorX - count(0))
            'E' -> {
                cursorDown(count(0))
                cursorX = 0
            }
            'F' -> {
                cursorUp(count(0))
                cursorX = 0
            }
            'G', '`' -> setCursorX(count(0) - 1)
            'H', 'f' -> cursorPosition(count(0), count(1))
            'I' -> forwardTab(count(0))
            'J' -> eraseInDisplay(param(0, 0))
            'K' -> eraseInLine(param(0, 0))
            'L' -> insertLines(count(0))
            'M' -> deleteLines(count(0))
            'P' -> {
                aboutToWrap = false
                screen[cursorY].deleteCells(cursorX, count(0), eraseStyle())
            }
            'S' -> scrollUp(top, bottom, count(0), !isAlternateScreen && top == 0)
            // NOTE: SD with five parameters is xterm's mouse highlight tracking
            'T' -> if (paramCount <= 1) scrollDown(top, bottom, count(0))
            'X' -> {
                aboutToWrap = false
                screen[cursorY].erase(cursorX, cursorX + count(0), eraseStyle())
            }
            'Z' -> backTab(count(0))
            'b' -> if (lastPrinted >= 0) repeat(min(count(0), MAX_REPEAT)) { print(lastPrinted) }
            'c' -> if (param(0, 0) == 0) respond("\u001b[?62;22c")
            'd' -> {
                val row = count(0) - 1
                cursorY = if (originMode) (top + row).coerceIn(top, bottom - 1) else row.coerceIn(0, rows - 1)
                aboutToWrap = false
            }
            'g' -> when (param(0, 0)) {
                0 -> tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h' -> setModes(true)
            'l' -> setModes(false)
            'm' -> selectGraphicRendition()
            'n' -> when (param(0, 0)) {
                5 -> respond("\u001b[0n")
                6 -> respond("\u001b[${reportRow()};${cursorX + 1}R")
            }
            'r' -> setScrollRegion()
            's' -> if (paramCount == 1 && params[0] < 0) saveCursor()
            't' -> if (param(0, 0) == 18) respond("\u001b[8;$rows;${columns}t")
            'u' -> if (paramCount == 1 && params[0] < 0) restoreCursor()
            else -> {}
        }
    }

    private fun respond(text: String) {
        onResponse(text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun reportRow(): Int = if (originMode) cursorY - top + 1 else cursorY + 1

    // ---- Printing --------------------------------------------------------

    private fun print(codePoint: Int) {
        var cp = codePoint
        val set = charsets[if (shiftOut) 1 else 0]
        if (set == CHARSET_DEC_GRAPHICS && cp in 0x5F..0x7E) {
            cp = DEC_GRAPHICS[cp - 0x5F]
        } else if (set == CHARSET_UK && cp == '#'.code) {
            cp = 0xA3
        }

        var width = CharWidth.of(cp)
        if (width == 0) {
            attachCombining(cp)
            return
        }
        if (width == 2 && columns < 2) width = 1

        if (aboutToWrap) {
            aboutToWrap = false
            if (autowrap) {
                screen[cursorY].wrapped = true
                cursorX = 0
                lineFeed()
            }
        }

        // NOTE: A wide character cannot start in the last column: with autowrap
        //       it moves to the next line, leaving a blank, and without it, it
        //       overwrites the last two columns.
        if (width == 2 && cursorX == columns - 1) {
            if (autowrap) {
                val row = screen[cursorY]
                row.erase(cursorX, columns, eraseStyle())
                row.wrapped = true
                cursorX = 0
                lineFeed()
            } else {
                cursorX = columns - 2
            }
        }

        val row = screen[cursorY]
        if (insertMode) row.insertCells(cursorX, width, eraseStyle())
        row.put(cursorX, cp, width, currentStyle())
        lastPrinted = cp

        val next = cursorX + width
        if (next >= columns) {
            cursorX = columns - 1
            aboutToWrap = autowrap
        } else {
            cursorX = next
        }
    }

    // Attaches a combining mark to the character before the cursor
    private fun attachCombining(cp: Int) {
        var x = if (aboutToWrap) cursorX else cursorX - 1
        if (x < 0) return
        val row = screen[cursorY]
        if (row.text[x] == TerminalRow.WIDE_TAIL && x > 0) x--
        row.addCombining(x, cp)
    }

    private fun currentStyle(): Long = TextStyle.encode(foreground, background, effects)

    // NOTE: Erased cells take the current background, as xterm's do (BCE),
    //       which is what makes a program's coloured status bar fill the line.
    private fun eraseStyle(): Long = TextStyle.encode(TextStyle.COLOR_DEFAULT_FG, background, 0)

    private fun resetAttributes() {
        foreground = TextStyle.COLOR_DEFAULT_FG
        background = TextStyle.COLOR_DEFAULT_BG
        effects = 0
    }

    // ---- Cursor movement -------------------------------------------------

    private fun setCursorX(x: Int) {
        cursorX = x.coerceIn(0, columns - 1)
        aboutToWrap = false
    }

    private fun cursorUp(n: Int) {
        val limit = if (cursorY >= top) top else 0
        cursorY = max(limit, cursorY - n)
        aboutToWrap = false
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorY < bottom) bottom - 1 else rows - 1
        cursorY = min(limit, cursorY + n)
        aboutToWrap = false
    }

    // CUP, 1-based, relative to the scroll region in origin mode
    private fun cursorPosition(row: Int, column: Int) {
        cursorY = if (originMode) (top + row - 1).coerceIn(top, bottom - 1) else (row - 1).coerceIn(0, rows - 1)
        cursorX = (column - 1).coerceIn(0, columns - 1)
        aboutToWrap = false
    }

    private fun forwardTab(n: Int) {
        var x = cursorX
        repeat(n) {
            if (x < columns - 1) {
                x++
                while (x < columns - 1 && !tabStops[x]) x++
            }
        }
        if (x != cursorX) {
            cursorX = x
            aboutToWrap = false
        }
    }

    private fun backTab(n: Int) {
        var x = cursorX
        repeat(n) {
            if (x > 0) {
                x--
                while (x > 0 && !tabStops[x]) x--
            }
        }
        cursorX = x
        aboutToWrap = false
    }

    private fun lineFeed() {
        aboutToWrap = false
        if (cursorY == bottom - 1) {
            scrollUp(top, bottom, 1, !isAlternateScreen && top == 0)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        aboutToWrap = false
        if (cursorY == top) {
            scrollDown(top, bottom, 1)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    private fun setScrollRegion() {
        val newTop = param(0, 1).coerceAtLeast(1) - 1
        val requestedBottom = param(1, 0)
        val newBottom = if (requestedBottom == 0) rows else min(requestedBottom, rows)
        // NOTE: A region needs at least two lines; anything less is ignored, as in xterm.
        if (newBottom - newTop < 2) return
        top = newTop
        bottom = newBottom
        cursorPosition(1, 1)
    }

    // ---- Scrolling and erasing -------------------------------------------

    // Scrolls [regionTop, regionBottom) up, optionally saving the lines that leave into the scrollback
    // NOTE: The scrollback takes lines whenever the region starts at the top of
    //       the primary screen, as xterm's does, not only for a full-height
    //       region: apt keeps its progress bar in a region one line short of
    //       the screen, and its output would otherwise never reach the scrollback.
    private fun scrollUp(regionTop: Int, regionBottom: Int, n: Int, save: Boolean) {
        val count = min(n, regionBottom - regionTop)
        val style = eraseStyle()
        repeat(count) {
            val leaving = screen[regionTop]
            System.arraycopy(screen, regionTop + 1, screen, regionTop, regionBottom - regionTop - 1)
            val fresh = if (save) history.push(leaving) ?: TerminalRow(columns, style) else leaving
            fresh.reset(columns, style)
            screen[regionBottom - 1] = fresh
        }
    }

    // Scrolls [regionTop, regionBottom) down, discarding the lines pushed off its bottom
    private fun scrollDown(regionTop: Int, regionBottom: Int, n: Int) {
        val count = min(n, regionBottom - regionTop)
        val style = eraseStyle()
        repeat(count) {
            val leaving = screen[regionBottom - 1]
            System.arraycopy(screen, regionTop, screen, regionTop + 1, regionBottom - regionTop - 1)
            leaving.reset(columns, style)
            screen[regionTop] = leaving
        }
    }

    private fun insertLines(n: Int) {
        if (cursorY < top || cursorY >= bottom) return
        scrollDown(cursorY, bottom, n)
        cursorX = 0
        aboutToWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY < top || cursorY >= bottom) return
        scrollUp(cursorY, bottom, n, false)
        cursorX = 0
        aboutToWrap = false
    }

    private fun eraseInDisplay(mode: Int) {
        val style = eraseStyle()
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (y in cursorY + 1 until rows) screen[y].reset(columns, style)
            }
            1 -> {
                for (y in 0 until cursorY) screen[y].reset(columns, style)
                eraseInLine(1)
            }
            2 -> for (y in 0 until rows) screen[y].reset(columns, style)
            3 -> history.clear()
        }
        aboutToWrap = false
    }

    private fun eraseInLine(mode: Int) {
        val row = screen[cursorY]
        val style = eraseStyle()
        when (mode) {
            0 -> {
                row.erase(cursorX, columns, style)
                row.wrapped = false
            }
            1 -> row.erase(0, cursorX + 1, style)
            2 -> {
                row.erase(0, columns, style)
                row.wrapped = false
            }
        }
        aboutToWrap = false
    }

    // DECALN: fills the screen with E, for alignment
    private fun screenAlignment() {
        for (row in screen) {
            row.reset(columns, TextStyle.DEFAULT)
            row.text.fill('E'.code)
        }
        top = 0
        bottom = rows
        cursorX = 0
        cursorY = 0
        aboutToWrap = false
    }

    // ---- Modes -----------------------------------------------------------

    private fun setModes(enable: Boolean) {
        for (i in 0 until paramCount) {
            val mode = params[i]
            if (mode < 0) continue
            if (csiPrefix == '?'.code) setPrivateMode(mode, enable) else setAnsiMode(mode, enable)
        }
    }

    private fun setAnsiMode(mode: Int, enable: Boolean) {
        when (mode) {
            4 -> insertMode = enable
            20 -> newLineMode = enable
        }
    }

    private fun setPrivateMode(mode: Int, enable: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = enable
            5 -> reverseVideo = enable
            6 -> {
                originMode = enable
                cursorPosition(1, 1)
            }
            7 -> {
                autowrap = enable
                if (!enable) aboutToWrap = false
            }
            25 -> cursorVisible = enable
            47 -> useAlternateScreen(enable, false)
            1047 -> {
                if (!enable && isAlternateScreen) clearScreen(alternate)
                useAlternateScreen(enable, false)
            }
            1048 -> if (enable) saveCursor() else restoreCursor()
            1049 -> if (enable) {
                saveCursor()
                useAlternateScreen(true, true)
            } else {
                useAlternateScreen(false, false)
                restoreCursor()
            }
            2004 -> bracketedPaste = enable
            9, 1000, 1001, 1002, 1003, 1005, 1006, 1015, 1016 -> if (enable) mouseModes.add(mode) else mouseModes.remove(mode)
            // 3 (column mode), 12 (blinking cursor) and the rest are accepted and ignored
            else -> {}
        }
    }

    // DECRQM: reports whether a mode is set (1), reset (2) or unknown (0)
    private fun reportMode() {
        val mode = param(0, 0)
        if (csiPrefix == '?'.code) {
            val value = when (mode) {
                1 -> applicationCursorKeys
                5 -> reverseVideo
                6 -> originMode
                7 -> autowrap
                25 -> cursorVisible
                47, 1047, 1049 -> isAlternateScreen
                2004 -> bracketedPaste
                9, 1000, 1001, 1002, 1003, 1005, 1006, 1015, 1016 -> mouseModes.contains(mode)
                else -> null
            }
            respond("\u001b[?$mode;${modeState(value)}\$y")
        } else if (csiPrefix == 0) {
            val value = when (mode) {
                4 -> insertMode
                20 -> newLineMode
                else -> null
            }
            respond("\u001b[$mode;${modeState(value)}\$y")
        }
    }

    private fun modeState(value: Boolean?): Int = when (value) {
        null -> 0
        true -> 1
        false -> 2
    }

    private fun useAlternateScreen(enable: Boolean, clear: Boolean) {
        if (enable && clear) clearScreen(alternate)
        if (enable == isAlternateScreen) return
        isAlternateScreen = enable
        screen = if (enable) alternate else primary
        aboutToWrap = false
    }

    private fun clearScreen(lines: Array<TerminalRow>) {
        val style = eraseStyle()
        for (row in lines) row.reset(columns, style)
    }

    // ---- Saved cursor ----------------------------------------------------

    private fun currentSaved(): SavedCursor = if (isAlternateScreen) savedAlternate else savedPrimary

    private fun saveCursor() {
        val saved = currentSaved()
        saved.valid = true
        saved.x = cursorX
        saved.y = cursorY
        saved.foreground = foreground
        saved.background = background
        saved.effects = effects
        saved.aboutToWrap = aboutToWrap
        saved.originMode = originMode
        System.arraycopy(charsets, 0, saved.charsets, 0, 4)
        saved.shiftOut = shiftOut
    }

    private fun restoreCursor() {
        val saved = currentSaved()
        if (!saved.valid) {
            // NOTE: As in xterm, restoring with nothing saved homes the cursor
            //       and resets the attributes.
            cursorX = 0
            cursorY = 0
            aboutToWrap = false
            originMode = false
            resetAttributes()
            charsets.fill(CHARSET_ASCII)
            shiftOut = false
            return
        }
        cursorX = saved.x.coerceIn(0, columns - 1)
        cursorY = saved.y.coerceIn(0, rows - 1)
        foreground = saved.foreground
        background = saved.background
        effects = saved.effects
        aboutToWrap = saved.aboutToWrap && cursorX == columns - 1
        originMode = saved.originMode
        System.arraycopy(saved.charsets, 0, charsets, 0, 4)
        shiftOut = saved.shiftOut
    }

    private class SavedCursor {
        var valid = false
        var x = 0
        var y = 0
        var foreground = TextStyle.COLOR_DEFAULT_FG
        var background = TextStyle.COLOR_DEFAULT_BG
        var effects = 0
        var aboutToWrap = false
        var originMode = false
        val charsets = IntArray(4)
        var shiftOut = false

        fun shift(rows: Int, newColumns: Int, newRows: Int) {
            y = (y + rows).coerceIn(0, newRows - 1)
            x = x.coerceIn(0, newColumns - 1)
            aboutToWrap = false
        }
    }

    // ---- SGR -------------------------------------------------------------

    private fun selectGraphicRendition() {
        var i = 0
        while (i < paramCount) {
            val p = if (params[i] < 0) 0 else params[i]
            // Sub-parameters (colon-separated) belong to this parameter
            var subs = 0
            while (i + 1 + subs < paramCount && subParam[i + 1 + subs]) subs++
            var consumed = 0
            when (p) {
                0 -> resetAttributes()
                1 -> effects = effects or TextStyle.BOLD
                2 -> effects = effects or TextStyle.DIM
                3 -> effects = effects or TextStyle.ITALIC
                4 -> effects = if (subs > 0 && params[i + 1] == 0) {
                    effects and TextStyle.UNDERLINE.inv()
                } else {
                    effects or TextStyle.UNDERLINE
                }
                5, 6 -> effects = effects or TextStyle.BLINK
                7 -> effects = effects or TextStyle.INVERSE
                8 -> effects = effects or TextStyle.HIDDEN
                9 -> effects = effects or TextStyle.STRIKETHROUGH
                21 -> effects = effects or TextStyle.UNDERLINE
                22 -> effects = effects and (TextStyle.BOLD or TextStyle.DIM).inv()
                23 -> effects = effects and TextStyle.ITALIC.inv()
                24 -> effects = effects and TextStyle.UNDERLINE.inv()
                25 -> effects = effects and TextStyle.BLINK.inv()
                27 -> effects = effects and TextStyle.INVERSE.inv()
                28 -> effects = effects and TextStyle.HIDDEN.inv()
                29 -> effects = effects and TextStyle.STRIKETHROUGH.inv()
                in 30..37 -> foreground = p - 30
                39 -> foreground = TextStyle.COLOR_DEFAULT_FG
                in 40..47 -> background = p - 40
                49 -> background = TextStyle.COLOR_DEFAULT_BG
                in 90..97 -> foreground = p - 90 + 8
                in 100..107 -> background = p - 100 + 8
                38, 48, 58 -> {
                    val color: Int
                    if (subs > 0) {
                        color = extendedColorFromSubParams(i + 1, subs)
                    } else {
                        color = extendedColorFromParams(i + 1)
                        consumed = extendedConsumed
                    }
                    if (color >= 0) {
                        // NOTE: 58 is the underline colour, parsed only so its
                        //       arguments are not read as attributes.
                        if (p == 38) foreground = color else if (p == 48) background = color
                    }
                }
            }
            i += 1 + subs + consumed
        }
    }

    private var extendedConsumed = 0

    // 38;5;n or 38;2;r;g;b: returns the colour, or -1, and sets extendedConsumed
    private fun extendedColorFromParams(start: Int): Int {
        val kind = param(start, -1)
        return when {
            kind == 5 && start + 1 < paramCount -> {
                extendedConsumed = 2
                param(start + 1, 0).coerceIn(0, 255)
            }
            kind == 2 && start + 3 < paramCount -> {
                extendedConsumed = 4
                TextStyle.rgb(param(start + 1, 0), param(start + 2, 0), param(start + 3, 0))
            }
            else -> {
                // NOTE: Malformed: the rest of the sequence cannot be trusted.
                extendedConsumed = paramCount
                -1
            }
        }
    }

    // 38:5:n, 38:2:r:g:b, or 38:2:colourspace:r:g:b
    private fun extendedColorFromSubParams(start: Int, subs: Int): Int {
        val kind = params[start]
        return when {
            kind == 5 && subs >= 2 -> param(start + 1, 0).coerceIn(0, 255)
            kind == 2 && subs >= 5 -> TextStyle.rgb(param(start + 2, 0), param(start + 3, 0), param(start + 4, 0))
            kind == 2 && subs == 4 -> TextStyle.rgb(param(start + 1, 0), param(start + 2, 0), param(start + 3, 0))
            else -> -1
        }
    }

    // ---- Testing and rendering helpers -----------------------------------

    // The packed style of a visible cell
    fun styleAt(column: Int, row: Int): Long = screen[row].styles[column]

    // The text of a visible cell, including any combining marks; empty for the right half of a wide character
    fun cellText(column: Int, row: Int): String {
        val line = screen[row]
        val cp = line.text[column]
        if (cp == TerminalRow.WIDE_TAIL) return ""
        return String(Character.toChars(cp)) + (line.combiningAt(column) ?: "")
    }

    companion object {
        private const val STATE_GROUND = 0
        private const val STATE_ESCAPE = 1
        private const val STATE_ESCAPE_INTERMEDIATE = 2
        private const val STATE_CSI = 3
        private const val STATE_OSC = 4
        private const val STATE_OSC_ESCAPE = 5
        private const val STATE_STRING = 6
        private const val STATE_STRING_ESCAPE = 7

        private const val MAX_PARAMS = 32
        private const val MAX_PARAM_VALUE = 99999
        private const val MAX_REPEAT = 65535
        private const val OSC_LIMIT = 4096
        private const val REPLACEMENT = 0xFFFD

        private const val CHARSET_ASCII = 0
        private const val CHARSET_DEC_GRAPHICS = 1
        private const val CHARSET_UK = 2

        // DEC special graphics for 0x5F..0x7E, the line drawing set
        private val DEC_GRAPHICS = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )

        private fun blankScreen(columns: Int, rows: Int): Array<TerminalRow> =
            Array(rows) { TerminalRow(columns, TextStyle.DEFAULT) }

        private fun defaultTabStops(columns: Int): BooleanArray =
            BooleanArray(columns) { it > 0 && it % 8 == 0 }
    }
}
