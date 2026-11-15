package io.github.nicup14.debdroid.ui

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nicup14.debdroid.term.TerminalScheme
import io.github.nicup14.debdroid.ui.Ui.dp

// Terminal preferences: size, colours, keyboard, scrollback
//
// These are the app's own, as they describe how this terminal draws, not how
// DebDroid runs. Autocorrect is deliberately not among them.
class TerminalPrefsActivity : Screen() {

    private lateinit var sample: TextView
    private lateinit var sizeLabel: TextView
    private val swatches = HashMap<TerminalScheme, LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = prefs(this)

        val page = Ui.Page(this)
        page.body.addView(Ui.backBar(this, "Terminal") { finish() })
        val body = Ui.vertical(this, 11f).apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        page.body.addView(body)

        // A live sample, redrawn as the size and colours change
        sample = Ui.text(this, "", 12f, Palette.OUTPUT, Ui.mono).apply {
            setLineSpacing(0f, 1.3f)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            minHeight = dp(128)
        }
        body.addView(sample)

        body.addView(Ui.vertical(this).apply {
            addView(Ui.header(context, "Text size").apply { setPadding(dp(4), 0, 0, dp(7)) })
            addView(Ui.horizontal(context, 12f).apply {
                background = Ui.rounded(context, Palette.CARD, 14f, Palette.LINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(stepButton("A−", "Smaller text", 15f) { step(-1) })
                sizeLabel = Ui.text(context, "", 20f, Palette.TEXT, android.graphics.Typeface.create(Ui.mono, android.graphics.Typeface.BOLD))
                addView(Ui.vertical(context).apply {
                    gravity = Gravity.CENTER
                    layoutParams = Ui.weighted(1f)
                    addView(sizeLabel)
                    addView(Ui.text(context, "points · 9 to 28", 10.5f, Palette.DIM).apply { setPadding(0, dp(3), 0, 0) })
                })
                addView(stepButton("A+", "Larger text", 19f) { step(1) })
            })
        })

        body.addView(Ui.vertical(this).apply {
            addView(Ui.header(context, "Colours").apply { setPadding(dp(4), 0, 0, dp(7)) })
            addView(Ui.horizontal(context, 9f).apply {
                for ((scheme, label) in listOf(TerminalScheme.DEBDROID to "DebDroid", TerminalScheme.CONTRAST to "Contrast",
                        TerminalScheme.DAYLIGHT to "Daylight")) {
                    addView(Ui.vertical(context).apply {
                        layoutParams = Ui.weighted(1f)
                        isClickable = true
                        contentDescription = "$label colours"
                        setOnClickListener {
                            prefs.edit().putString(SCHEME, scheme.name).apply()
                            paint()
                        }
                        val swatch = Ui.horizontal(context, 4f).apply {
                            minimumHeight = dp(42)
                            setPadding(dp(10), 0, dp(10), 0)
                            for (index in listOf(2, 4, 1)) addView(android.view.View(context).apply {
                                background = Ui.rounded(context, scheme.color(index), 2f)
                                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                            })
                        }
                        swatches[scheme] = swatch
                        addView(swatch)
                        addView(Ui.text(context, label, 11f, Palette.TEXT_SOFT).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(5), 0, 0)
                        }, Ui.matchWidth())
                    })
                }
            })
        })

        body.addView(Ui.vertical(this).apply {
            addView(Ui.header(context, "Keyboard").apply { setPadding(dp(4), 0, 0, dp(7)) })
            addView(Ui.card(context, 14f).apply {
                addView(Ui.switchRow(context, "Extra key row", "Esc, Tab, Ctrl, Alt, arrows", prefs.getBoolean(EXTRA_KEYS, true)) {
                    prefs.edit().putBoolean(EXTRA_KEYS, it).apply()
                }.first)
                addView(Ui.divider(context, 15f))
                addView(Ui.switchRow(context, "Volume keys send Ctrl", "Vol-down + C becomes Ctrl-C", prefs.getBoolean(VOLUME_CTRL, true)) {
                    prefs.edit().putBoolean(VOLUME_CTRL, it).apply()
                }.first)
                addView(Ui.divider(context, 15f))
                addView(Ui.switchRow(context, "Back button sends Esc", "Hold it to leave the session", prefs.getBoolean(BACK_ESC, false)) {
                    prefs.edit().putBoolean(BACK_ESC, it).apply()
                }.first)
            })
        })

        body.addView(Ui.vertical(this).apply {
            addView(Ui.header(context, "Scrollback").apply { setPadding(dp(4), 0, 0, dp(7)) })
            val row = Ui.row(context, "${prefs.getInt(SCROLLBACK, 2000)} lines", "", valueMono = false) {}
            row.background = Ui.pressable(context, Ui.rounded(context, Palette.CARD, 14f, Palette.LINE), 14f)
            row.setOnClickListener {
                val choices = intArrayOf(500, 1000, 2000, 5000, 10000)
                AlertDialog.Builder(this@TerminalPrefsActivity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Scrollback")
                    .setItems(choices.map { "$it lines" }.toTypedArray()) { _, which ->
                        prefs.edit().putInt(SCROLLBACK, choices[which]).apply()
                        recreate()
                    }.show()
            }
            addView(row)
            addView(Ui.text(context, "More lines cost memory on an older device. New sessions use the new size.", 10.5f, Palette.DIM).apply {
                setPadding(dp(4), dp(6), 0, 0)
            })
        })

        body.addView(Ui.text(this, "Autocorrect, autocapitalise and word suggestions are always off: a keyboard that rewrites cd to CD is not a preference.",
            10.5f, Palette.DIM).apply { setPadding(dp(4), 0, dp(4), 0) })

        setContentView(page)
        paint()
    }

    private fun stepButton(label: String, description: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = Ui.sp(context, size)
        setTextColor(Palette.TEXT)
        typeface = Ui.medium
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        background = Ui.pressable(context, Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET), 12f)
        setOnClickListener { onClick() }
    }

    private fun step(by: Int) {
        val prefs = prefs(this)
        prefs.edit().putInt(SIZE, (prefs.getInt(SIZE, defaultSize(this)) + by).coerceIn(9, 28)).apply()
        paint()
    }

    // Redraws the sample and the chosen swatch
    private fun paint() {
        val prefs = prefs(this)
        val size = prefs.getInt(SIZE, defaultSize(this))
        val scheme = runCatching { TerminalScheme.valueOf(prefs.getString(SCHEME, "DEBDROID")!!) }.getOrDefault(TerminalScheme.DEBDROID)
        sizeLabel.text = "$size"
        sample.textSize = size.toFloat()
        sample.background = Ui.rounded(this, scheme.background, 13f, Palette.LINE)
        val text = SpannableStringBuilder()
        fun add(s: String, color: Int) {
            val start = text.length
            text.append(s)
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (line in 0..1) {
            add("root@debdroid", scheme.color(2))
            add(":", scheme.dim)
            add("~", scheme.color(4))
            add("# ", scheme.dim)
            if (line == 0) {
                add("df -h /\n", scheme.foreground)
                add("Filesystem       Size  Used Avail\n", scheme.dim)
                add("/dev/block/loop0 7.9G  4.6G  3.0G\n", scheme.foreground)
            } else {
                add("█", scheme.cursor)
            }
        }
        sample.text = text
        for ((s, view) in swatches) {
            view.background = Ui.rounded(this, s.background, 11f, if (s == scheme) Palette.PRIMARY else Palette.LINE_INSET, if (s == scheme) 2f else 1f)
        }
    }

    companion object {
        const val SIZE = "size"
        const val SCHEME = "scheme"
        const val EXTRA_KEYS = "extra_keys"
        const val VOLUME_CTRL = "volume_ctrl"
        const val BACK_ESC = "back_esc"
        const val SCROLLBACK = "scrollback"

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)

        // The terminal's size until the user picks one: larger on a tablet,
        // which is read from further away
        fun defaultSize(context: Context): Int =
            if (context.resources.configuration.smallestScreenWidthDp >= 600) 16 else 14
    }
}
