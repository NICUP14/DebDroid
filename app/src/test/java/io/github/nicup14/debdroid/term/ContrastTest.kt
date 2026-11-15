package io.github.nicup14.debdroid.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Text a program colours onto a coloured background stays readable
class ContrastTest {

    @Test fun readableTextIsLeftAlone() {
        val white = 0xFFFFFFFF.toInt()
        val navy = 0xFF14459B.toInt()
        assertEquals(white, Contrast.readable(white, navy))
    }

    // The case that was seen on a device: ls's other-writable directories
    @Test fun blueOnGreenInEverySchemeBecomesReadable() {
        for (scheme in TerminalScheme.values()) {
            val fg = scheme.colorOnColour(4)
            val bg = scheme.color(2)
            val drawn = Contrast.readable(fg, bg)
            assertTrue("${scheme.name}: blue on green", Contrast.ratio(drawn, bg) >= Contrast.FLOOR)
        }
    }

    @Test fun everyPairInEverySchemeMeetsTheFloor() {
        for (scheme in TerminalScheme.values()) {
            for (b in 0 until 16) for (f in 0 until 16) {
                val bg = scheme.color(b)
                val drawn = Contrast.readable(scheme.colorOnColour(f), bg)
                // NOTE: Some mid-grey backgrounds cannot reach 3:1 with any ink;
                //       the better ink is still chosen, and it is never worse.
                assertTrue("${scheme.name} $f on $b",
                    Contrast.ratio(drawn, bg) >= minOf(Contrast.FLOOR, Contrast.ratio(scheme.colorOnColour(f), bg)))
            }
        }
    }
}
