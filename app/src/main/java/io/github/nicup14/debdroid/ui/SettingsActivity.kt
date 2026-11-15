package io.github.nicup14.debdroid.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.core.ConfFile
import io.github.nicup14.debdroid.core.Flag
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Root
import io.github.nicup14.debdroid.ui.Ui.dp
import io.github.nicup14.debdroid.work.BootHook

// Settings: the manager's CONFIG_* flags, and starting at boot
//
// The flags are read from the installed manager, which holds the defaults, and
// written to debdroid.conf, which the manager sources and no update replaces.
// Only a changed flag is written. Nothing is kept in the app, so the terminal
// and the app always agree about what is set.
class SettingsActivity : Screen() {

    private var defaults: List<Flag> = emptyList()
    private var conf = ""
    private var values: Map<String, String> = emptyMap()
    private var advancedOpen = false
    private lateinit var page: Ui.Page

    // The flags given their own rows; every other one is a device internal
    private val shown = setOf("CONFIG_ENV_FSCK", "CONFIG_ENV_WAKELOCK", "CONFIG_ENV_SWAP", "CONFIG_ENV_SWAPPINESS", "CONFIG_ENV_DNSSERVER")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = Ui.Page(this)
        page.body.addView(Ui.backBar(this, "Settings") { finish() })
        page.body.addView(Ui.body(this, "Reading the current flags").apply { setPadding(dp(20), dp(10), dp(20), 0) })
        setContentView(page)
        load()
    }

    private fun load() {
        Thread {
            val manager = Root.read(DebDroid.MANAGER).orEmpty()
            val current = Root.read(DebDroid.CONF).orEmpty()
            Handler(Looper.getMainLooper()).post {
                defaults = ConfFile.parseDefaults(manager)
                conf = current
                values = ConfFile.effective(defaults, conf)
                render()
            }
        }.start()
    }

    // Writes one flag, keeping every other line of the conf as it was
    private fun set(name: String, value: String) {
        val updated = try {
            ConfFile.withValue(conf, defaults, name, value)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "That value is not allowed.", Toast.LENGTH_SHORT).show()
            return
        } ?: run {
            values = ConfFile.effective(defaults, conf)
            return
        }
        conf = updated
        values = ConfFile.effective(defaults, conf)
        Thread {
            val result = Root.write(DebDroid.CONF, updated)
            Handler(Looper.getMainLooper()).post {
                if (!result.ok) Toast.makeText(this, "Could not save: ${result.output.trim()}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun on(name: String) = values[name] == "yes"

    private fun render() {
        page.body.removeAllViews()
        page.body.addView(Ui.backBar(this, "Settings") { finish() })
        val body = Ui.vertical(this).apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        page.body.addView(body)
        if (defaults.isEmpty()) {
            body.addView(Ui.body(this, "Could not read the manager's flags from ${DebDroid.MANAGER}."))
            return
        }

        val probe = DeviceState.probe
        body.addView(Ui.header(this, "Startup"))
        body.addView(Ui.card(this, 14f).apply {
            if (probe != null) {
                val mechanism = BootHook.current(context, probe)
                val (row, toggle) = Ui.switchRow(context, "Start when the device boots", BootHook.describe(mechanism),
                    mechanism != BootHook.Mechanism.NONE) { checked ->
                    Thread {
                        val message = if (checked) BootHook.enable(context, probe) else BootHook.disable(context, probe)
                        DeviceState.refresh(context) { render() }
                        if (message != null && !checked) Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
                toggle.contentDescription = "Start when the device boots"
                addView(row)
                addView(Ui.divider(context, 15f))
            }
            addView(flagSwitch("CONFIG_ENV_FSCK", "Check the filesystem first", "Slower to start, catches corruption"))
        })

        body.addView(Ui.header(this, "Power").apply { setPadding(dp(4), dp(12), 0, dp(7)) })
        body.addView(Ui.card(this, 14f).apply {
            addView(flagSwitch("CONFIG_ENV_WAKELOCK", "Keep the device awake", "Needed for a server; costs battery"))
        })

        body.addView(Ui.header(this, "Swap").apply { setPadding(dp(4), dp(12), 0, dp(7)) })
        body.addView(Ui.card(this, 14f).apply {
            addView(flagSwitch("CONFIG_ENV_SWAP", "Use a swapfile",
                if (probe?.swapfile == true) "On /data, at the next start" else "Create one in Manage first"))
            addView(Ui.divider(context, 15f))
            addView(swappiness())
        })

        body.addView(Ui.header(this, "Network").apply { setPadding(dp(4), dp(12), 0, dp(7)) })
        body.addView(Ui.card(this, 14f).apply {
            val dns = values["CONFIG_ENV_DNSSERVER"].orEmpty()
            addView(Ui.row(context, "Fallback DNS", dns) {
                TaskSheet.ask(this@SettingsActivity, "Fallback DNS", "Only used when android reports no dns server.",
                    "address", dns, valid = {
                        if (Regex("^[0-9a-fA-F.:]+$").matches(it)) null else "An ip address, such as 1.1.1.1"
                    }) { value ->
                    set("CONFIG_ENV_DNSSERVER", value)
                    render()
                }
            })
        })

        // The /dev flags and their neighbours, behind a door
        // NOTE: Getting CONFIG_DEV_OVERLAY and its neighbours wrong is how the
        //       overlay silently never mounted for a year. The defaults are
        //       correct, and the door says so.
        val internals = defaults.filter { it.name !in shown }
        body.addView(Ui.horizontal(this, 12f).apply {
            layoutParams = Ui.margins(context, top = 12f)
            minimumHeight = dp(54)
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = Ui.pressable(context, Ui.rounded(context, Palette.CARD, 14f, Palette.LINE), 14f)
            isClickable = true
            addView(Ui.icon(context, R.drawable.ic_alert, Palette.CAUTION))
            addView(Ui.vertical(context).apply {
                layoutParams = Ui.weighted(1f)
                addView(Ui.text(context, "Device internals", 14f, Palette.TEXT))
                addView(Ui.text(context, "${internals.size} more flags · defaults are correct", 11f, Palette.DIM).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(Ui.icon(context, if (advancedOpen) R.drawable.ic_arrow_down else R.drawable.ic_chevron, Palette.FAINT, 15f))
            setOnClickListener {
                advancedOpen = !advancedOpen
                render()
            }
        })
        if (advancedOpen) {
            body.addView(Ui.card(this, 14f).apply {
                layoutParams = Ui.margins(context, top = 8f)
                for ((i, flag) in internals.withIndex()) {
                    if (i > 0) addView(Ui.divider(context, 15f))
                    if (flag.isBoolean) {
                        addView(flagSwitch(flag.name, flag.name.removePrefix("CONFIG_"), flag.comment.ifEmpty { "default: ${flag.default}" }, mono = true))
                    } else {
                        val value = values[flag.name].orEmpty()
                        addView(Ui.row(context, flag.name.removePrefix("CONFIG_"), value.ifEmpty { "empty" }) {
                            TaskSheet.ask(this@SettingsActivity, flag.name.removePrefix("CONFIG_"),
                                flag.comment.ifEmpty { "Default: ${flag.default.ifEmpty { "empty" }}" }, "value", value,
                                valid = { if (it.isEmpty() || ConfFile.isSafeValue(it)) null else "Letters, digits and . - _ : / only" }) { v ->
                                set(flag.name, v)
                                render()
                            }
                        })
                    }
                }
            })
        }

        body.addView(Ui.text(this, "Saved to ${DebDroid.CONF}, the file the manager reads at every start. Only what you change is written there, and updates never replace it.",
            11f, Palette.DIM).apply { setPadding(dp(4), dp(12), dp(4), 0) })
        body.addView(Ui.text(this, "Changes apply the next time Debian starts.", 11f, Palette.DIM).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        })
    }

    private fun flagSwitch(name: String, label: String, detail: String, mono: Boolean = false): LinearLayout {
        val (row, toggle) = Ui.switchRow(this, label, detail, on(name)) { set(name, if (it) "yes" else "no") }
        toggle.contentDescription = label
        if (mono) (row.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.let { (it as TextView).typeface = Ui.mono }
        return row
    }

    // Swappiness, 0 to 100, as a slider
    private fun swappiness() = Ui.vertical(this).apply {
        setPadding(dp(15), dp(12), dp(15), dp(14))
        val value = values["CONFIG_ENV_SWAPPINESS"]?.toIntOrNull() ?: 60
        val label = Ui.text(context, "$value", 13f, Palette.TEXT, android.graphics.Typeface.create(Ui.mono, android.graphics.Typeface.BOLD))
        addView(Ui.horizontal(context, 8f).apply {
            addView(Ui.text(context, "Swappiness", 14f, Palette.TEXT))
            addView(Ui.space(context))
            addView(label)
        })
        addView(SeekBar(context).apply {
            max = 100
            progress = value
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.PRIMARY)
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.TEXT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.LINE_INSET)
            contentDescription = "Swappiness"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "$p"
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) = set("CONFIG_ENV_SWAPPINESS", "${bar.progress}")
            })
        }, Ui.margins(context, top = 6f))
        addView(Ui.horizontal(context).apply {
            addView(Ui.text(context, "keep in RAM", 10.5f, Palette.DIM))
            addView(Ui.space(context))
            addView(Ui.text(context, "swap eagerly", 10.5f, Palette.DIM))
        })
    }
}
