package io.github.nicup14.debdroid.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Root
import io.github.nicup14.debdroid.ui.Ui.dp

// Shows text DebDroid produced: the startup log, or the issue report
//
// The report is tests/device.sh, which already gathers everything a bug
// report needs and changes nothing. This screen runs it, shows it, and offers
// to share it.
class TextActivity : Screen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_LOG
        val report = kind == KIND_REPORT

        val page = Ui.Page(this)
        page.body.addView(Ui.backBar(this, if (report) "Issue report" else "Startup log") { finish() })
        page.body.addView(Ui.body(this,
            if (report) "Every check the device suite runs, read-only. Nothing here changes the device."
            else "Written on every background start, by \"debdroid.sh start\".", 12.5f).apply {
            setPadding(dp(20), 0, dp(20), dp(12))
        })
        val output = Ui.Output(this)
        page.body.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(560)).apply {
            setMargins(dp(16), 0, dp(16), dp(16))
        })
        val share = Ui.button(this, "Share", height = 48f)
        Ui.enable(share, false)
        page.actions.setPadding(dp(16), dp(8), dp(16), dp(16))
        page.actions.addView(share)
        setContentView(page)

        output.show(listOf(if (report) "Running the device checks, this takes a few seconds" else "Reading the log"))
        Thread {
            val text = if (report) {
                Root.run("NO_COLOR=1 sh ${DebDroid.SDHOME}/tests/device.sh").output
            } else {
                Root.read(DebDroid.LOG) ?: "No log yet: the environment has not been started in the background."
            }
            Handler(Looper.getMainLooper()).post {
                output.replace(text.lines())
                Ui.enable(share, true)
                share.setOnClickListener {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, if (report) "DebDroid issue report" else "DebDroid startup log")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share"))
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_LOG = "log"
        const val KIND_REPORT = "report"
    }
}
