package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.core.ScriptName
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Root
import io.github.nicup14.debdroid.ui.Ui.dp

// The editor: a command script, or the environment file
//
// For a script: the name field accepts only what the runtime runs, the body
// is plain text with no autocorrect, and Run opens it in a terminal with the
// arguments given. For the environment file: the same editor, no name, no run.
class EditorActivity : Screen() {

    private lateinit var name: EditText
    private lateinit var script: EditText
    private lateinit var lineNumbers: TextView
    private var original: String? = null
    private var file: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        file = intent.getStringExtra(EXTRA_FILE)
        original = intent.getStringExtra(EXTRA_SCRIPT)
        val imported = intent.getStringExtra(EXTRA_IMPORTED)

        val root = Ui.vertical(this).apply { setBackgroundColor(Palette.GROUND) }
        root.addView(Ui.horizontal(this, 6f).apply {
            setPadding(dp(8), dp(14), dp(14), dp(10))
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_back)?.mutate()?.also { it.setTint(Palette.TEXT_SOFT) })
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                contentDescription = "Back"
                background = Ui.pressable(context, null, 10f)
                setOnClickListener { finish() }
            })
            addView(Ui.text(context, when {
                file != null -> "Environment variables"
                original == null -> "New script"
                else -> "Edit script"
            }, 16f, Palette.TEXT, Ui.medium).apply { layoutParams = Ui.weighted(1f) })
            addView(Ui.button(context, "Save", height = 36f).apply {
                textSize = Ui.sp(context, 13.5f)
                setPadding(dp(15), 0, dp(15), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { save(null) }
            })
        })
        root.addView(android.view.View(this).apply { setBackgroundColor(0xFF1F1F26.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val body = Ui.vertical(this).apply { setPadding(dp(16), dp(14), dp(16), 0) }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        name = EditText(this).apply {
            setText(original ?: intent.getStringExtra(EXTRA_SUGGESTED).orEmpty())
            typeface = Ui.mono
            textSize = Ui.sp(context, 14f)
            setTextColor(Palette.TEXT)
            hint = "name"
            setHintTextColor(Palette.FAINT)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // NOTE: The field cannot hold a character debdroid.sh would refuse,
            //       as a name like "my backup" makes a file nobody can run.
            filters = arrayOf(InputFilter { source, _, _, _, _, _ -> ScriptName.clean(source.toString()) })
            layoutParams = Ui.weighted(1f)
        }
        if (file == null) {
            body.addView(Ui.header(this, "Name").apply { setPadding(0, 0, 0, dp(7)) })
            body.addView(Ui.horizontal(this).apply {
                background = Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET)
                setPadding(dp(13), 0, dp(13), 0)
                minimumHeight = dp(46)
                addView(name)
                addView(Ui.monoText(context, ".sh", 12f, 0xFF6E6E7A.toInt()))
            })
            body.addView(Ui.text(this, "Letters, digits, _ and - only: anything else and the runtime refuses to run it.", 11f, Palette.DIM).apply {
                setPadding(dp(2), dp(7), 0, dp(13))
            })
        }

        body.addView(Ui.horizontal(this, 8f).apply {
            layoutParams = Ui.margins(context, bottom = 7f)
            addView(Ui.header(context, if (file != null) "environment" else "Script").apply { setPadding(0, 0, 0, 0) })
            addView(Ui.space(context))
            addView(Ui.text(context, if (file != null) "sourced inside debian, every session" else "runs inside debian, as root", 10.5f, Palette.DIM))
        })

        lineNumbers = Ui.text(this, "1", 11f, 0xFF4E4E58.toInt(), Ui.mono).apply {
            gravity = Gravity.END
            setLineSpacing(0f, 1.0f)
            setPadding(dp(4), dp(11), dp(8), dp(11))
            setBackgroundColor(Palette.GROUND)
            minWidth = dp(30)
        }
        script = EditText(this).apply {
            typeface = Ui.mono
            textSize = Ui.sp(context, 11f)
            setTextColor(Palette.OUTPUT)
            setLineSpacing(0f, 1.0f)
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(dp(12), dp(11), dp(12), dp(11))
            // NOTE: Autocorrect and capitalisation off: a keyboard that turns
            //       "cd" into "CD" breaks a script without anyone noticing.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = numberLines()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val editor = android.widget.ScrollView(this).apply {
            background = Ui.rounded(context, Palette.BARS, 12f, Palette.LINE)
            isFillViewport = true
            addView(Ui.horizontal(context).apply {
                gravity = Gravity.TOP
                addView(lineNumbers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(script, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        body.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (file == null) {
            body.addView(Ui.horizontal(this, 9f).apply {
                gravity = Gravity.TOP
                background = Ui.rounded(context, Palette.CARD, 12f, Palette.LINE)
                setPadding(dp(13), dp(12), dp(13), dp(12))
                layoutParams = Ui.margins(context, top = 13f, bottom = 4f)
                addView(Ui.icon(context, R.drawable.ic_alert, Palette.PATHS, 15f))
                addView(Ui.text(context, "This runs inside Debian, so every Debian tool is available. For anything on the android side, use the device actions in Manage instead.",
                    11.5f, Palette.TEXT_MID).apply { layoutParams = Ui.weighted(1f) })
            })

            val args = EditText(this).apply {
                hint = "arguments (optional)"
                setHintTextColor(Palette.DIM)
                typeface = Ui.mono
                textSize = Ui.sp(context, 12.5f)
                setTextColor(Palette.TEXT)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET)
                setPadding(dp(13), 0, dp(13), 0)
                minHeight = dp(46)
                layoutParams = Ui.weighted(1f)
            }
            root.addView(Ui.horizontal(this, 10f).apply {
                setBackgroundColor(Palette.BARS)
                setPadding(dp(16), dp(11), dp(16), dp(15))
                addView(args)
                addView(Ui.button(context, "Run", iconRes = R.drawable.ic_play, height = 46f).apply {
                    textSize = Ui.sp(context, 14f)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { save(args.text.toString()) }
                })
            })
        }
        setContentView(root)

        when {
            imported != null -> script.setText(imported)
            file != null -> load(file!!)
            original != null -> load("${DebDroid.COMMANDS}/$original.sh")
            else -> script.setText("#!/bin/sh\n# ")
        }
    }

    private fun load(path: String) {
        Thread {
            val text = Root.read(path).orEmpty()
            Handler(Looper.getMainLooper()).post { script.setText(text) }
        }.start()
    }

    private fun numberLines() {
        val count = script.lineCount.coerceAtLeast(script.text.count { it == '\n' } + 1)
        lineNumbers.text = (1..count).joinToString("\n")
    }

    // Saves, then runs when asked to
    private fun save(runWith: String?) {
        val target = file ?: run {
            val n = name.text.toString()
            if (!ScriptName.isValid(n)) {
                name.error = "Letters, digits, _ and - only"
                return
            }
            "${DebDroid.COMMANDS}/$n.sh"
        }
        val text = script.text.toString().replace("\r\n", "\n").let { if (it.endsWith("\n")) it else "$it\n" }
        val renamedFrom = if (file == null && original != null && original != name.text.toString()) "${DebDroid.COMMANDS}/$original.sh" else null
        Thread {
            val result = Root.write(target, text)
            if (result.ok && renamedFrom != null) Root.run("rm -f " + io.github.nicup14.debdroid.core.Shell.quote(renamedFrom))
            Handler(Looper.getMainLooper()).post {
                if (!result.ok) {
                    Toast.makeText(this, "Could not save: ${result.output.trim()}", Toast.LENGTH_LONG).show()
                    return@post
                }
                original = name.text.toString()
                if (runWith == null) {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                    if (file != null) finish()
                    return@post
                }
                startActivity(Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TERMINAL)
                    .putExtra(TerminalTab.EXTRA_COMMAND, name.text.toString())
                    .putExtra(TerminalTab.EXTRA_ARGS, runWith)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }.start()
    }

    companion object {
        const val EXTRA_SCRIPT = "script"
        const val EXTRA_FILE = "file"
        const val EXTRA_IMPORTED = "imported"
        const val EXTRA_SUGGESTED = "suggested"
    }
}
