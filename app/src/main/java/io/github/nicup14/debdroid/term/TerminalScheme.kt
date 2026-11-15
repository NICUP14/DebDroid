package io.github.nicup14.debdroid.term

// The terminal's colour schemes: background, text, cursor and the 16 ANSI colours
//
// Colours are ARGB ints. The 256-colour cube and grey ramp (16-255) are the
// standard xterm values in every scheme, since a program that picks them picks
// its own background too.
//
// `palette` colours text on the default background; `paletteOnColour` colours
// backgrounds, and text on a coloured background. They differ only in the light
// scheme, where "white" text must be a dark grey to be read on the light
// background, but a white *background* (or white text on a blue bar) must stay
// light.
enum class TerminalScheme(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val dim: Int,
    val isLight: Boolean,
    private val palette: IntArray,
    private val paletteOnColour: IntArray,
) {
    DEBDROID(
        background = rgb(0x0B0B0D),
        foreground = rgb(0xCFCFD6),
        cursor = rgb(0xE0103A),
        dim = rgb(0x6E6E7A),
        isLight = false,
        palette = DEBDROID_PALETTE,
        paletteOnColour = DEBDROID_PALETTE,
    ),
    CONTRAST(
        background = rgb(0x000000),
        foreground = rgb(0xFFFFFF),
        cursor = rgb(0xFFFFFF),
        dim = rgb(0xA8A8B0),
        isLight = false,
        palette = CONTRAST_PALETTE,
        paletteOnColour = CONTRAST_PALETTE,
    ),
    DAYLIGHT(
        background = rgb(0xF7F5F2),
        foreground = rgb(0x23232A),
        cursor = rgb(0xA80D2E),
        dim = rgb(0x6B6B73),
        isLight = true,
        palette = DAYLIGHT_PALETTE,
        paletteOnColour = DAYLIGHT_ON_COLOUR,
    );

    // An indexed colour (0..255) as text on the default background
    fun color(index: Int): Int = if (index < 16) palette[index] else XTERM_256[index and 0xFF]

    // An indexed colour (0..255) as a background, or as text on a coloured background
    fun colorOnColour(index: Int): Int = if (index < 16) paletteOnColour[index] else XTERM_256[index and 0xFF]
}

private fun rgb(value: Int): Int = (0xFF shl 24) or value

private fun palette(vararg values: Int): IntArray = IntArray(16) { rgb(values[it]) }

// NOTE: Red is a lighter, pinker red than the cursor's #E0103A, which is too
//       dark to read as text on #0B0B0D.
private val DEBDROID_PALETTE = palette(
    0x2A2A31, 0xFF5C7A, 0x34D399, 0xF2C55C, 0x7AA2F7, 0xC792EA, 0x56C8D8, 0xCFCFD6,
    0x6E6E7A, 0xFF8095, 0x6EE7B7, 0xFFE08A, 0xA5C0FF, 0xE0B3FF, 0x8BE9FD, 0xFFFFFF,
)

private val CONTRAST_PALETTE = palette(
    0x303030, 0xFF5F5F, 0x00FF87, 0xFFFF5F, 0x5FD7FF, 0xFF87FF, 0x87FFFF, 0xE4E4E4,
    0xA8A8B0, 0xFF8787, 0x5FFFAF, 0xFFFF87, 0x87D7FF, 0xFFAFFF, 0xAFFFFF, 0xFFFFFF,
)

// NOTE: Every entry reads on #F7F5F2 at 4.5:1 or better, yellow included, and
//       white and bright white are dark greys, which is what a program writing
//       "white" text on the default background needs on a light scheme.
private val DAYLIGHT_PALETTE = palette(
    0x23232A, 0xB3122E, 0x0A6E43, 0x7A5A00, 0x14459B, 0x8A2D8F, 0x0B6670, 0x4A4A52,
    0x6B6B73, 0xA80D2E, 0x0E7A4B, 0x855F00, 0x2257B8, 0x9C3AA3, 0x0F7480, 0x2F2F36,
)

private val DAYLIGHT_ON_COLOUR = palette(
    0x23232A, 0xB3122E, 0x0A6E43, 0x7A5A00, 0x14459B, 0x8A2D8F, 0x0B6670, 0xDCD9D3,
    0x6B6B73, 0xA80D2E, 0x0E7A4B, 0x855F00, 0x2257B8, 0x9C3AA3, 0x0F7480, 0xFFFFFF,
)

// The xterm 256-colour palette above 15: a 6x6x6 cube, then 24 greys
private val XTERM_256 = IntArray(256).also { table ->
    val levels = intArrayOf(0, 95, 135, 175, 215, 255)
    for (i in 16 until 232) {
        val n = i - 16
        table[i] = rgb((levels[n / 36] shl 16) or (levels[(n / 6) % 6] shl 8) or levels[n % 6])
    }
    for (i in 232 until 256) {
        val v = 8 + (i - 232) * 10
        table[i] = rgb((v shl 16) or (v shl 8) or v)
    }
}
