package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.core.ScriptName
import io.github.nicup14.debdroid.core.Shell
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Payload
import io.github.nicup14.debdroid.root.Root
import io.github.nicup14.debdroid.ui.Ui.dp
import java.io.File

// Commands: the command directory, which is the list
//
// Not a fixed list: whatever is in /sdcard/debdroid/command is shown, the
// shipped scripts first. Add, edit, rename, duplicate, delete, import, export.
class CommandsActivity : Screen() {

    data class Script(val name: String, val shipped: Boolean, val summary: String)

    private lateinit var page: Ui.Page
    private var exporting: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = Ui.Page(this)
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // Reads the directory, and which names the payload ships
    private fun load() {
        val shippedNames = File(Payload.unpack(this), "command").list()
            ?.filter { it.endsWith(".sh") }?.map { it.removeSuffix(".sh") }?.toSet().orEmpty()
        Thread {
            // NOTE: The second line of each script is its one-line summary, by
            //       the project's own convention ("# name.sh: what it does").
            val listing = Root.run(
                "cd ${DebDroid.COMMANDS} 2> /dev/null && for f in *.sh; do [ -f \"\$f\" ] || continue; " +
                    "echo \"\$f|\$(sed -n 2p \"\$f\" | cut -c1-120)|\$(wc -l < \"\$f\")\"; done"
            ).output
            val scripts = listing.lines().filter { it.contains('|') }.map { line ->
                val parts = line.split('|')
                val name = parts[0].removeSuffix(".sh")
                val summary = parts.getOrElse(1) { "" }.removePrefix("#").substringAfter(": ", "").trim()
                val lines = parts.getOrElse(2) { "" }.trim()
                Script(name, name in shippedNames, summary.ifEmpty { "$lines lines" })
            }
            Handler(Looper.getMainLooper()).post { render(scripts) }
        }.start()
    }

