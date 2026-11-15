package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.ui.Ui.dp

// Uninstall, with the one choice that matters made explicit
//
// This is the only screen where getting it wrong deletes gigabytes of the
// user's work. So deleting the image is a separate
// switch that starts off, and nothing happens until the word is typed.
class UninstallActivity : Screen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val probe = DeviceState.probe

        val page = Ui.Page(this)
        page.body.addView(Ui.backBar(this, "Uninstall DebDroid") { finish() })
        val body = Ui.vertical(this).apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        page.body.addView(body)

        body.addView(Ui.body(this,
            "Removes the scripts in /sdcard/debdroid and the tools in /data/local/debdroid" +
                (if (probe?.moduleInstalled == true) ", and marks the DebDroid module for removal at the next reboot." else ".") +
                " This app stays installed; uninstall it from android's settings.", 13.5f).apply {
            setPadding(dp(4), 0, dp(4), dp(16))
        })

        var deleteImage = false
        var deleteConfig = false
        body.addView(Ui.card(this, 14f).apply {
            // NOTE: The uninstaller removes the configuration only together
            //       with the image ("--all"), so that switch is offered only
            //       once the image switch is on, rather than promising a
            //       combination the shell does not do.
            val (configRow, configToggle) = Ui.switchRow(context, "Also delete my configuration",
                "environment, debdroid_env.sh and debdroid.conf", false) { deleteConfig = it }
            Ui.enable(configRow, false)
            val (imageRow, _) = Ui.switchRow(context, "Also delete the debian image",
                "${Ui.size(probe?.imageSize)} · everything installed inside Debian", false) {
                deleteImage = it
                Ui.enable(configRow, it)
                if (!it) {
                    configToggle.checked = false
                    deleteConfig = false
                }
            }
            addView(imageRow)
            addView(Ui.divider(context, 15f))
            addView(configRow)
        })
        if (probe?.running == true) {
            body.addView(Ui.text(this, "Debian is running. Stop it on the Home screen first; the uninstaller refuses otherwise.",
                12.5f, Palette.CAUTION).apply { setPadding(dp(4), dp(14), dp(4), 0) })
        }

        val confirm = EditText(this).apply {
            hint = "Type UNINSTALL to confirm"
            setHintTextColor(Palette.DIM)
            setTextColor(Palette.TEXT)
            typeface = Ui.mono
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            background = Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val go = Ui.button(this, "Uninstall", Ui.Kind.CAUTION, R.drawable.ic_trash, 48f).apply {
            layoutParams = Ui.margins(context, top = 10f)
            Ui.enable(this, false)
        }
        confirm.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Ui.enable(go, s.toString().trim() == "UNINSTALL")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        go.setOnClickListener {
            val args = ArrayList<String>()
            args += "uninstall"
            args += "--yes"
            // NOTE: "--all" is the uninstaller's flag for removing the image and
            //       the configuration together, so keeping the configuration
            //       while deleting the image is "--image" alone.
            if (deleteImage && deleteConfig) args += "--all" else if (deleteImage) args += "--image"
            TaskSheet.run(this, "Uninstalling", "", DebDroid.verb(*args.toTypedArray())) { ok ->
                if (ok) {
                    DeviceState.refresh(this, full = true) {
                        startActivity(Intent(this, InstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        page.actions.setPadding(dp(16), dp(8), dp(16), dp(20))
        page.actions.addView(confirm)
        page.actions.addView(go)
        setContentView(page)
    }
}
