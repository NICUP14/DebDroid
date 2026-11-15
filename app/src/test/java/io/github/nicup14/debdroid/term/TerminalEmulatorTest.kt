package io.github.nicup14.debdroid.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

// The emulator against the sequences real programs send
class TerminalEmulatorTest {

    private val esc = "\u001b"

    private fun emulator(columns: Int = 10, rows: Int = 5, scrollback: Int = 100) =
        TerminalEmulator(columns, rows, scrollback)

    private fun TerminalEmulator.feed(text: String): TerminalEmulator {
        append(text)
        return this
    }

    private fun lines(emulator: TerminalEmulator): List<String> = emulator.screenText().split("\n")

    private fun assertCursor(emulator: TerminalEmulator, x: Int, y: Int) {
        assertEquals("cursor x", x, emulator.cursorX)
        assertEquals("cursor y", y, emulator.cursorY)
    }

    // ---- Cursor movement -------------------------------------------------

    @Test
    fun cupMovesTheCursorOneBased() {
        val e = emulator().feed("$esc[3;4H")
        assertCursor(e, 3, 2)
        e.feed("$esc[H")
        assertCursor(e, 0, 0)
        e.feed("$esc[2;2f")
        assertCursor(e, 1, 1)
    }

    @Test
    fun cursorMovementClampsToTheScreen() {
        val e = emulator().feed("$esc[100;100H")
        assertCursor(e, 9, 4)
        e.feed("$esc[50A")
        assertCursor(e, 9, 0)
        e.feed("$esc[50D")
        assertCursor(e, 0, 0)
        e.feed("$esc[3B$esc[4C")
        assertCursor(e, 4, 3)
        e.feed("$esc[0A")
        assertCursor(e, 4, 2)
    }

    @Test
    fun absoluteAndLineMovement() {
        val e = emulator().feed("$esc[5G")
        assertCursor(e, 4, 0)
        e.feed("$esc[4d")
        assertCursor(e, 4, 3)
        e.feed("$esc[2F")
        assertCursor(e, 0, 1)
        e.feed("$esc[7`$esc[E")
        assertCursor(e, 0, 2)
    }

    @Test
    fun backspaceAndCarriageReturn() {
        val e = emulator().feed("abc\bX\rY")
        assertEquals("YbX", lines(e)[0])
        assertCursor(e, 1, 0)
    }

    @Test
    fun originModeIsRelativeToTheRegion() {
        val e = emulator().feed("$esc[2;4r$esc[?6h")
        assertCursor(e, 0, 1)
        e.feed("$esc[10;1H")
        assertCursor(e, 0, 3)
        val responses = mutableListOf<String>()
        e.onResponse = { responses.add(String(it, StandardCharsets.UTF_8)) }
        e.feed("$esc[6n")
        assertEquals("$esc[3;1R", responses.single())
    }

    // ---- Wrapping --------------------------------------------------------

    @Test
    fun autowrapWaitsInTheLastColumn() {
        val e = emulator(5, 3).feed("abcde")
        assertCursor(e, 4, 0)
        assertEquals("abcde", lines(e)[0])
        assertEquals("", lines(e)[1])
        e.feed("f")
        assertEquals("f", lines(e)[1])
        assertCursor(e, 1, 1)
    }

    @Test
    fun carriageReturnCancelsThePendingWrap() {
        val e = emulator(5, 3).feed("abcde\rX")
        assertEquals("Xbcde", lines(e)[0])
        assertEquals("", lines(e)[1])
    }

    @Test
    fun withoutAutowrapTheLastColumnIsOverwritten() {
        val e = emulator(5, 3).feed("$esc[?7labcdefg")
        assertEquals("abcdg", lines(e)[0])
        assertCursor(e, 4, 0)
    }

    @Test
    fun cursorMovementCancelsThePendingWrap() {
        val e = emulator(5, 3).feed("abcde$esc[DX")
        assertEquals("abcXe", lines(e)[0])
    }

    // ---- Line feed and scrollback ----------------------------------------