    private fun render(scripts: List<Script>) {
        page.body.removeAllViews()
        page.body.addView(Ui.horizontal(this, 8f).apply {
            setPadding(dp(8), dp(16), dp(14), dp(10))
            addView(ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_back)?.mutate()?.also { it.setTint(Palette.TEXT_SOFT) })
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                contentDescription = "Back"
                background = Ui.pressable(context, null, 10f)
                setOnClickListener { finish() }
            })
            addView(Ui.vertical(context).apply {
                layoutParams = Ui.weighted(1f)
                addView(Ui.title(context, "Commands", 21f))
                addView(Ui.monoText(context, "${DebDroid.COMMANDS}/", 10.5f).apply { setPadding(0, dp(2), 0, 0) })
            })
            addView(ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_plus)?.mutate()?.also { it.setTint(android.graphics.Color.WHITE) })
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                contentDescription = "New script"
                background = Ui.pressable(context, Ui.rounded(context, Palette.PRIMARY, 11f), 11f)
                setOnClickListener { openEditor(null) }
            })
        })

        val body = Ui.vertical(this).apply { setPadding(dp(16), dp(6), dp(16), dp(16)) }
        page.body.addView(body)
        val shipped = scripts.filter { it.shipped }
        val yours = scripts.filter { !it.shipped }

        body.addView(Ui.header(this, "Shipped · ${shipped.size}"))
        body.addView(list(shipped))
        body.addView(Ui.text(this, "An update replaces these. To change one, duplicate it first and edit the copy.", 11f, Palette.DIM).apply {
            setPadding(dp(4), dp(7), dp(4), 0)
        })

        body.addView(Ui.header(this, "Yours · ${yours.size}").apply { setPadding(dp(4), dp(14), 0, dp(8)) })
        if (yours.isEmpty()) {
            body.addView(Ui.text(this, "None yet. Make one with +, or import one.", 12.5f, Palette.DIM).apply {
                setPadding(dp(4), 0, 0, dp(4))
            })
        } else {
            body.addView(list(yours))
        }

        body.addView(Ui.text(this, "Import a script", 13.5f, Palette.TEXT_MID, Ui.medium).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            layoutParams = Ui.margins(context, top = 14f)
            background = Ui.pressable(context, android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Palette.BORDER, dp(4).toFloat(), dp(3).toFloat())
            }, 14f)
            getDrawable(R.drawable.ic_import)?.mutate()?.let {
                it.setTint(Palette.TEXT_MID)
                it.setBounds(0, 0, dp(16), dp(16))
                text = android.text.SpannableString("\u0000  Import a script").apply {
                    setSpan(Ui.CenteredIcon(it), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            setOnClickListener { importScript() }
        })
    }

    private fun list(scripts: List<Script>) = Ui.card(this, 14f).apply {
        for ((i, script) in scripts.withIndex()) {
            if (i > 0) addView(Ui.divider(context, 15f))
            addView(Ui.horizontal(context, 11f).apply {
                minimumHeight = dp(52)
                setPadding(dp(15), dp(10), dp(8), dp(10))
                addView(Ui.vertical(context).apply {
                    layoutParams = Ui.weighted(1f)
                    addView(Ui.text(context, script.name, 13f, Palette.TEXT, Ui.mono))
                    addView(Ui.text(context, script.summary, 11.5f, Palette.DIM).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                addView(squareButton(R.drawable.ic_play, "Run ${script.name}") { run(script) })
                addView(ImageView(context).apply {
                    setImageDrawable(getDrawable(R.drawable.ic_more)?.mutate()?.also { it.setTint(0xFF6E6E7A.toInt()) })
                    setPadding(dp(8), dp(9), dp(8), dp(9))
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(36))
                    contentDescription = "More for ${script.name}"
                    background = Ui.pressable(context, null, 9f)
                    setOnClickListener { more(it, script) }
                })
                isClickable = true
                background = Ui.pressable(context, null, 0f)
                setOnClickListener { openEditor(script.name) }
            })
        }
    }

    private fun squareButton(iconRes: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(getDrawable(iconRes)?.mutate()?.also { it.setTint(Palette.TEXT_SOFT) })
        setPadding(dp(11), dp(11), dp(11), dp(11))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        contentDescription = label
        background = Ui.pressable(context, Ui.rounded(context, Palette.INSET, 9f, Palette.LINE_INSET), 9f)
        setOnClickListener { onClick() }
    }

    // Runs a script in a terminal, as it may ask questions
    private fun run(script: Script) {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TERMINAL)
            .putExtra(TerminalTab.EXTRA_COMMAND, script.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun openEditor(name: String?) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_SCRIPT, name))
    }

    private fun path(name: String) = "${DebDroid.COMMANDS}/$name.sh"

    private fun more(anchor: View, script: Script) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Edit")
        menu.menu.add(0, 2, 0, "Duplicate")
        if (!script.shipped) menu.menu.add(0, 3, 0, "Rename")
        menu.menu.add(0, 4, 0, "Export")
        if (!script.shipped) menu.menu.add(0, 5, 0, "Delete")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(script.name)
                2 -> askName("Duplicate ${script.name}", "${script.name}-copy") { name ->
                    act("cp ${Shell.quote(path(script.name))} ${Shell.quote(path(name))}")
                }
                3 -> askName("Rename ${script.name}", script.name) { name ->
                    act("mv ${Shell.quote(path(script.name))} ${Shell.quote(path(name))}")
                }
                4 -> export(script.name)
                5 -> TaskSheet.ask(this, "Delete ${script.name}", "The file is removed from the command directory.",
                    "Type DELETE to confirm", "", valid = { if (it == "DELETE") null else "Type DELETE" }) {
                    act("rm -f ${Shell.quote(path(script.name))}")
                }
            }
            true
        }
        menu.show()
    }

    // Asks for a name the runtime will accept, refusing one that exists
    private fun askName(title: String, initial: String, then: (String) -> Unit) {
        TaskSheet.ask(this, title, "Letters, digits, _ and - only: anything else and the runtime refuses to run it.",
            "name", ScriptName.clean(initial), valid = { if (ScriptName.isValid(it)) null else "Letters, digits, _ and - only" }) { name ->
            Thread {
                val exists = Root.run("[ -e ${Shell.quote(path(name))} ]").ok
                Handler(Looper.getMainLooper()).post {
                    if (exists) Toast.makeText(this, "\"$name\" already exists.", Toast.LENGTH_SHORT).show() else then(name)
                }
            }.start()
        }
    }

    private fun act(command: String) {
        Thread {
            val result = Root.run(command)
            Handler(Looper.getMainLooper()).post {
                if (!result.ok) Toast.makeText(this, result.output.trim().ifEmpty { "That did not work." }, Toast.LENGTH_LONG).show()
                load()
            }
        }.start()
    }

    // NOTE: An imported script opens in the editor rather than running. These
    //       run as root inside the chroot and most users are new to this, so
    //       "paste and run" is a bad default; editing first costs one tap and
    //       shows the content once.
    private fun importScript() {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, REQUEST_IMPORT)
    }

    private fun export(name: String) {
        exporting = name
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/x-shellscript"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "$name.sh")
        }, REQUEST_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT -> {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
                val suggested = (uri.lastPathSegment ?: "imported").substringAfterLast('/').removeSuffix(".sh")
                startActivity(Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_IMPORTED, text.replace("\r\n", "\n"))
                    .putExtra(EditorActivity.EXTRA_SUGGESTED, ScriptName.clean(suggested)))
            }
            REQUEST_EXPORT -> {
                val name = exporting ?: return
                Thread {
                    val text = Root.read(path(name)).orEmpty()
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    Handler(Looper.getMainLooper()).post { Toast.makeText(this, "Exported $name.sh", Toast.LENGTH_SHORT).show() }
                }.start()
            }
        }
    }

    companion object {
        private const val REQUEST_IMPORT = 1
        private const val REQUEST_EXPORT = 2
    }
}
