package io.github.nicup14.debdroid.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The extra key row's latches
class ModifiersTest {

    @Test
    fun aLatchAppliesToOneKeyOnly() {
        val m = Modifiers()
        m.ctrl = true
        assertEquals(KeyEncoder.CTRL, m.consume())
        assertFalse(m.ctrl)
        assertEquals(0, m.consume())
    }

    @Test
    fun bothLatchesCombine() {
        val m = Modifiers()
        m.ctrl = true
        m.alt = true
        assertEquals(KeyEncoder.CTRL or KeyEncoder.ALT, m.consume())
        assertFalse(m.ctrl)
        assertFalse(m.alt)
    }

    @Test
    fun toggling() {
        val m = Modifiers()
        m.toggleCtrl()
        assertTrue(m.ctrl)
        m.toggleCtrl()
        assertFalse(m.ctrl)
        m.toggleAlt()
        assertEquals(KeyEncoder.ALT, m.consume())
    }

    @Test
    fun onChangeFiresOnlyOnChanges() {
        val m = Modifiers()
        var changes = 0
        m.onChange = { changes++ }
        m.ctrl = true
        m.ctrl = true
        assertEquals(1, changes)
        m.alt = true
        assertEquals(2, changes)
        m.consume()
        assertEquals(3, changes)
        m.consume()
        assertEquals(3, changes)
    }

    @Test
    fun aConsumedLatchEncodesTheKey() {
        val m = Modifiers()
        m.ctrl = true
        assertEquals(listOf(0x03.toByte()), KeyEncoder.char('c'.code, m.consume()).toList())
        assertEquals(listOf('c'.code.toByte()), KeyEncoder.char('c'.code, m.consume()).toList())
    }
}
