package io.github.nicup14.debdroid.term

import kotlin.math.pow

// Keeps text readable on whatever background a program picks
//
// A scheme's colours are chosen against its own background, but programs
// combine them freely. Debian's ls colours an other-writable directory blue on
// green, and the DebDroid scheme's soft blue on its soft green is about 1.3:1:
// the names vanished into solid blocks. Where text on a coloured background
// falls under the 3:1 floor for large text and UI, it is drawn in the
// darkest or lightest ink instead, whichever reads better.
object Contrast {
    const val FLOOR = 3.0
    const val DARK = 0xFF0B0B0D.toInt()
    const val LIGHT = 0xFFFFFFFF.toInt()

    // WCAG relative luminance of an ARGB colour
    fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // The foreground to draw on bg: fg itself when it is readable enough
    fun readable(fg: Int, bg: Int): Int {
        if (ratio(fg, bg) >= FLOOR) return fg
        return if (ratio(DARK, bg) >= ratio(LIGHT, bg)) DARK else LIGHT
    }
}
