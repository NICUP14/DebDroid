package io.github.nicup14.debdroid.term

// One line of cells: a code point and a packed TextStyle per cell, plus any combining marks
//
// A wide character occupies two cells: its code point in the first and
// WIDE_TAIL in the second. Rows in the scrollback keep the width they had when
// they scrolled off, so a reader must use this row's own `columns`.
class TerminalRow internal constructor(columns: Int, style: Long) {

    var columns: Int = columns
        private set

    // NOTE: Internal rather than private so the view can draw straight from
    //       them without a copy. Only the emulator writes them.
    internal var text = IntArray(columns)
    internal var styles = LongArray(columns)
    internal var combining: Array<String?>? = null

    // True when the line continued onto the next one by autowrap
    var wrapped: Boolean = false
        internal set

    init {
        text.fill(BLANK)
        styles.fill(style)
    }

    fun codePointAt(column: Int): Int = text[column]

    fun styleAt(column: Int): Long = styles[column]

    fun combiningAt(column: Int): String? = combining?.get(column)

    // True for the right half of a wide character
    fun isWideTail(column: Int): Boolean = text[column] == WIDE_TAIL

    // True when the cell holds the left half of a wide character
    fun isWideHead(column: Int): Boolean = column + 1 < columns && text[column + 1] == WIDE_TAIL

    // Blanks the whole row, reallocating if the width changed
    internal fun reset(newColumns: Int, style: Long) {
        if (newColumns != columns) {
            columns = newColumns
            text = IntArray(newColumns)
            styles = LongArray(newColumns)
        }
        text.fill(BLANK)
        styles.fill(style)
        combining = null
        wrapped = false
    }

    // Changes the width, truncating or padding with blanks
    internal fun resize(newColumns: Int, style: Long) {
        if (newColumns == columns) return
        val keep = minOf(columns, newColumns)
        val newText = IntArray(newColumns)
        val newStyles = LongArray(newColumns)
        System.arraycopy(text, 0, newText, 0, keep)
        System.arraycopy(styles, 0, newStyles, 0, keep)
        for (i in keep until newColumns) {
            newText[i] = BLANK
            newStyles[i] = style
        }
        combining = combining?.let { old -> arrayOfNulls<String>(newColumns).also { System.arraycopy(old, 0, it, 0, keep) } }
        text = newText
        styles = newStyles
        columns = newColumns
        // NOTE: Truncating can cut a wide character in half; the left half alone
        //       would draw over nothing, so it becomes a blank.
        if (newColumns > 0 && CharWidth.of(text[newColumns - 1]) == 2) text[newColumns - 1] = BLANK
    }

    // Writes one character of the given width at a column
    internal fun put(column: Int, codePoint: Int, width: Int, style: Long) {
        // NOTE: Overwriting either half of a wide character orphans the other
        //       half, which then becomes a blank.
        if (text[column] == WIDE_TAIL && column > 0) {
            text[column - 1] = BLANK
            clearCombining(column - 1)
        }
        val end = column + width
        if (end < columns && text[end] == WIDE_TAIL) text[end] = BLANK
        text[column] = codePoint
        styles[column] = style
        clearCombining(column)
        if (width == 2 && column + 1 < columns) {
            text[column + 1] = WIDE_TAIL
            styles[column + 1] = style
            clearCombining(column + 1)
        }
    }

    // Blanks the cells in [from, to)
    internal fun erase(from: Int, to: Int, style: Long) {
        val start = from.coerceIn(0, columns)
        val end = to.coerceIn(0, columns)
        if (start >= end) return
        if (text[start] == WIDE_TAIL && start > 0) text[start - 1] = BLANK
        if (end < columns && text[end] == WIDE_TAIL) text[end] = BLANK
        for (i in start until end) {
            text[i] = BLANK
            styles[i] = style
        }
        combining?.fill(null, start, end)
    }

