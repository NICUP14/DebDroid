package io.github.nicup14.debdroid.term

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

// Keys, characters and paste against the bytes xterm sends
class KeyEncoderTest {

    private fun text(bytes: ByteArray) = String(bytes, StandardCharsets.UTF_8)

    private fun key(key: TermKey, mods: Int = 0, app: Boolean = false) = text(KeyEncoder.key(key, mods, app))

    private fun char(c: Char, mods: Int = 0) = KeyEncoder.char(c.code, mods)

    @Test
    fun arrowsInNormalMode() {
        assertEquals("\u001b[A", key(TermKey.UP))
        assertEquals("\u001b[B", key(TermKey.DOWN))
        assertEquals("\u001b[C", key(TermKey.RIGHT))
        assertEquals("\u001b[D", key(TermKey.LEFT))
        assertEquals("\u001b[H", key(TermKey.HOME))
        assertEquals("\u001b[F", key(TermKey.END))
    }

    @Test
    fun arrowsInApplicationMode() {
        assertEquals("\u001bOA", key(TermKey.UP, app = true))
        assertEquals("\u001bOB", key(TermKey.DOWN, app = true))
        assertEquals("\u001bOC", key(TermKey.RIGHT, app = true))
        assertEquals("\u001bOD", key(TermKey.LEFT, app = true))
        assertEquals("\u001bOH", key(TermKey.HOME, app = true))
        assertEquals("\u001bOF", key(TermKey.END, app = true))
    }

    @Test
    fun modifiedArrowsIgnoreApplicationMode() {
        assertEquals("\u001b[1;5A", key(TermKey.UP, KeyEncoder.CTRL))
        assertEquals("\u001b[1;5A", key(TermKey.UP, KeyEncoder.CTRL, app = true))
        assertEquals("\u001b[1;2D", key(TermKey.LEFT, KeyEncoder.SHIFT))
        assertEquals("\u001b[1;3C", key(TermKey.RIGHT, KeyEncoder.ALT))
        assertEquals("\u001b[1;8B", key(TermKey.DOWN, KeyEncoder.CTRL or KeyEncoder.ALT or KeyEncoder.SHIFT))
        assertEquals("\u001b[1;5H", key(TermKey.HOME, KeyEncoder.CTRL))
    }

    @Test
    fun editingKeys() {
        assertEquals("\u001b[2~", key(TermKey.INSERT))
        assertEquals("\u001b[3~", key(TermKey.DELETE))
        assertEquals("\u001b[5~", key(TermKey.PAGE_UP))
        assertEquals("\u001b[6~", key(TermKey.PAGE_DOWN))
        assertEquals("\u001b[3;5~", key(TermKey.DELETE, KeyEncoder.CTRL))
        assertEquals("\u001b[6;2~", key(TermKey.PAGE_DOWN, KeyEncoder.SHIFT))
    }

    @Test
    fun functionKeys() {
        val expected = listOf(
            "\u001bOP", "\u001bOQ", "\u001bOR", "\u001bOS",
            "\u001b[15~", "\u001b[17~", "\u001b[18~", "\u001b[19~",
            "\u001b[20~", "\u001b[21~", "\u001b[23~", "\u001b[24~",
        )
        val keys = listOf(
            TermKey.F1, TermKey.F2, TermKey.F3, TermKey.F4, TermKey.F5, TermKey.F6,
            TermKey.F7, TermKey.F8, TermKey.F9, TermKey.F10, TermKey.F11, TermKey.F12,
        )
        assertEquals(expected, keys.map { key(it) })
        // Application mode does not change function keys
        assertEquals(expected, keys.map { key(it, app = true) })
    }

    @Test
    fun modifiedFunctionKeys() {
        assertEquals("\u001b[1;2P", key(TermKey.F1, KeyEncoder.SHIFT))
        assertEquals("\u001b[1;5S", key(TermKey.F4, KeyEncoder.CTRL))
        assertEquals("\u001b[15;5~", key(TermKey.F5, KeyEncoder.CTRL))
        assertEquals("\u001b[24;3~", key(TermKey.F12, KeyEncoder.ALT))
    }

    @Test
    fun plainKeys() {
        assertEquals("\u001b", key(TermKey.ESCAPE))
        assertEquals("\u001b\u001b", key(TermKey.ESCAPE, KeyEncoder.ALT))
        assertEquals("\t", key(TermKey.TAB))
        assertEquals("\u001b[Z", key(TermKey.TAB, KeyEncoder.SHIFT))
        assertEquals("\r", key(TermKey.ENTER))
        assertEquals("\u001b\r", key(TermKey.ENTER, KeyEncoder.ALT))
        assertEquals("\u007f", key(TermKey.BACKSPACE))
        assertEquals("\u0008", key(TermKey.BACKSPACE, KeyEncoder.CTRL))
        assertEquals("\u001b\u007f", key(TermKey.BACKSPACE, KeyEncoder.ALT))
    }

    @Test
    fun ctrlLetters() {
        assertArrayEquals(byteArrayOf(0x03), char('c', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x03), char('C', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x01), char('a', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x1a), char('z', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x04), char('d', KeyEncoder.CTRL or KeyEncoder.SHIFT))
    }

    @Test
    fun ctrlSymbols() {
        assertArrayEquals(byteArrayOf(0x00), char(' ', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x00), char('@', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x00), char('2', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x1b), char('[', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x1c), char('\\', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x1d), char(']', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x1e), char('^', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x1f), char('_', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x1f), char('/', KeyEncoder.CTRL))
        assertArrayEquals(byteArrayOf(0x7f), char('?', KeyEncoder.CTRL))
        // No control code: sent as typed
        assertArrayEquals(byteArrayOf('.'.code.toByte()), char('.', KeyEncoder.CTRL))
    }

    @Test
    fun altPrefixesEscape() {
        assertArrayEquals(byteArrayOf(0x1b, 'x'.code.toByte()), char('x', KeyEncoder.ALT))
        assertArrayEquals(byteArrayOf(0x1b, 0x18), char('x', KeyEncoder.ALT or KeyEncoder.CTRL))
    }

    @Test
    fun charactersAreUtf8() {
        assertArrayEquals("é".toByteArray(StandardCharsets.UTF_8), char('é'))
        assertArrayEquals("😀".toByteArray(StandardCharsets.UTF_8), KeyEncoder.char(0x1F600, 0))
        assertArrayEquals(byteArrayOf('a'.code.toByte()), char('a', KeyEncoder.SHIFT))
    }

    @Test
    fun pasteNormalisesLineEndings() {
        assertEquals("a\rb\rc\r", text(KeyEncoder.paste("a\r\nb\nc\n", false)))
        assertEquals("a\rb", text(KeyEncoder.paste("a\rb", false)))
    }

    @Test
    fun bracketedPasteWrapsAndStripsEscape() {
        val pasted = text(KeyEncoder.paste("ls\n\u001b[201~rm -rf /\r\n", true))
        assertEquals("\u001b[200~ls\r[201~rm -rf /\r\u001b[201~", pasted)
        assertTrue(pasted.startsWith("\u001b[200~"))
        assertFalse(pasted.substring(6, pasted.length - 6).contains("\u001b"))
    }

    @Test
    fun unbracketedPasteKeepsEscape() {
        assertEquals("a\u001bb", text(KeyEncoder.paste("a\u001bb", false)))
    }
}
