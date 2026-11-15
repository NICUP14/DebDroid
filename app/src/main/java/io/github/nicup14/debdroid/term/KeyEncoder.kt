package io.github.nicup14.debdroid.term

import java.nio.charset.StandardCharsets

// The keys that send an escape sequence rather than a character
enum class TermKey {
    UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    ESCAPE, TAB, ENTER, BACKSPACE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

// Turns keys and typed characters into the bytes an xterm sends for them
//
// Pure Kotlin, so it is tested on the host. `mods` is a bit set of CTRL, ALT and SHIFT.
object KeyEncoder {
    const val CTRL = 1
    const val ALT = 2
    const val SHIFT = 4

    private const val ESC = "\u001b"

    fun key(key: TermKey, mods: Int, applicationCursor: Boolean): ByteArray {
        val alt = mods and ALT != 0
        val sequence = when (key) {
            TermKey.UP -> cursor('A', mods, applicationCursor)
            TermKey.DOWN -> cursor('B', mods, applicationCursor)
            TermKey.RIGHT -> cursor('C', mods, applicationCursor)
            TermKey.LEFT -> cursor('D', mods, applicationCursor)
            TermKey.HOME -> cursor('H', mods, applicationCursor)
            TermKey.END -> cursor('F', mods, applicationCursor)
            TermKey.INSERT -> tilde(2, mods)
            TermKey.DELETE -> tilde(3, mods)
            TermKey.PAGE_UP -> tilde(5, mods)
            TermKey.PAGE_DOWN -> tilde(6, mods)
            TermKey.F1 -> ss3('P', mods)
            TermKey.F2 -> ss3('Q', mods)
            TermKey.F3 -> ss3('R', mods)
            TermKey.F4 -> ss3('S', mods)
            TermKey.F5 -> tilde(15, mods)
            TermKey.F6 -> tilde(17, mods)
            TermKey.F7 -> tilde(18, mods)
            TermKey.F8 -> tilde(19, mods)
            TermKey.F9 -> tilde(20, mods)
            TermKey.F10 -> tilde(21, mods)
            TermKey.F11 -> tilde(23, mods)
            TermKey.F12 -> tilde(24, mods)
            TermKey.ESCAPE -> if (alt) ESC + ESC else ESC
            TermKey.TAB -> when {
                mods and SHIFT != 0 -> (if (alt) ESC else "") + "$ESC[Z"
                alt -> "$ESC\t"
                else -> "\t"
            }
            TermKey.ENTER -> if (alt) "$ESC\r" else "\r"
            // NOTE: Ctrl+Backspace sends ^H, which many programs bind to
            //       deleting a word, and Alt+Backspace is readline's
            //       backward-kill-word.
            TermKey.BACKSPACE -> (if (alt) ESC else "") + (if (mods and CTRL != 0) "\u0008" else "\u007f")
        }
        return sequence.toByteArray(StandardCharsets.UTF_8)
    }

    // xterm's modifier parameter: 1 plus shift 1, alt 2, ctrl 4
    private fun modifierParam(mods: Int): Int {
        var m = 1
        if (mods and SHIFT != 0) m += 1
        if (mods and ALT != 0) m += 2
        if (mods and CTRL != 0) m += 4
        return m
    }

    // Arrows, Home and End: CSI or SS3 depending on DECCKM, CSI 1;m with modifiers
    private fun cursor(final: Char, mods: Int, application: Boolean): String {
        val m = modifierParam(mods)
        return when {
            m != 1 -> "$ESC[1;$m$final"
            application -> "${ESC}O$final"
            else -> "$ESC[$final"
        }
    }

    // F1-F4: SS3 alone, CSI 1;m with modifiers
    private fun ss3(final: Char, mods: Int): String {
        val m = modifierParam(mods)
        return if (m == 1) "${ESC}O$final" else "$ESC[1;$m$final"
    }

    // The CSI n ~ keys, with ;m for modifiers
    private fun tilde(number: Int, mods: Int): String {
        val m = modifierParam(mods)
        return if (m == 1) "$ESC[$number~" else "$ESC[$number;$m~"
    }

    // A typed character: Ctrl maps it to its control code, Alt prefixes ESC, the rest is UTF-8
    fun char(codePoint: Int, mods: Int): ByteArray {
        var cp = codePoint
        if (mods and CTRL != 0) {
            val control = controlCode(cp)
            if (control >= 0) cp = control
        }
        val utf8 = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)
        if (mods and ALT == 0) return utf8
        val result = ByteArray(utf8.size + 1)
        result[0] = 0x1b
        System.arraycopy(utf8, 0, result, 1, utf8.size)
        return result
    }

    // The control code Ctrl+character sends, or -1 if it has none
    // NOTE: The digits follow xterm's VT220 mapping (Ctrl+2 is NUL, Ctrl+3 to
    //       Ctrl+7 are ESC to US, Ctrl+8 is DEL), which matters on a keyboard
    //       where [ or _ needs a modifier of its own.
    private fun controlCode(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 0x60
        in 'A'.code..'Z'.code -> cp - 0x40
        '@'.code, ' '.code, '2'.code -> 0
        '['.code, '3'.code -> 0x1b
        '\\'.code, '4'.code -> 0x1c
        ']'.code, '5'.code -> 0x1d
        '^'.code, '6'.code, '~'.code -> 0x1e
        '_'.code, '7'.code, '-'.code, '/'.code -> 0x1f
        '?'.code, '8'.code -> 0x7f
        else -> -1
    }

    // Pasted text: line endings become CR, and with bracketed paste it is wrapped and stripped of ESC
    // NOTE: Stripping ESC is what stops pasted text from ending the bracket
    //       early with its own ESC[201~ and having the rest run as typed.
    fun paste(text: String, bracketed: Boolean): ByteArray {
        var body = text.replace("\r\n", "\r").replace('\n', '\r')
        if (bracketed) body = "$ESC[200~" + body.replace(ESC, "") + "$ESC[201~"
        return body.toByteArray(StandardCharsets.UTF_8)
    }
}