    // Inserts blanks at a column, shifting the rest right and off the end
    internal fun insertCells(column: Int, count: Int, style: Long) {
        if (column >= columns) return
        val n = minOf(count, columns - column)
        if (text[column] == WIDE_TAIL && column > 0) {
            text[column - 1] = BLANK
            text[column] = BLANK
        }
        val moved = columns - column - n
        System.arraycopy(text, column, text, column + n, moved)
        System.arraycopy(styles, column, styles, column + n, moved)
        combining?.let { System.arraycopy(it, column, it, column + n, moved) }
        for (i in column until column + n) {
            text[i] = BLANK
            styles[i] = style
        }
        combining?.fill(null, column, column + n)
        if (CharWidth.of(text[columns - 1]) == 2) text[columns - 1] = BLANK
    }

    // Deletes cells at a column, shifting the rest left and blanking the end
    internal fun deleteCells(column: Int, count: Int, style: Long) {
        if (column >= columns) return
        val n = minOf(count, columns - column)
        if (text[column] == WIDE_TAIL && column > 0) text[column - 1] = BLANK
        val moved = columns - column - n
        System.arraycopy(text, column + n, text, column, moved)
        System.arraycopy(styles, column + n, styles, column, moved)
        combining?.let { System.arraycopy(it, column + n, it, column, moved) }
        for (i in columns - n until columns) {
            text[i] = BLANK
            styles[i] = style
        }
        combining?.fill(null, columns - n, columns)
        if (text[column] == WIDE_TAIL) text[column] = BLANK
    }

    // Attaches a combining mark to a cell
    internal fun addCombining(column: Int, codePoint: Int) {
        val marks = combining ?: arrayOfNulls<String>(columns).also { combining = it }
        val mark = String(Character.toChars(codePoint))
        // NOTE: Capped, so a stream of nothing but combining marks cannot grow
        //       one cell without bound.
        val existing = marks[column]
        if (existing == null) marks[column] = mark else if (existing.length < 16) marks[column] = existing + mark
    }

    private fun clearCombining(column: Int) {
        combining?.set(column, null)
    }

    // True when every cell is a blank with no marks
    internal fun isBlank(): Boolean {
        for (i in 0 until columns) if (text[i] != BLANK) return false
        return combining?.all { it == null } ?: true
    }

    // The row as plain text, optionally without its trailing spaces
    fun toText(trimEnd: Boolean = true): String {
        val builder = StringBuilder(columns)
        for (i in 0 until columns) {
            val cp = text[i]
            if (cp == WIDE_TAIL) continue
            builder.appendCodePoint(cp)
            combining?.get(i)?.let { builder.append(it) }
        }
        if (trimEnd) {
            var end = builder.length
            while (end > 0 && builder[end - 1] == ' ') end--
            builder.setLength(end)
        }
        return builder.toString()
    }

    companion object {
        const val WIDE_TAIL = 0
        const val BLANK = ' '.code
    }
}

// The lines scrolled off the top of the primary screen, oldest first, in a ring
internal class Scrollback(capacity: Int) {
    private var ring = arrayOfNulls<TerminalRow>(maxOf(0, capacity))
    private var head = 0

    var size = 0
        private set

    val capacity: Int get() = ring.size

    operator fun get(index: Int): TerminalRow = ring[(head + index) % ring.size]!!

    // Adds the newest line, returning the line that fell off (or the line itself with no room), for reuse
    fun push(row: TerminalRow): TerminalRow? {
        if (ring.isEmpty()) return row
        if (size < ring.size) {
            ring[(head + size) % ring.size] = row
            size++
            return null
        }
        val evicted = ring[head]
        ring[head] = row
        head = (head + 1) % ring.size
        return evicted
    }

    // Takes the newest line back out, for a screen that grew
    fun removeNewest(): TerminalRow? {
        if (size == 0) return null
        val index = (head + size - 1) % ring.size
        val row = ring[index]
        ring[index] = null
        size--
        return row
    }

    fun clear() {
        ring.fill(null)
        head = 0
        size = 0
    }

    // Changes the capacity, keeping the newest lines
    fun setCapacity(capacity: Int) {
        val newCapacity = maxOf(0, capacity)
        if (newCapacity == ring.size) return
        val keep = minOf(size, newCapacity)
        val newRing = arrayOfNulls<TerminalRow>(newCapacity)
        for (i in 0 until keep) newRing[i] = get(size - keep + i)
        ring = newRing
        head = 0
        size = keep
    }
}
