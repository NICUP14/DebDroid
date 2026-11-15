package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.core.ScriptName
import io.github.nicup14.debdroid.core.Shell
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Root
import io.github.nicup14.debdroid.service.SessionService
import io.github.nicup14.debdroid.term.TermKey
import io.github.nicup14.debdroid.term.TerminalScheme
import io.github.nicup14.debdroid.term.TerminalSession
import io.github.nicup14.debdroid.term.TerminalView
import io.github.nicup14.debdroid.ui.Ui.dp

// Terminal: the session, an extra key row, an input line
//
// Every session attaches to the background environment, so leaving the app or
// this tab ends nothing. The sessions live in SessionService, which keeps them
// alive and in the notification.
class TerminalTab(private val activity: MainActivity) : MainActivity.Tab, TerminalSession.Listener {

    // NOTE: A tab is not a Context, so it sizes through its activity
    private fun dp(value: Number) = Ui.dp(activity, value.toFloat())

    private val terminal = TerminalView(activity)
    private val title = Ui.text(activity, "debian", 14.5f, Palette.TEXT, Ui.medium)
    private val state = Ui.text(activity, "attached", 11.5f, Palette.DIM)
    private val dot = View(activity)
    private val ended = Ui.horizontal(activity, 10f)
    private val keyRow = Ui.horizontal(activity, 6f)
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private var current: TerminalSession? = null

