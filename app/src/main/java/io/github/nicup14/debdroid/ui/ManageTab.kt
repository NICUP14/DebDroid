package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.Pinned
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.core.Version
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.ui.Ui.dp
import java.net.HttpURLConnection
import java.net.URL

// Manage: the image, swap, users, packages, scripts, updates, report, uninstall
class ManageTab(private val activity: MainActivity) : MainActivity.Tab {

    // NOTE: A tab is not a Context, so it sizes through its activity
    private fun dp(value: Number) = Ui.dp(activity, value.toFloat())

    private val page = Ui.Page(activity)
    override val view: View = page
    private var latest: String? = null
    private var checking = false

    override fun refresh() {
        val body = page.body
        body.removeAllViews()
        body.setPadding(dp(16), 0, dp(16), dp(16))
        val probe = DeviceState.probe

        body.addView(Ui.horizontal(activity, 10f).apply {
            setPadding(dp(4), dp(20), dp(4), dp(12))
            addView(Ui.title(context, "Manage"))
            addView(Ui.space(context))
            addView(Ui.monoText(context, "v${probe?.installedVersion ?: Pinned.VERSION}", 11.5f))
        })

        body.addView(Ui.header(activity, "Environment"))
        body.addView(Ui.card(activity, 14f).apply {
            addView(Ui.row(context, "Image size", Ui.size(probe?.imageSize), R.drawable.ic_resize) { TaskSheet.resize(activity) })
            addView(Ui.divider(context, 44f))
            addView(Ui.row(context, "Swapfile", if (probe?.swapfile == true) (if (probe.swapOn) "on" else "created") else "none",
                R.drawable.ic_swap) { swap(activity) })
        })

        body.addView(Ui.header(activity, "Debian").apply { setPadding(dp(4), dp(14), 0, dp(8)) })
        body.addView(Ui.card(activity, 14f).apply {
            addView(Ui.row(context, "Users", "add one", R.drawable.ic_user, valueMono = false) { addUser(activity) })
            addView(Ui.divider(context, 44f))
            addView(Ui.row(context, "Update packages", "apt", R.drawable.ic_refresh) {
                TerminalTab.openCommand(activity, "update", emptyList())
            })
            addView(Ui.divider(context, 44f))
            addView(Ui.row(context, "Environment variables", "environment", R.drawable.ic_doc) {
                activity.startActivity(Intent(activity, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_FILE, DebDroid.ENVIRONMENT))
            })
        })

        body.addView(Ui.horizontal(activity, 8f).apply {
            setPadding(dp(4), dp(14), dp(4), dp(8))
            addView(Ui.header(context, "Command scripts").apply { setPadding(0, 0, 0, 0) })
            addView(Ui.space(context))
            addView(Ui.text(context, "Manage", 11.5f, Palette.PRIMARY, Ui.medium).apply {
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { activity.startActivity(Intent(activity, CommandsActivity::class.java)) }
            })
        })
        body.addView(Ui.card(activity, 14f).apply {
            val running = probe?.services.orEmpty()
            addView(Ui.row(context, "sshserver", if ("ssh" in running) "running" else "stopped", valueMono = false) {
                activity.startActivity(Intent(activity, CommandsActivity::class.java))
            }.also { if ("ssh" in running) Ui.setRowValue(it, "running", Palette.RUNNING) })
            addView(Ui.divider(context, 15f))
            addView(Ui.row(context, "vncserver", if ("vnc" in running) "running" else "stopped", valueMono = false) {
                activity.startActivity(Intent(activity, CommandsActivity::class.java))
            }.also { if ("vnc" in running) Ui.setRowValue(it, "running", Palette.RUNNING) })
            addView(Ui.divider(context, 15f))
            addView(Ui.row(context, "All scripts", "add, edit, import", valueMono = false) {
                activity.startActivity(Intent(activity, CommandsActivity::class.java))
            })
        })
        body.addView(Ui.text(activity, "Add, edit, rename or import. The directory is the list: drop a script in and it appears.",
            11.5f, Palette.DIM).apply { setPadding(dp(4), dp(7), dp(4), 0) })

        body.addView(Ui.header(activity, "DebDroid").apply { setPadding(dp(4), dp(14), 0, dp(8)) })
        body.addView(Ui.card(activity, 14f).apply {
            val status = when {
                checking -> "checking"
                latest == null -> "check"
                Version.compare(latest!!, Pinned.VERSION) > 0 -> "v$latest available"
                else -> "up to date"
            }
            addView(Ui.row(context, "Check for updates", status, valueMono = false) { checkUpdates() })
            addView(Ui.divider(context, 15f))
            addView(Ui.row(context, "Restore the previous version", "rollback", valueMono = false) { rollback() })
            addView(Ui.divider(context, 15f))
            addView(Ui.row(context, "Startup log", "mnt.log") {
                activity.startActivity(Intent(activity, TextActivity::class.java).putExtra(TextActivity.EXTRA_KIND, TextActivity.KIND_LOG))
            })
            addView(Ui.divider(context, 15f))
            addView(Ui.row(context, "Issue report", null) {
                activity.startActivity(Intent(activity, TextActivity::class.java).putExtra(TextActivity.EXTRA_KIND, TextActivity.KIND_REPORT))
            })
        })

        body.addView(Ui.button(activity, "Uninstall DebDroid", Ui.Kind.CAUTION, R.drawable.ic_trash, 48f).apply {
            layoutParams = Ui.margins(context, top = 16f)
            setOnClickListener { activity.startActivity(Intent(activity, UninstallActivity::class.java)) }
        })
    }

