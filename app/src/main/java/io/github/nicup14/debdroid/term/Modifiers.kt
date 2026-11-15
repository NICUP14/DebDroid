package io.github.nicup14.debdroid.term

// Latching modifiers for the on-screen extra key row
//
// Tapping CTRL arms it for the next key, and taking that key clears it again,
// so a phone keyboard with no Ctrl can still send Ctrl+C. onChange fires
// whenever a latch changes, for the buttons to redraw their state.
class Modifiers {
    var onChange: () -> Unit = {}

    private var ctrlLatched = false
    private var altLatched = false

    var ctrl: Boolean
        get() = ctrlLatched
        set(value) {
            if (ctrlLatched != value) {
                ctrlLatched = value
                onChange()
            }
        }

    var alt: Boolean
        get() = altLatched
        set(value) {
            if (altLatched != value) {
                altLatched = value
                onChange()
            }
        }

    fun toggleCtrl() {
        ctrl = !ctrl
    }

    fun toggleAlt() {
        alt = !alt
    }

    // The KeyEncoder bits for the armed latches, clearing them
    fun consume(): Int {
        var mods = 0
        if (ctrlLatched) mods = mods or KeyEncoder.CTRL
        if (altLatched) mods = mods or KeyEncoder.ALT
        if (mods != 0) {
            ctrlLatched = false
            altLatched = false
            onChange()
        }
        return mods
    }
}
