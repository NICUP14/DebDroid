package io.github.nicup14.debdroid.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.core.InstallAction
import io.github.nicup14.debdroid.ui.Ui.dp
import io.github.nicup14.debdroid.work.Tasks

// The three tabs: Home, Terminal, Manage
//
// Settings hangs off Home's gear, Commands and the editor off Manage, so
// nothing is more than two taps deep.
class MainActivity : Screen(), DeviceState.Listener, Tasks.Listener {

    interface Tab {
        val view: View
        fun refresh()
        fun onShown() {}
        fun onHidden() {}
        fun onBack(): Boolean = false
    }

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var navLine: View
    private val tabs = LinkedHashMap<String, Tab>()
    private var current = TAB_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOTE: The keyboard resizes the window rather than covering the
        //       terminal's input line and key row.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        content = FrameLayout(this)
        nav = Ui.horizontal(this).apply {
            setBackgroundColor(Palette.BARS)
            setPadding(0, dp(7), 0, dp(11))
        }
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Palette.GROUND)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            navLine = View(context).apply { setBackgroundColor(Palette.LINE) }
            addView(navLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(nav, Ui.matchWidth())
        }
        setContentView(root)

        tabs[TAB_HOME] = HomeTab(this)
        tabs[TAB_TERMINAL] = TerminalTab(this)
        tabs[TAB_MANAGE] = ManageTab(this)
        for (tab in tabs.values) {
            tab.view.visibility = View.GONE
            content.addView(tab.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        current = intent.getStringExtra(EXTRA_TAB) ?: savedInstanceState?.getString(EXTRA_TAB) ?: TAB_HOME
        val wanted = current
        current = TAB_HOME
        show(TAB_HOME)
        if (intent.hasExtra(TerminalTab.EXTRA_COMMAND)) tabs[TAB_TERMINAL]?.refresh() else show(wanted)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A command to run opens its own session, which then shows the tab
        if (intent.hasExtra(TerminalTab.EXTRA_COMMAND)) {
            tabs[TAB_TERMINAL]?.refresh()
            return
        }
        intent.getStringExtra(EXTRA_TAB)?.let { show(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TAB, current)
    }

    override fun onResume() {
        super.onResume()
        DeviceState.addListener(this)
        Tasks.addListener(this)
        DeviceState.refresh(this, full = true)
        onDeviceState()
    }

    override fun onPause() {
        super.onPause()
        DeviceState.removeListener(this)
        Tasks.removeListener(this)
    }

    override fun onDeviceState() {
        // NOTE: Anything other than a working install goes to the install
        //       screens, which decide the one action. A declined update stays
        //       declined until the app restarts.
        val action = DeviceState.action
        if (DeviceState.rootChecked && action != InstallAction.NONE &&
            !(DeviceState.updateDeclined && action in listOf(InstallAction.UPDATE, InstallAction.NEWER_INSTALLED,
                InstallAction.ENVIRONMENT_RUNNING)) &&
            Tasks.current?.running != true) {
            startActivity(Intent(this, InstallActivity::class.java))
            finish()
            return
        }
        for (tab in tabs.values) tab.refresh()
        buildNav()
    }

    override fun onTasksChanged() {
        for (tab in tabs.values) tab.refresh()
    }

    // Switches to a tab
    fun show(name: String) {
        if (name == TAB_TERMINAL && !terminalAvailable()) return
        tabs[current]?.let {
            it.view.visibility = View.GONE
            it.onHidden()
        }
        current = name
        // NOTE: The terminal has no tab bar, as its board draws it: its back
        //       arrow returns home, and the rows go to the session instead.
        val bar = if (name == TAB_TERMINAL) View.GONE else View.VISIBLE
        nav.visibility = bar
        navLine.visibility = bar
        tabs[name]?.let {
            it.view.visibility = View.VISIBLE
            it.refresh()
            it.onShown()
        }
        buildNav()
    }

    // The terminal needs something to attach to
    fun terminalAvailable() = DeviceState.probe?.running == true ||
        io.github.nicup14.debdroid.service.SessionService.sessions.isNotEmpty()

    private fun buildNav() {
        nav.removeAllViews()
        navItem(TAB_HOME, "Home", R.drawable.ic_home, true)
        navItem(TAB_TERMINAL, "Terminal", R.drawable.ic_terminal, terminalAvailable())
        navItem(TAB_MANAGE, "Manage", R.drawable.ic_manage, true)
    }

    private fun navItem(name: String, label: String, iconRes: Int, enabled: Boolean) {
        val active = name == current
        val item = Ui.vertical(this).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(46)
            alpha = if (enabled) 1f else 0.4f
            isClickable = enabled
            contentDescription = label
            background = Ui.pressable(context, null, 12f)
            addView(ImageView(context).apply {
                setImageDrawable(getDrawable(iconRes)?.mutate()?.also { it.setTint(if (active) Palette.PRIMARY else Palette.DIM) })
                layoutParams = LinearLayout.LayoutParams(Ui.iconSize(context, 21f), Ui.iconSize(context, 21f))
            })
            addView(TextView(context).apply {
                text = label
                textSize = Ui.sp(context, 10.5f)
                setTextColor(if (active) Palette.TEXT else Palette.DIM)
                typeface = if (active) Ui.medium else Ui.sans
                // NOTE: A vertical LinearLayout gives children its full width,
                //       so the label centres itself rather than relying on it.
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { show(name) }
        }
        nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && tabs[current]?.onBack() == true) return true
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tabs[current]?.onBack() == true) return
        if (current != TAB_HOME) {
            show(TAB_HOME)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = "home"
        const val TAB_TERMINAL = "terminal"
        const val TAB_MANAGE = "manage"
    }
}