    // Asks GitHub for the latest release, the only place a newer app comes from
    // NOTE: The app carries its own scripts, so updating DebDroid means
    //       updating the app; this only has to say whether there is one.
    private fun checkUpdates() {
        val known = latest
        if (known != null && Version.compare(known, Pinned.VERSION) > 0) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NICUP14/DebDroid/releases/latest")))
            return
        }
        checking = true
        refresh()
        Thread {
            val tag = try {
                val c = URL("https://api.github.com/repos/NICUP14/DebDroid/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "DebDroid-app")
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                val text = c.inputStream.bufferedReader().readText()
                c.disconnect()
                Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(text)?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post {
                checking = false
                latest = tag
                if (tag == null) android.widget.Toast.makeText(activity, "Could not reach the releases page.", android.widget.Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    // Restores the version saved before the last update
    // NOTE: A button rather than a command, because the thing you would type
    //       the command with may be exactly what just broke. The scripts refuse while the environment runs.
    private fun rollback() {
        val probe = DeviceState.probe
        TaskSheet.ask(activity, "Restore the previous version",
            if (probe?.running == true) "Stop Debian first. The previous scripts and tools come back; your image and settings stay as they are."
            else "The scripts and tools saved before the last update come back. Your image and settings stay as they are.",
            "Type RESTORE to confirm", "", valid = { if (it == "RESTORE") null else "Type RESTORE" }) {
            TaskSheet.run(activity, "Restoring", "", DebDroid.verb("self-update", "--rollback"))
        }
    }

    companion object {
        // Adds a non-root user, in a terminal because passwd asks for a password
        fun addUser(activity: Activity) {
            TaskSheet.ask(activity, "Add a user",
                "A non-root account that can use the network. You will be asked for its password next.",
                "name", "", valid = {
                    if (Regex("^[a-z][a-z0-9_-]{0,31}$").matches(it)) null
                    else "Lowercase letters, digits, _ and -, starting with a letter"
                }) { name ->
                TerminalTab.openCommand(activity, "user", listOf(name))
            }
        }

        // Creates, reports on or removes the swapfile, which lives on the android side
        fun swap(activity: Activity) {
            val probe = DeviceState.probe
            if (probe?.swapfile != true) {
                TaskSheet.ask(activity, "Create a swapfile",
                    "Gives Debian more memory to work with, on /data. Turn it on afterwards in Settings.",
                    "size", "2G", valid = { if (TaskSheet.validSize(it.uppercase())) null else "A number then M or G, such as 2G" }) { size ->
                    TaskSheet.run(activity, "Creating a swapfile", "Writing $size takes a while on slower storage.",
                        DebDroid.verb("swap", "create", size.uppercase()))
                }
                return
            }
            TaskSheet.ask(activity, "Remove the swapfile",
                "Gives its space back. Sessions run without swap afterwards.",
                "Type REMOVE to confirm", "", valid = { if (it == "REMOVE") null else "Type REMOVE" }) {
                TaskSheet.run(activity, "Removing the swapfile", "", DebDroid.verb("swap", "remove"))
            }
        }
    }
}
