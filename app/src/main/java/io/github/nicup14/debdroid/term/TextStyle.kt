package io.github.nicup14.debdroid.term

// Packs a cell's colours and effects into one Long, so a row's styles are a LongArray
//
// Layout: bits 0-24 the foreground, bits 25-49 the background, bits 50-57 the
// effects. A colour is one of:
//   - 0..255, an index into the xterm palette (0..15 come from the scheme)
//   - COLOR_DEFAULT_FG or COLOR_DEFAULT_BG, the scheme's own text or background
//   - COLOR_RGB or 0xRRGGBB, a truecolor value
object TextStyle {
    const val COLOR_DEFAULT_FG = 256
    const val COLOR_DEFAULT_BG = 257
    const val COLOR_RGB = 0x1000000

    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val HIDDEN = 64
    const val STRIKETHROUGH = 128

    private const val COLOR_MASK = 0x1FFFFFFL

    @JvmStatic
    fun encode(foreground: Int, background: Int, effects: Int): Long =
        (foreground.toLong() and COLOR_MASK) or
            ((background.toLong() and COLOR_MASK) shl 25) or
            ((effects.toLong() and 0xFF) shl 50)

    @JvmField
    val DEFAULT: Long = encode(COLOR_DEFAULT_FG, COLOR_DEFAULT_BG, 0)

    @JvmStatic
    fun foreground(style: Long): Int = (style and COLOR_MASK).toInt()

    @JvmStatic
    fun background(style: Long): Int = ((style ushr 25) and COLOR_MASK).toInt()

    @JvmStatic
    fun effects(style: Long): Int = ((style ushr 50) and 0xFF).toInt()

    @JvmStatic
    fun isRgb(color: Int): Boolean = color and COLOR_RGB != 0

    // Builds a truecolor value from its components
    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int =
        COLOR_RGB or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