    @Test
    fun lineFeedAtTheBottomScrollsIntoTheScrollback() {
        val e = emulator(10, 3).feed("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("2", "3", "4"), lines(e))
        assertEquals(1, e.scrollbackRows)
        assertEquals("1", e.row(-1).toText())
        assertEquals("1\n2\n3\n4", e.transcript())
    }

    @Test
    fun scrollbackIsBounded() {
        val e = emulator(10, 2, scrollback = 3)
        for (i in 1..10) e.feed("$i\r\n")
        assertEquals(3, e.scrollbackRows)
        assertEquals("7", e.row(-3).toText())
        e.setScrollback(1)
        assertEquals(1, e.scrollbackRows)
        assertEquals("9", e.row(-1).toText())
    }

    @Test
    fun linefeedModeAlsoReturns() {
        val e = emulator().feed("$esc[20hab\ncd")
        assertEquals(listOf("ab", "cd"), lines(e).take(2))
    }

    @Test
    fun reverseIndexAtTheTopScrollsDown() {
        val e = emulator(10, 3).feed("a\r\nb\r\nc$esc[H${esc}M")
        assertEquals(listOf("", "a", "b"), lines(e))
        assertEquals(0, e.scrollbackRows)
    }

    @Test
    fun nextLineAndIndex() {
        val e = emulator(10, 3).feed("ab${esc}Ecd${esc}De")
        assertEquals(listOf("ab", "cd", "  e"), lines(e))
    }

    // ---- Scroll regions --------------------------------------------------

    private fun abcde(): TerminalEmulator = emulator(10, 5).feed("a\r\nb\r\nc\r\nd\r\ne")

    @Test
    fun insertLineInsideTheRegion() {
        val e = abcde().feed("$esc[2;4r$esc[2;1H$esc[L")
        assertEquals(listOf("a", "", "b", "c", "e"), lines(e))
    }

    @Test
    fun deleteLineInsideTheRegion() {
        val e = abcde().feed("$esc[2;4r$esc[2;1H$esc[M")
        assertEquals(listOf("a", "c", "d", "", "e"), lines(e))
    }

    @Test
    fun insertAndDeleteLinesOutsideTheRegionDoNothing() {
        val e = abcde().feed("$esc[2;4r$esc[5;1H$esc[L$esc[M")
        assertEquals(listOf("a", "b", "c", "d", "e"), lines(e))
    }

    @Test
    fun lineFeedScrollsOnlyTheRegion() {
        val e = abcde().feed("$esc[2;4r$esc[4;1H\nX")
        assertEquals(listOf("a", "c", "d", "X", "e"), lines(e))
        // NOTE: A region below the top of the screen feeds no scrollback.
        assertEquals(0, e.scrollbackRows)
    }

    @Test
    fun aRegionFromTheTopFeedsTheScrollback() {
        // As apt does: everything but a progress bar on the last line scrolls
        val e = abcde().feed("$esc[1;4r$esc[4;1H\nX")
        assertEquals(listOf("b", "c", "d", "X", "e"), lines(e))
        assertEquals(1, e.scrollbackRows)
        assertEquals("a", e.row(-1).toText())
    }

    @Test
    fun scrollUpAndDown() {
        val e = abcde().feed("$esc[2S")
        assertEquals(listOf("c", "d", "e", "", ""), lines(e))
        e.feed("$esc[T")
        assertEquals(listOf("", "c", "d", "e", ""), lines(e))
    }

    @Test
    fun invalidRegionIsIgnored() {
        val e = abcde().feed("$esc[3;3r$esc[5;1H\n")
        assertEquals(listOf("b", "c", "d", "e", ""), lines(e))
    }

    // ---- Erasing and editing ---------------------------------------------

    private fun filled(): TerminalEmulator = emulator(5, 3).feed("abcde\r\nfghij\r\nklmno")

    @Test
    fun eraseInDisplayBelow() {
        val e = filled().feed("$esc[2;3H$esc[J")
        assertEquals(listOf("abcde", "fg", ""), lines(e))
    }

    @Test
    fun eraseInDisplayAbove() {
        val e = filled().feed("$esc[2;3H$esc[1J")
        assertEquals(listOf("", "   ij", "klmno"), lines(e))
    }

    @Test
    fun eraseInDisplayAllAndScrollback() {
        val e = emulator(5, 2).feed("1\r\n2\r\n3")
        assertEquals(1, e.scrollbackRows)
        e.feed("$esc[2J")
        assertEquals(listOf("", ""), lines(e))
        assertEquals(1, e.scrollbackRows)
        e.feed("$esc[3J")
        assertEquals(0, e.scrollbackRows)
    }

    @Test
    fun eraseInLineVariants() {
        val e = filled().feed("$esc[1;3H$esc[K")
        assertEquals("ab", lines(e)[0])
        e.feed("$esc[2;3H$esc[1K")
        assertEquals("   ij", lines(e)[1])
        e.feed("$esc[3;3H$esc[2K")
        assertEquals("", lines(e)[2])
    }

    @Test
    fun eraseTakesTheCurrentBackground() {
        val e = emulator(5, 2).feed("$esc[44m$esc[2K")
        assertEquals(4, TextStyle.background(e.styleAt(0, 0)))
        assertEquals(TextStyle.COLOR_DEFAULT_FG, TextStyle.foreground(e.styleAt(0, 0)))
    }

    @Test
    fun insertDeleteAndEraseCharacters() {
        val e = filled().feed("$esc[1;2H$esc[2@")
        assertEquals("a  bc", lines(e)[0])
        e.feed("$esc[2;2H$esc[2P")
        assertEquals("fij", lines(e)[1])
        e.feed("$esc[3;2H$esc[2X")
        assertEquals("k  no", lines(e)[2])
        assertCursor(e, 1, 2)
    }

    @Test
    fun insertModeShiftsText() {
        val e = emulator().feed("abc$esc[2G$esc[4hX$esc[4lY")
        assertEquals("aXYc", lines(e)[0])
    }

    @Test
    fun repeatPrecedingCharacter() {
        val e = emulator().feed("a$esc[3b")
        assertEquals("aaaa", lines(e)[0])
    }

    @Test
    fun screenAlignmentFillsWithE() {
        val e = emulator(3, 2).feed("$esc#8")
        assertEquals(listOf("EEE", "EEE"), lines(e))
    }

    // ---- SGR -------------------------------------------------------------

    @Test
    fun basicAttributesAndColours() {
        val e = emulator().feed("$esc[1;4;31;42mX")
        val style = e.styleAt(0, 0)
        assertEquals(1, TextStyle.foreground(style))
        assertEquals(2, TextStyle.background(style))
        assertEquals(TextStyle.BOLD or TextStyle.UNDERLINE, TextStyle.effects(style))
    }

    @Test
    fun brightColours() {
        val e = emulator().feed("$esc[91;104mX")
        assertEquals(9, TextStyle.foreground(e.styleAt(0, 0)))
        assertEquals(12, TextStyle.background(e.styleAt(0, 0)))
    }

    @Test
    fun resetsOfIndividualAttributes() {
        val e = emulator().feed("$esc[1;2;3;4;7;9;8mA$esc[22;23;24;27;29;28mB$esc[33;45m$esc[39;49mC")
        assertEquals(
            TextStyle.BOLD or TextStyle.DIM or TextStyle.ITALIC or TextStyle.UNDERLINE or
                TextStyle.INVERSE or TextStyle.STRIKETHROUGH or TextStyle.HIDDEN,
            TextStyle.effects(e.styleAt(0, 0)),
        )
        assertEquals(0, TextStyle.effects(e.styleAt(1, 0)))
        assertEquals(TextStyle.DEFAULT, e.styleAt(2, 0))
    }

    @Test
    fun resetWithEmptyAndZero() {
        val e = emulator().feed("$esc[1;31mA$esc[mB$esc[1;31mC$esc[0mD")
        assertEquals(TextStyle.DEFAULT, e.styleAt(1, 0))
        assertEquals(TextStyle.DEFAULT, e.styleAt(3, 0))
    }

    @Test
    fun indexedColours() {
        val e = emulator().feed("$esc[38;5;200;48;5;17mX")
        assertEquals(200, TextStyle.foreground(e.styleAt(0, 0)))
        assertEquals(17, TextStyle.background(e.styleAt(0, 0)))
    }

    @Test
    fun truecolour() {
        val e = emulator().feed("$esc[38;2;10;20;30;48;2;255;128;0;1mX")
        val style = e.styleAt(0, 0)
        assertEquals(TextStyle.rgb(10, 20, 30), TextStyle.foreground(style))
        assertTrue(TextStyle.isRgb(TextStyle.foreground(style)))
        assertEquals(TextStyle.rgb(255, 128, 0), TextStyle.background(style))
        assertEquals(TextStyle.BOLD, TextStyle.effects(style))
    }

    @Test
    fun colonFormsOfExtendedColours() {
        val e = emulator().feed("$esc[38:2::10:20:30;48:5:17;4mX$esc[38:2:1:2:3mY")
        val style = e.styleAt(0, 0)
        assertEquals(TextStyle.rgb(10, 20, 30), TextStyle.foreground(style))
        assertEquals(17, TextStyle.background(style))
        assertEquals(TextStyle.UNDERLINE, TextStyle.effects(style))
        assertEquals(TextStyle.rgb(1, 2, 3), TextStyle.foreground(e.styleAt(1, 0)))
    }

    @Test
    fun underlineColourIsSkipped() {
        val e = emulator().feed("$esc[58;5;196;1mX$esc[0;58:2::1:2:3;3mY")
        assertEquals(TextStyle.BOLD, TextStyle.effects(e.styleAt(0, 0)))
        assertEquals(TextStyle.COLOR_DEFAULT_FG, TextStyle.foreground(e.styleAt(0, 0)))
        assertEquals(TextStyle.ITALIC, TextStyle.effects(e.styleAt(1, 0)))
    }

    @Test
    fun colonUnderlineOff() {
        val e = emulator().feed("$esc[4mA$esc[4:0mB$esc[4:3mC")
        assertEquals(TextStyle.UNDERLINE, TextStyle.effects(e.styleAt(0, 0)))
        assertEquals(0, TextStyle.effects(e.styleAt(1, 0)))
        assertEquals(TextStyle.UNDERLINE, TextStyle.effects(e.styleAt(2, 0)))
    }

    // ---- Alternate screen ------------------------------------------------

    @Test
    fun alternateScreen1049RoundTrip() {
        val e = emulator(10, 3).feed("hello$esc[1;31m")
        e.feed("$esc[?1049h")
        assertTrue(e.isAlternateScreen)
        assertEquals(listOf("", "", ""), lines(e))
        e.feed("${esc}[0m${esc}[Hvim\r\n1\r\n2\r\n3\r\n4")
        assertEquals(0, e.scrollbackRows)
        e.feed("$esc[?1049l")
        assertFalse(e.isAlternateScreen)
        assertEquals(listOf("hello", "", ""), lines(e))
        assertCursor(e, 5, 0)
        e.feed("X")
        assertEquals(1, TextStyle.foreground(e.styleAt(5, 0)))
        assertEquals(0, e.scrollbackRows)
        assertEquals("helloX", e.transcript())
    }

    @Test
    fun alternateScreen47KeepsItsContent() {
        val e = emulator(10, 2).feed("main$esc[?47hALT$esc[?47l")
        assertEquals("main", lines(e)[0])
        e.feed("$esc[?47h")
        assertEquals("    ALT", lines(e)[0])
        e.feed("$esc[?1047l$esc[?47h")
        assertEquals("", lines(e)[0])
    }

    // ---- Unicode ---------------------------------------------------------

    @Test
    fun utf8SplitAcrossAppends() {
        val e = emulator()
        val bytes = "é€😀".toByteArray(StandardCharsets.UTF_8)
        for (b in bytes) e.append(byteArrayOf(b))
        assertEquals("é€😀", lines(e)[0])
        assertCursor(e, 4, 0)
    }

    @Test
    fun utf8OffsetAndLength() {
        val e = emulator()
        val bytes = "xxé€yy".toByteArray(StandardCharsets.UTF_8)
        e.append(bytes, 2, 3)
        e.append(bytes, 5, bytes.size - 7)
        assertEquals("é€", lines(e)[0])
    }

    @Test
    fun invalidUtf8BecomesReplacementCharacters() {
        val e = emulator()
        e.append(byteArrayOf(0x61, 0xFF.toByte(), 0x62, 0xC3.toByte(), 0x63, 0xC0.toByte(), 0x80.toByte(), 0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
        assertEquals("a�b�c��", lines(e)[0].substring(0, 7))
    }

    @Test
    fun escapeInterruptsAPartialSequence() {
        val e = emulator()
        e.append(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0x1b, '['.code.toByte(), '2'.code.toByte(), 'C'.code.toByte(), 'x'.code.toByte()))
        assertEquals("�  x", lines(e)[0])
    }

    @Test
    fun wideCharactersTakeTwoCells() {
        val e = emulator().feed("a中b")
        assertEquals("a中b", lines(e)[0])
        assertCursor(e, 4, 0)
        assertTrue(e.row(0).isWideHead(1))
        assertTrue(e.row(0).isWideTail(2))
        assertEquals("", e.cellText(2, 0))
    }

    @Test
    fun wideCharacterWrapsFromTheLastColumn() {
        val e = emulator(5, 3).feed("abcd中")
        assertEquals(listOf("abcd", "中", ""), lines(e))
        assertTrue(e.row(0).wrapped)
        assertEquals("abcd 中", e.transcript())
    }

    @Test
    fun overwritingHalfAWideCharacterBlanksTheOtherHalf() {
        val e = emulator().feed("中$esc[2Gx")
        assertEquals(" x", lines(e)[0])
        e.feed("$esc[1;5H中$esc[1;5Hy")
        assertEquals(" x  y", lines(e)[0])
    }

    @Test
    fun combiningMarksDoNotAdvance() {
        val e = emulator().feed("éx")
        assertEquals("éx", lines(e)[0])
        assertCursor(e, 2, 0)
        assertEquals("é", e.cellText(0, 0))
    }

    @Test
    fun emojiAreWide() {
        val e = emulator().feed("😀!")
        assertCursor(e, 3, 0)
    }

    // ---- Character sets --------------------------------------------------

    @Test
    fun decLineDrawing() {
        val e = emulator().feed("$esc(0lqqk$esc(Bq")
        assertEquals("┌──┐q", lines(e)[0])
    }

    @Test
    fun shiftOutUsesG1() {
        val e = emulator().feed("$esc)0x\u000ex\u000fx")
        assertEquals("x│x", lines(e)[0])
    }

    @Test
    fun lineDrawingSurvivesRepeat() {
        val e = emulator().feed("$esc(0q$esc[3b")
        assertEquals("────", lines(e)[0])
    }

    // ---- Strings ---------------------------------------------------------

    @Test
    fun oscTitleWithBel() {
        val e = emulator()
        val titles = mutableListOf<String>()
        e.onTitle = { titles.add(it) }
        e.feed("$esc]0;hello world\u0007after")
        assertEquals(listOf("hello world"), titles)
        assertEquals("after", lines(e)[0])
    }

    @Test
    fun oscTitleWithStSplitAcrossAppends() {
        val e = emulator()
        val titles = mutableListOf<String>()
        e.onTitle = { titles.add(it) }
        e.feed("$esc]2;déb")
        e.feed("ian$esc")
        e.feed("\\ok")
        assertEquals(listOf("débian"), titles)
        assertEquals("débian", e.title)
        assertEquals("ok", lines(e)[0])
    }

    @Test
    fun otherOscAndStringsAreIgnored() {
        val e = emulator()
        val titles = mutableListOf<String>()
        e.onTitle = { titles.add(it) }
        e.feed("$esc]11;?\u0007A$esc]52;c;aGVsbG8=$esc\\B${esc}P1\$r0m$esc\\C${esc}_app${esc}\\D")
        assertTrue(titles.isEmpty())
        assertEquals("ABCD", lines(e)[0])
    }

    @Test
    fun dcsWithEmbeddedEscapesStaysIgnored() {
        val e = emulator().feed("${esc}Ptmux;$esc$esc[31mX$esc\\Y")
        assertEquals("Y", lines(e)[0])
        assertEquals(TextStyle.DEFAULT, e.styleAt(0, 0))
    }

    // ---- Reports ---------------------------------------------------------

    @Test
    fun cursorPositionReport() {
        val e = emulator()
        val responses = mutableListOf<String>()
        e.onResponse = { responses.add(String(it, StandardCharsets.UTF_8)) }
        e.feed("$esc[3;4H$esc[6n$esc[5n")
        assertEquals(listOf("$esc[3;4R", "$esc[0n"), responses)
    }

    @Test
    fun deviceAttributes() {
        val e = emulator()
        val responses = mutableListOf<String>()
        e.onResponse = { responses.add(String(it, StandardCharsets.UTF_8)) }
        e.feed("$esc[c$esc[>c$esc[18t")
        assertEquals("$esc[?62;22c", responses[0])
        assertTrue(responses[1].startsWith("$esc[>"))
        assertEquals("$esc[8;5;10t", responses[2])
    }

    @Test
    fun modeReport() {
        val e = emulator()
        val responses = mutableListOf<String>()
        e.onResponse = { responses.add(String(it, StandardCharsets.UTF_8)) }
        e.feed("$esc[?2004h$esc[?2004\$p$esc[?1\$p$esc[?7777\$p")
        assertEquals(listOf("$esc[?2004;1\$y", "$esc[?1;2\$y", "$esc[?7777;0\$y"), responses)
    }

    // ---- Modes -----------------------------------------------------------

    @Test
    fun modeFlags() {
        val e = emulator()
        assertFalse(e.applicationCursorKeys)
        e.feed("$esc[?1h$esc[?2004h$esc[?25l$esc=")
        assertTrue(e.applicationCursorKeys)
        assertTrue(e.bracketedPaste)
        assertFalse(e.cursorVisible)
        assertTrue(e.applicationKeypad)
        e.feed("$esc[?1l$esc[?2004l$esc[?25h$esc>$esc[?5h")
        assertFalse(e.applicationCursorKeys)
        assertFalse(e.bracketedPaste)
        assertTrue(e.cursorVisible)
        assertFalse(e.applicationKeypad)
        assertTrue(e.reverseVideo)
    }

    @Test
    fun acceptedModesDoNotPrint() {
        val e = emulator().feed("$esc[?1000h$esc[?1002;1006h$esc[?3h$esc[?12l$esc[2 q$esc[22;0;0tX")
        assertEquals("X", lines(e)[0])
    }

    @Test
    fun softResetKeepsTheScreen() {
        val e = emulator().feed("ab$esc[?25l$esc[1m$esc[2;3r$esc[!p$esc[1;3Hc")
        assertTrue(e.cursorVisible)
        assertEquals("abc", lines(e)[0])
        assertEquals(TextStyle.DEFAULT, e.styleAt(2, 0))
    }

    @Test
    fun fullResetClearsTheScreenButNotTheScrollback() {
        val e = emulator(5, 2).feed("1\r\n2\r\n3$esc[?1h${esc}c")
        assertEquals(listOf("", ""), lines(e))
        assertCursor(e, 0, 0)
        assertFalse(e.applicationCursorKeys)
        assertEquals(1, e.scrollbackRows)
    }

    // ---- Save and restore ------------------------------------------------

    @Test
    fun saveAndRestoreCursorWithAttributes() {
        val e = emulator().feed("$esc[2;3H$esc[32m${esc}7$esc[H$esc[0mA${esc}8B")
        assertCursor(e, 3, 1)
        assertEquals(2, TextStyle.foreground(e.styleAt(2, 1)))
        assertEquals(TextStyle.DEFAULT, e.styleAt(0, 0))
        e.feed("$esc[4;4H$esc[s$esc[H$esc[u")
        assertCursor(e, 3, 3)
    }

    @Test
    fun saveAndRestoreCharset() {
        val e = emulator().feed("$esc(0${esc}7$esc(B$esc[5Gq${esc}8q")
        assertEquals("─   q", lines(e)[0])
    }

    // ---- Tabs ------------------------------------------------------------

    @Test
    fun tabStopsEveryEight() {
        val e = emulator(20, 2).feed("\tX\tY\tZ")
        assertEquals("        X       Y  Z", lines(e)[0])
    }

    @Test
    fun setClearAndBackTabs() {
        val e = emulator(20, 2).feed("$esc[4G${esc}H\r\tA")
        assertEquals("   A", lines(e)[0])
        // TBC 0 clears the stop under the cursor only
        e.feed("\r\t$esc[g\r\tB")
        assertEquals("   A    B", lines(e)[0])
        // TBC 3 clears them all, and a tab then goes to the last column
        e.feed("$esc[3g\r\tC")
        assertCursor(e, 19, 0)
        e.feed("\r$esc[2I")
        assertCursor(e, 19, 0)
        e.feed("$esc[18G${esc}H$esc[Z")
        assertCursor(e, 0, 0)
        e.feed("$esc[20G$esc[Z")
        assertCursor(e, 17, 0)
    }

    @Test
    fun forwardTabCount() {
        val e = emulator(30, 2).feed("$esc[2I")
        assertCursor(e, 16, 0)
        e.feed("$esc[Z")
        assertCursor(e, 8, 0)
    }

    // ---- Resize ----------------------------------------------------------

    @Test
    fun shrinkingKeepsTheCursorLine() {
        val e = emulator(10, 5).feed("1\r\n2\r\n3\r\n4\r\n5")
        e.resize(10, 3)
        assertEquals(listOf("3", "4", "5"), lines(e))
        assertCursor(e, 1, 2)
        assertEquals(2, e.scrollbackRows)
        e.resize(10, 5)
        assertEquals(listOf("1", "2", "3", "4", "5"), lines(e))
        assertCursor(e, 1, 4)
        assertEquals(0, e.scrollbackRows)
    }

    @Test
    fun shrinkingDropsBlankLinesBelowTheCursorFirst() {
        val e = emulator(10, 5).feed("prompt")
        e.resize(10, 2)
        assertEquals(listOf("prompt", ""), lines(e))
        assertCursor(e, 6, 0)
        assertEquals(0, e.scrollbackRows)
    }

    @Test
    fun narrowingTruncatesAndClamps() {
        val e = emulator(10, 3).feed("abcdefghij")
        e.resize(4, 3)
        assertEquals("abcd", lines(e)[0])
        assertCursor(e, 3, 0)
        e.feed("$esc[1;1H$esc[3IX")
        assertCursor(e, 3, 0)
        e.resize(20, 3)
        assertEquals(20, e.columns)
        e.feed("\r\n\tY")
        assertEquals("        Y", lines(e)[1])
    }

    @Test
    fun resizeResetsTheScrollRegion() {
        val e = emulator(10, 5).feed("$esc[2;3r")
        e.resize(10, 6)
        e.feed("$esc[6;1H\nX")
        assertEquals("X", lines(e)[5])
    }

    @Test
    fun resizeCutsAWideCharacterCleanly() {
        val e = emulator(10, 2).feed("abc中")
        e.resize(4, 2)
        assertEquals("abc", lines(e)[0])
    }

    @Test
    fun resizeWhileOnTheAlternateScreen() {
        val e = emulator(10, 4).feed("1\r\n2\r\n3\r\n4$esc[?1049h")
        e.resize(8, 2)
        assertTrue(e.isAlternateScreen)
        e.feed("$esc[?1049l")
        assertEquals(listOf("3", "4"), lines(e))
        assertCursor(e, 1, 1)
    }

    // ---- Transcript ------------------------------------------------------

    @Test
    fun transcriptJoinsWrappedLines() {
        val e = emulator(5, 3).feed("abcdefgh\r\nxy\r\n\r\n\r\nlast")
        assertEquals("abcdefgh\nxy\n\n\nlast", e.transcript())
    }

    @Test
    fun transcriptTrimsTrailingBlankLines() {
        val e = emulator(5, 4).feed("one  \r\ntwo")
        assertEquals("one\ntwo", e.transcript())
        assertEquals("one\ntwo\n\n", e.screenText())
    }

    // ---- Robustness ------------------------------------------------------

    @Test
    fun hugeParametersDoNotCrash() {
        val e = emulator()
        e.feed("$esc[99999999999;99999999999H$esc[99999999L$esc[99999999@$esc[99999999P$esc[99999999b")
        e.feed("$esc[" + "1;".repeat(100) + "m")
        e.feed("$esc[38;5m$esc[38;2;1m$esc[48:2m")
        e.feed("ok")
        assertTrue(e.screenText().contains("ok"))
    }

    @Test
    fun cancelAbortsASequence() {
        val e = emulator().feed("$esc[31\u0018x")
        assertEquals("x", lines(e)[0])
        assertEquals(TextStyle.DEFAULT, e.styleAt(0, 0))
    }

    @Test
    fun controlInsideCsiIsExecuted() {
        val e = emulator().feed("ab$esc[\r2Cx")
        assertEquals("ab", lines(e)[0].substring(0, 2))
        assertCursor(e, 3, 0)
    }

    @Test
    fun bellCallsBack() {
        val e = emulator()
        var bells = 0
        e.onBell = { bells++ }
        e.feed("\u0007a\u0007")
        assertEquals(2, bells)
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = java.util.Random(42)
        val e = emulator(13, 7, scrollback = 20)
        val alphabet = "\u001b[];?0123456789;:mHJKLMPXr@hl\r\n\t\b\u000e\u000fabc中é(0)#8cP\\\u0007"
        repeat(2000) {
            val chunk = StringBuilder()
            repeat(random.nextInt(40)) { chunk.append(alphabet[random.nextInt(alphabet.length)]) }
            e.append(chunk.toString())
            if (random.nextInt(50) == 0) e.resize(1 + random.nextInt(20), 1 + random.nextInt(10))
            val bytes = ByteArray(random.nextInt(8)).also { random.nextBytes(it) }
            e.append(bytes)
        }
        e.transcript()
        e.screenText()
    }
}
