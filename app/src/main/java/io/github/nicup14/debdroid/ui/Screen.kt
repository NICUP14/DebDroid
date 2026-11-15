package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.os.Bundle
import android.view.View

// The base of every screen: the bar colours, and room for the system bars
//
// NOTE: An app targeting android 15 or later is drawn edge to edge whether it
//       asks to be or not, under the status and navigation bars. Fitting the
//       content to the system windows gives it back the room, and on older
//       releases, where the bars are opaque, it changes nothing.
open class Screen : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = Palette.GROUND
        @Suppress("DEPRECATION")
        window.navigationBarColor = Palette.BARS
    }

    override fun setContentView(view: View) {
        view.fitsSystemWindows = true
        super.setContentView(view)
    }
}
