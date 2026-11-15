package io.github.nicup14.debdroid.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

// The schemes' colours, checked for legibility rather than by eye
class TerminalSchemeTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xFF) + 0.7152 * channel((color shr 8) and 0xFF) + 0.0722 * channel(color and 0xFF)

    // The WCAG contrast ratio
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun designValues() {
        assertEquals(0xFF0B0B0D.toInt(), TerminalScheme.DEBDROID.background)
        assertEquals(0xFFCFCFD6.toInt(), TerminalScheme.DEBDROID.foreground)
        assertEquals(0xFFE0103A.toInt(), TerminalScheme.DEBDROID.cursor)
        assertEquals(0xFF34D399.toInt(), TerminalScheme.DEBDROID.color(2))
        assertEquals(0xFF7AA2F7.toInt(), TerminalScheme.DEBDROID.color(4))
        assertEquals(0xFF6E6E7A.toInt(), TerminalScheme.DEBDROID.color(8))
        assertEquals(0xFF000000.toInt(), TerminalScheme.CONTRAST.background)
        assertEquals(0xFF00FF87.toInt(), TerminalScheme.CONTRAST.color(2))
        assertEquals(0xFFF7F5F2.toInt(), TerminalScheme.DAYLIGHT.background)
        assertEquals(0xFF0A6E43.toInt(), TerminalScheme.DAYLIGHT.color(2))
        assertEquals(0xFF14459B.toInt(), TerminalScheme.DAYLIGHT.color(4))
    }

    @Test
    fun everyTextColourIsLegibleOnItsBackground() {
        for (scheme in TerminalScheme.entries) {
            assertTrue("$scheme text", contrast(scheme.foreground, scheme.background) >= 7.0)
            // 0 is black, used as a background far more than as text
            for (i in 1 until 16) {
                val ratio = contrast(scheme.color(i), scheme.background)
                val minimum = if (i == 8) 3.0 else 4.5
                assertTrue("$scheme colour $i: $ratio", ratio >= minimum)
            }
        }
    }

    @Test
    fun daylightWhiteIsDarkAsTextAndLightAsBackground() {
        val s = TerminalScheme.DAYLIGHT
        assertTrue(luminance(s.color(7)) < 0.2)
        assertTrue(luminance(s.color(15)) < 0.2)
        assertTrue(luminance(s.colorOnColour(7)) > 0.6)
        assertTrue(contrast(s.colorOnColour(0), s.colorOnColour(7)) >= 7.0)
    }

    @Test
    fun theCubeAndGreyRamp() {
        val s = TerminalScheme.DEBDROID
        assertEquals(0xFF000000.toInt(), s.color(16))
        assertEquals(0xFFFF0000.toInt(), s.color(196))
        assertEquals(0xFFFFFFFF.toInt(), s.color(231))
        assertEquals(0xFF080808.toInt(), s.color(232))
        assertEquals(0xFFEEEEEE.toInt(), s.color(255))
    }
}
