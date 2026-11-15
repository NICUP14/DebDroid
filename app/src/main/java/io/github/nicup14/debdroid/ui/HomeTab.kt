package io.github.nicup14.debdroid.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.service.SessionService
import io.github.nicup14.debdroid.ui.Ui.dp
import io.github.nicup14.debdroid.work.Task
import io.github.nicup14.debdroid.work.Tasks

// Home: stopped shows the facts that decide whether it can work and Start;
// running shows status, stats, Open terminal, Stop and the services
class HomeTab(private val activity: MainActivity) : MainActivity.Tab {

    // NOTE: A tab is not a Context, so it sizes through its activity
    private fun dp(value: Number) = Ui.dp(activity, value.toFloat())

    private val page = Ui.Page(activity)
    override val view: View = page

    override fun refresh() {
        page.body.removeAllViews()
        page.actions.removeAllViews()
        page.body.addView(topBar())
        val probe = DeviceState.probe ?: return
        val task = Tasks.current
        val starting = task != null && task.running && task.title.startsWith("Starting")
        if (probe.running) running(probe) else stopped(probe, starting)
    }

    private fun topBar() = Ui.horizontal(activity, 11f).apply {
        setPadding(dp(20), dp(18), dp(12), dp(14))
        addView(Ui.mark(context, 26f * Ui.scale(context)))
        addView(Ui.text(context, "DebDroid", 17f, Palette.TEXT, Ui.medium))
        addView(Ui.space(context))
        addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(R.drawable.ic_gear)?.mutate()?.also { it.setTint(Palette.TEXT_MID) })
            val side = maxOf(dp(44), Ui.iconSize(context, 21f) + dp(22))
            val pad = (side - Ui.iconSize(context, 21f)) / 2
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(side, side)
            contentDescription = "Settings"
            background = Ui.pressable(context, null, 10f)
            setOnClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
        })
    }

    private fun describe(probe: io.github.nicup14.debdroid.core.Probe) =
        "Debian · ${probe.arch} · ${Ui.size(probe.imageSize)} image"

    private fun stopped(probe: io.github.nicup14.debdroid.core.Probe, starting: Boolean) {
        val body = page.body
        body.setPadding(dp(16), 0, dp(16), dp(14))
        val hero = Ui.vertical(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(40), dp(12), dp(36))
            addView(Ui.mark(context, 88f).apply {
                alpha = 0.38f
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(26)
            })
            addView(Ui.title(context, if (starting) "Starting" else "Not running").apply { gravity = Gravity.CENTER })
            addView(Ui.body(context, if (starting) "Checking the filesystem and mounting. The output is on the next screen."
                else "${describe(probe)} is installed and ready.").apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(28))
                maxWidth = dp(280)
            })
            addView(Ui.button(context, if (starting) "Starting…" else "Start session", iconRes = R.drawable.ic_play).apply {
                layoutParams = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT)
                Ui.enable(this, !starting && Tasks.current?.running != true && !probe.foregroundSession)
                setOnClickListener { start() }
            })
        }
        body.addView(hero, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val free = DeviceState.freeData
        val low = free != null && free < (1L shl 30)
        body.addView(Ui.card(activity).apply {
            addView(Ui.fact(context, DeviceState.root, "Root access", DeviceState.managerVersion.ifEmpty { "granted" }))
            addView(Ui.divider(context, 42f))
            addView(Ui.fact(context, probe.mountNamespace, "Mount namespace", if (probe.mountNamespace) "supported" else "missing"))
            addView(Ui.divider(context, 42f))
            addView(Ui.fact(context, if (free == null) null else !low, "Free space on /data",
                if (free == null) "unknown" else "${Ui.size(free)} left", if (low) Palette.CAUTION else Palette.DIM))
            if (probe.foregroundSession) {
                addView(Ui.divider(context, 42f))
                addView(Ui.fact(context, false, "A foreground session holds the image", "run", Palette.CAUTION))
            }
        })
    }

    // Start always means start then attach: a session that died with the app
    // would be a bug, so the terminal opens on the background environment
    private fun start() {
        val task = Tasks.launch(Task("Starting Debian")) { t ->
            t.finish(t.exec(DebDroid.verb("start")))
        }
        SessionService.keepAlive(activity)
        TaskSheet.follow(activity, task, "Starting Debian",
            "Starting runs a filesystem check first, so a large image takes a little longer.") { ok ->
            DeviceState.refresh(activity) { if (ok && DeviceState.probe?.running == true) activity.show(MainActivity.TAB_TERMINAL) }
        }
        refresh()
    }

    private fun running(probe: io.github.nicup14.debdroid.core.Probe) {
        val body = page.body
        body.setPadding(dp(16), 0, dp(16), dp(14))

        body.addView(Ui.card(activity).apply {
            setPadding(dp(18), dp(18), dp(18), dp(16))
            addView(Ui.horizontal(context, 9f).apply {
                addView(View(context).apply {
                    background = Ui.rounded(context, Palette.RUNNING, 4f)
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                })
                addView(Ui.text(context, "Running", 20f, Palette.TEXT, Ui.bold))
                addView(Ui.space(context))
                addView(Ui.monoText(context, probe.started?.let { SessionService.uptime(it) } ?: "", 12f))
            })
            addView(Ui.body(context, describe(probe), 13f).apply { setPadding(0, dp(3), 0, dp(16)) })
            addView(Ui.horizontal(context, 8f).apply {
                layoutParams = Ui.margins(context, bottom = 16f)
                addView(Ui.stat(context, "${probe.processes ?: 0}", "procs"))
                addView(Ui.stat(context, "${probe.mounts ?: 0}", "mounts"))
                addView(Ui.stat(context, if (probe.swapOn) "on" else "off", "swap"))
                val held = probe.wakelock == "held"
                addView(Ui.stat(context, if (held) "on" else "off", "wakelock", if (held) Palette.RUNNING else Palette.TEXT))
            })
            addView(Ui.horizontal(context, 10f).apply {
                addView(Ui.button(context, "Open terminal", iconRes = R.drawable.ic_prompt, height = 46f).apply {
                    textSize = Ui.sp(context, 14.5f)
                    layoutParams = Ui.weighted(2f)
                    setOnClickListener { activity.show(MainActivity.TAB_TERMINAL) }
                })
                addView(Ui.button(context, "Stop", Ui.Kind.SECONDARY, height = 46f).apply {
                    layoutParams = Ui.weighted(1f)
                    Ui.enable(this, Tasks.current?.running != true)
                    setOnClickListener { stop() }
                })
            })
        })

        body.addView(Ui.header(activity, "Services").apply { setPadding(dp(4), dp(16), 0, dp(9)) })
        body.addView(Ui.card(activity).apply {
            addView(service(R.drawable.ic_server, "SSH server", "sshserver", listOf("pkill", "-x", "sshd"), probe.services.contains("ssh"),
                if (probe.services.contains("ssh")) "port 8022" else "stopped"))
            addView(Ui.divider(context, 63f))
            addView(service(R.drawable.ic_desktop, "VNC desktop", "vncserver", listOf("vncserver", "-kill", ":1"), probe.services.contains("vnc"),
                if (probe.services.contains("vnc")) "xfce4 · :1" else "stopped"))
        })

        body.addView(Ui.header(activity, "Quick").apply { setPadding(dp(4), dp(16), 0, dp(9)) })
        body.addView(Ui.horizontal(activity, 10f).apply {
            addView(quick(R.drawable.ic_resize, "Resize image") { TaskSheet.resize(activity) })
            addView(quick(R.drawable.ic_user, "Users") { ManageTab.addUser(activity) })
        })
    }

    private fun stop() {
        val task = SessionService.stopEnvironment(activity)
        TaskSheet.follow(activity, task, "Stopping Debian",
            "Every process in it is stopped and everything is unmounted.") { DeviceState.refresh(activity) }
        refresh()
    }

    // A service row: its state, and a switch that starts it in a terminal or stops it
    // NOTE: Started in a terminal tab rather than in the background, as the
    //       scripts ask for a password the first time and need somewhere to
    //       ask it.
    private fun service(iconRes: Int, label: String, script: String, stopCommand: List<String>, on: Boolean, detail: String): View {
        val (row, toggle) = Ui.switchRow(activity, label, detail, on) { checked ->
            if (checked) {
                TerminalTab.openCommand(activity, script, emptyList())
            } else {
                val task = Tasks.command("Stopping $label", DebDroid.verb("attach", *stopCommand.toTypedArray()))
                TaskSheet.follow(activity, task, "Stopping $label", "") { DeviceState.refresh(activity) }
            }
        }
        toggle.contentDescription = label
        row.addView(Ui.vertical(activity).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(context, Palette.INSET, 10f)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            addView(Ui.icon(context, iconRes, if (on) Palette.RUNNING else Palette.DIM))
        }, 0)
        (row.findViewWithTag<View>("detail") as? android.widget.TextView)?.typeface = Ui.mono
        return row
    }

    private fun quick(iconRes: Int, label: String, onClick: () -> Unit) = Ui.horizontal(activity, 11f).apply {
        minimumHeight = dp(60)
        setPadding(dp(14), 0, dp(14), 0)
        background = Ui.pressable(context, Ui.rounded(context, Palette.CARD, 14f, Palette.LINE), 14f)
        layoutParams = Ui.weighted(1f)
        isClickable = true
        addView(Ui.icon(context, iconRes, Palette.PRIMARY, 18f))
        addView(Ui.text(context, label, 13.5f, Palette.TEXT, Ui.medium))
        setOnClickListener { onClick() }
    }
}