    override val view: View = Ui.vertical(activity).apply {
        setBackgroundColor(Palette.GROUND)
        addView(sessionBar())
        addView(View(context).apply { setBackgroundColor(0xFF1F1F26.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        addView(FrameLayout(context).apply {
            addView(terminal, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ended, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(View(context).apply { setBackgroundColor(0xFF1F1F26.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        addView(keyRow)
        addView(inputLine())
    }

    init {
        terminal.setPadding(activity.dp(13), activity.dp(14), activity.dp(13), activity.dp(6))
        buildKeys()
        terminal.modifiers.onChange = { paintModifiers() }
        terminal.onBackLongPress = { activity.show(MainActivity.TAB_HOME) }
        ended.visibility = View.GONE
        ended.setBackgroundColor(Palette.CARD)
        ended.setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
        applyPrefs()
    }

    private fun sessionBar() = Ui.horizontal(activity, 10f).apply {
        setPadding(dp(8), dp(12), dp(12), dp(10))
        addView(iconButton(R.drawable.ic_back, "Back to home", Palette.TEXT_SOFT) { activity.show(MainActivity.TAB_HOME) })
        addView(Ui.horizontal(context, 7f).apply {
            layoutParams = Ui.weighted(1f)
            dot.background = Ui.rounded(context, Palette.RUNNING, 3f)
            addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))
            addView(title)
            addView(state)
        })
        addView(iconButton(R.drawable.ic_tabs, "Session tabs", Palette.TEXT_MID) { chooseSession() })
        addView(iconButton(R.drawable.ic_more, "Session menu", Palette.TEXT_MID) { menu(it) })
    }

    private fun iconButton(res: Int, label: String, tint: Int, onClick: (View) -> Unit) = ImageView(activity).apply {
        setImageDrawable(activity.getDrawable(res)?.mutate()?.also { it.setTint(tint) })
        val pad = activity.dp(10)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(activity.dp(42), activity.dp(42))
        contentDescription = label
        background = Ui.pressable(activity, null, 10f)
        setOnClickListener { onClick(it) }
    }

    // The keys a soft keyboard does not have
    private fun buildKeys() {
        keyRow.setBackgroundColor(Palette.BARS)
        keyRow.setPadding(activity.dp(8), activity.dp(9), activity.dp(8), activity.dp(9))
        keyRow.addView(textKey("ESC") { terminal.sendKey(TermKey.ESCAPE) })
        keyRow.addView(textKey("TAB") { terminal.sendKey(TermKey.TAB) })
        ctrlKey = textKey("CTRL") { terminal.modifiers.toggleCtrl() }
        altKey = textKey("ALT") { terminal.modifiers.toggleAlt() }
        keyRow.addView(ctrlKey)
        keyRow.addView(altKey)
        keyRow.addView(Ui.space(activity))
        keyRow.addView(arrowKey(R.drawable.ic_arrow_up, "Up") { terminal.sendKey(TermKey.UP) })
        keyRow.addView(arrowKey(R.drawable.ic_arrow_down, "Down") { terminal.sendKey(TermKey.DOWN) })
        keyRow.addView(arrowKey(R.drawable.ic_arrow_left, "Left") { terminal.sendKey(TermKey.LEFT) })
        keyRow.addView(arrowKey(R.drawable.ic_arrow_right, "Right") { terminal.sendKey(TermKey.RIGHT) })
        paintModifiers()
    }

    private fun textKey(label: String, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = Ui.mono
        textSize = Ui.sp(context, 11.5f)
        setTextColor(Palette.TEXT_SOFT)
        minWidth = activity.dp(46)
        minimumHeight = activity.dp(38)
        setPadding(activity.dp(9), 0, activity.dp(9), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(38))
        background = Ui.pressable(activity, Ui.rounded(activity, Palette.INSET, 9f, Palette.LINE_INSET), 9f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun arrowKey(res: Int, label: String, onClick: () -> Unit) = ImageView(activity).apply {
        setImageDrawable(activity.getDrawable(res)?.mutate()?.also { it.setTint(Palette.TEXT_SOFT) })
        val pad = activity.dp(11)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(activity.dp(38), activity.dp(38))
        contentDescription = label
        background = Ui.pressable(activity, Ui.rounded(activity, Palette.INSET, 9f, Palette.LINE_INSET), 9f)
        setOnClickListener { onClick() }
    }

    // An armed modifier is filled, the way the canvas shows CTRL pressed
    private fun paintModifiers() {
        for ((key, on) in listOf(ctrlKey to terminal.modifiers.ctrl, altKey to terminal.modifiers.alt)) {
            key.background = Ui.pressable(activity,
                if (on) Ui.rounded(activity, Palette.PRIMARY, 9f, Palette.PRIMARY) else Ui.rounded(activity, Palette.INSET, 9f, Palette.LINE_INSET), 9f)
            key.setTextColor(if (on) android.graphics.Color.WHITE else Palette.TEXT_SOFT)
            key.typeface = if (on) android.graphics.Typeface.create(Ui.mono, android.graphics.Typeface.BOLD) else Ui.mono
        }
    }

    // A line to compose a command in, sent with Enter
    // NOTE: Autocorrect, capitalisation and suggestions are always off. A
    //       keyboard that turns "cd" into "CD" is not a preference.
    private fun inputLine() = Ui.horizontal(activity, 9f).apply {
        setBackgroundColor(Palette.BARS)
        setPadding(dp(12), 0, dp(12), dp(13))
        val field = EditText(context).apply {
            hint = "Type a command"
            setHintTextColor(Palette.DIM)
            typeface = Ui.mono
            textSize = Ui.sp(context, 13f)
            setTextColor(Palette.TEXT)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET)
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(44)
            contentDescription = "Command"
            layoutParams = Ui.weighted(1f)
            setOnEditorActionListener { v, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (!enter) return@setOnEditorActionListener false
                current?.write(v.text.toString() + "\r")
                v.text = ""
                terminal.scrollToBottom()
                true
            }
        }
        addView(field)
        addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(R.drawable.ic_paste)?.mutate()?.also { it.setTint(Palette.TEXT_MID) })
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Paste"
            background = Ui.pressable(context, Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET), 12f)
            setOnClickListener { pasteClipboard() }
        })
    }

    private fun pasteClipboard() {
        val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(activity, "The clipboard is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        terminal.paste(text)
    }

    // Reads the terminal preferences, which are this app's own and nothing else's
    fun applyPrefs() {
        val prefs = TerminalPrefsActivity.prefs(activity)
        terminal.textSizeSp = prefs.getInt(TerminalPrefsActivity.SIZE, TerminalPrefsActivity.defaultSize(activity)).toFloat()
        terminal.scheme = runCatching { TerminalScheme.valueOf(prefs.getString(TerminalPrefsActivity.SCHEME, "DEBDROID")!!) }
            .getOrDefault(TerminalScheme.DEBDROID)
        terminal.volumeDownIsCtrl = prefs.getBoolean(TerminalPrefsActivity.VOLUME_CTRL, true)
        terminal.backSendsEsc = prefs.getBoolean(TerminalPrefsActivity.BACK_ESC, false)
        keyRow.visibility = if (prefs.getBoolean(TerminalPrefsActivity.EXTRA_KEYS, true)) View.VISIBLE else View.GONE
        terminal.setBackgroundColor(terminal.scheme.background)
    }

    override fun refresh() {
        applyPrefs()
        val pending = activity.intent.getStringExtra(EXTRA_COMMAND)
        if (pending != null) {
            val args = activity.intent.getStringExtra(EXTRA_ARGS).orEmpty()
            activity.intent.removeExtra(EXTRA_COMMAND)
            activity.intent.removeExtra(EXTRA_ARGS)
            open(activity, pending, splitArgs(args))
            return
        }
        bind(current ?: SessionService.sessions.lastOrNull())
    }

    override fun onShown() {
        if (current == null && SessionService.sessions.isEmpty() && DeviceState.probe?.running == true) {
            bind(newSession(activity, "debian", DebDroid.attachCommand()))
        }
        terminal.requestFocus()
        terminal.showKeyboard()
    }

    override fun onBack(): Boolean = false

    // Shows one session in the view
    private fun bind(session: TerminalSession?) {
        if (session === current && session != null) {
            showState(session)
            return
        }
        current?.removeListener(this)
        current = session
        terminal.attach(session)
        session?.addListener(this)
        showState(session)
    }

    private fun showState(session: TerminalSession?) {
        title.text = session?.title ?: "debian"
        val alive = session?.isRunning == true
        state.text = if (alive) (if (session?.title == "debian") "attached" else "running") else "ended"
        dot.background = Ui.rounded(activity, if (alive) Palette.RUNNING else Palette.DIM, 3f)
        ended.removeAllViews()
        if (session != null && !alive) {
            ended.visibility = View.VISIBLE
            ended.addView(Ui.text(activity, "Session ended" + (session.exitCode?.let { " (exit $it)" } ?: ""), 13f, Palette.TEXT_SOFT).apply {
                layoutParams = Ui.weighted(1f)
            })
            ended.addView(Ui.button(activity, "Close", Ui.Kind.SECONDARY, height = 40f).apply {
                setOnClickListener { close(session) }
            })
        } else {
            ended.visibility = View.GONE
        }
    }

    private fun close(session: TerminalSession) {
        session.finish()
        SessionService.sessions.remove(session)
        SessionService.sessionsChanged(activity)
        current = null
        bind(SessionService.sessions.lastOrNull())
        if (current == null) activity.show(MainActivity.TAB_HOME)
    }

    override fun onScreenUpdate(session: TerminalSession) {}

    override fun onExit(session: TerminalSession, code: Int) {
        showState(session)
        SessionService.sessionsChanged(activity)
    }

    // The tabs affordance: every session the app holds, and a new one
    private fun chooseSession() {
        val sessions = SessionService.sessions
        val labels = sessions.mapIndexed { i, s -> "${i + 1}. ${s.title}" + if (s.isRunning) "" else " (ended)" } + "New session"
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Sessions")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == sessions.size) {
                    if (DeviceState.probe?.running == true) bind(newSession(activity, "debian", DebDroid.attachCommand()))
                    else Toast.makeText(activity, "Start Debian on the Home screen first.", Toast.LENGTH_SHORT).show()
                } else {
                    bind(sessions[which])
                }
            }
            .show()
    }

    private fun menu(anchor: View) {
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, "Copy the screen")
        menu.menu.add(0, 2, 0, "Paste")
        menu.menu.add(0, 3, 0, "New session")
        menu.menu.add(0, 4, 0, "Close this session")
        menu.menu.add(0, 5, 0, "Terminal preferences")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", terminal.copyScreen()))
                    Toast.makeText(activity, "Copied the screen", Toast.LENGTH_SHORT).show()
                }
                2 -> pasteClipboard()
                3 -> if (DeviceState.probe?.running == true) bind(newSession(activity, "debian", DebDroid.attachCommand()))
                4 -> current?.let { s -> close(s) }
                5 -> activity.startActivity(Intent(activity, TerminalPrefsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ARGS = "args"

        private fun env(): Array<String> = arrayOf(
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8", "HOME=/",
            "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/su/bin",
        )

        // Opens a session that runs one root command on a pty
        fun newSession(activity: Activity, title: String, command: String): TerminalSession {
            val prefs = TerminalPrefsActivity.prefs(activity)
            val session = TerminalSession(arrayOf(Root.su, "-c", command), env(), "/", 80, 24,
                prefs.getInt(TerminalPrefsActivity.SCROLLBACK, 2000))
            session.title = title
            try {
                session.start()
            } catch (e: Exception) {
                Toast.makeText(activity, "Could not open a terminal: ${e.message}", Toast.LENGTH_LONG).show()
            }
            SessionService.sessions += session
            SessionService.sessionsChanged(activity)
            return session
        }

        // Runs a command script in a terminal, where it can ask its questions
        // NOTE: "debdroid.sh command" runs it in the background environment when
        //       there is one, and in a foreground session otherwise, so this is
        //       right either way.
        fun openCommand(activity: Activity, name: String, args: List<String>) {
            if (!ScriptName.isValid(name)) return
            activity.startActivity(Intent(activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TERMINAL)
                .putExtra(EXTRA_COMMAND, name)
                .putExtra(EXTRA_ARGS, Shell.join(args))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        private fun open(activity: MainActivity, name: String, args: List<String>) {
            if (!ScriptName.isValid(name)) return
            val command = "TERM=xterm-256color; export TERM; " + DebDroid.verb("command", name, *args.toTypedArray())
            newSession(activity, name, command)
            activity.show(MainActivity.TAB_TERMINAL)
        }

        // Splits the arguments field the way a shell would split plain words
        // and quoted strings, without running anything
        fun splitArgs(text: String): List<String> {
            val out = ArrayList<String>()
            val word = StringBuilder()
            var quote = 0.toChar()
            var inWord = false
            for (c in text) {
                when {
                    quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else word.append(c)
                    c == '\'' || c == '"' -> { quote = c; inWord = true }
                    c.isWhitespace() -> if (inWord) { out += word.toString(); word.clear(); inWord = false }
                    else -> { word.append(c); inWord = true }
                }
            }
            if (inWord) out += word.toString()
            return out
        }
    }
}
