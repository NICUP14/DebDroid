package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.Pinned
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.core.InstallAction
import io.github.nicup14.debdroid.core.Probe
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Root
import io.github.nicup14.debdroid.service.SessionService
import io.github.nicup14.debdroid.ui.Ui.dp
import io.github.nicup14.debdroid.work.Install
import io.github.nicup14.debdroid.work.Task
import io.github.nicup14.debdroid.work.Tasks

// Install, detected and installing: the boards a first run walks through
//
// The screen never asks what to do. The probe decided on one action, and this
// shows what it found, what will happen, and one button.
class InstallActivity : Screen(), Task.Listener, DeviceState.Listener {

    private var task: Task? = null
    private var output: Ui.Output? = null
    private var stepViews = ArrayList<LinearLayout>()
    private var footer: LinearLayout? = null
    private var summary: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val running = Tasks.current
        if (running != null && running.title == "Installing" && running.running) {
            showInstalling(running)
        } else {
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        DeviceState.addListener(this)
    }

    override fun onPause() {
        super.onPause()
        DeviceState.removeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        task?.removeListener(this)
    }

    override fun onDeviceState() {
        if (task == null) render()
    }

    // Draws the screen for whatever the probe decided
    private fun render() {
        val probe = DeviceState.probe
        val action = DeviceState.action
        when {
            !DeviceState.rootChecked -> setContentView(checking())
            action == InstallAction.NONE -> openHome()
            action.blocks || action == InstallAction.FOREGROUND_SESSION ||
                action == InstallAction.ENVIRONMENT_RUNNING -> setContentView(blocked(action, probe))
            probe == null -> setContentView(checking())
            !probe.scriptsPresent && action != InstallAction.IMAGE_DAMAGED -> setContentView(fresh(action, probe))
            else -> setContentView(detected(action, probe))
        }
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // Shown while root is asked for and the probe runs
    private fun checking(): View = Ui.vertical(this).apply {
        setBackgroundColor(Palette.GROUND)
        gravity = Gravity.CENTER
        addView(Ui.mark(context, 56f))
        addView(Ui.body(context, "Asking for root and checking this device").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
    }

    // The top of every install board: the mark, a title, one line
    private fun intro(page: Ui.Page, title: String, line: String, markSize: Float = 56f) {
        page.body.setPadding(dp(20), dp(44), dp(20), dp(8))
        page.body.addView(Ui.mark(this, markSize).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(20)
        })
        page.body.addView(Ui.title(this, title, 27f).apply { setLineSpacing(0f, 1.15f) })
        page.body.addView(Ui.body(this, line).apply { setPadding(0, dp(9), 0, dp(22)) })
    }

    // Install, nothing found
    private fun fresh(action: InstallAction, probe: Probe): View {
        val page = Ui.Page(this)
        intro(page, "Install Debian\non this device", "A real glibc userland, not an emulator. Nothing to configure.")

        val pin = Pinned.IMAGES[probe.arch]
        val free = DeviceState.freeSdcard
        val roomy = free == null || pin == null || !action.downloads || free > pin.size + (256L shl 20)

        page.body.addView(Ui.card(this).apply {
            addView(Ui.fact(context, true, "Root access granted", DeviceState.managerVersion.ifEmpty { "su" }))
            addView(Ui.divider(context, 42f))
            addView(Ui.fact(context, true, "${probe.arch} · android ${Build.VERSION.RELEASE}", "supported"))
            addView(Ui.divider(context, 42f))
            addView(Ui.fact(context, roomy, "Space for the image",
                if (free == null) "unknown" else "${Ui.size(free)} free",
                if (roomy) Palette.DIM else Palette.CAUTION))
        })

        page.body.addView(Ui.header(this, "What happens next").apply { setPadding(dp(2), dp(16), 0, dp(8)) })
        page.body.addView(Ui.card(this).apply {
            setPadding(dp(15), dp(15), dp(15), dp(15))
            addView(stepLine("1", "Unpack the scripts and tools", "bundled in this app"))
            if (action.downloads) {
                addView(stepLine("2", "Download the debian image",
                    "${Ui.size(pin?.size)} unpacked · wi-fi · checksum pinned in this app"))
            } else {
                addView(stepLine("2", "Keep the debian image already here", Ui.size(probe.imageSize)))
            }
            addView(stepLine("3", "Install as root", "/sdcard/debdroid · /data/local/debdroid", last = true))
        })

        val boot = CheckBox(this).apply {
            text = "Start Debian when the device boots"
            setTextColor(Palette.TEXT_MID)
            textSize = Ui.sp(context, 12.5f)
            isChecked = true
            buttonTintList = ColorStateList.valueOf(Palette.PRIMARY)
        }
        page.actions.setPadding(dp(20), dp(10), dp(20), dp(24))
        page.actions.addView(boot)
        page.actions.addView(Ui.button(this, "Install").apply {
            layoutParams = Ui.margins(context, top = 10f, bottom = 12f)
            Ui.enable(this, roomy)
            setOnClickListener { begin(action, probe, boot.isChecked) }
        })
        if (action.downloads) {
            page.actions.addView(fileButton(action, probe) { boot.isChecked }.apply {
                layoutParams = Ui.margins(context, bottom = 12f)
            })
        }
        val checked = if (probe.imagePresent) "found a debian image to reuse." else "no earlier install to reuse."
        page.actions.addView(Ui.text(this, "Checked /sdcard/debdroid and /data/local/debdroid: $checked", 11f, Palette.DIM).apply {
            gravity = Gravity.CENTER
        })
        return page
    }

    private fun stepLine(number: String, title: String, detail: String, last: Boolean = false) = Ui.horizontal(this, 11f).apply {
        gravity = Gravity.TOP
        layoutParams = Ui.margins(context, bottom = if (last) 0f else 13f)
        addView(Ui.text(context, number, 11f, Palette.PRIMARY, Ui.mono).apply { setPadding(0, dp(1), 0, 0) })
        addView(Ui.vertical(context).apply {
            layoutParams = Ui.weighted(1f)
            addView(Ui.text(context, title, 13f, Palette.TEXT, Ui.medium))
            addView(Ui.monoText(context, detail, 10.5f).apply { setPadding(0, dp(3), 0, 0) })
        })
    }

    // Install, detected: what the probe found and the one thing to do
    private fun detected(action: InstallAction, probe: Probe): View {
        val page = Ui.Page(this)
        val installed = probe.installedVersion ?: "an unknown version"
        val by = if (probe.moduleInstalled) "installed by the Magisk module" else "installed on this device"
        intro(page, "DebDroid is already\non this device", "Version $installed, $by.", 44f)

        page.body.addView(Ui.card(this).apply {
            addView(Ui.fact(context, true, "Scripts", "v$installed"))
            addView(Ui.divider(context, 41f))
            addView(Ui.fact(context, probe.binariesOk, "Bundled tools", if (probe.binariesOk) "executable" else "not executable",
                if (probe.binariesOk) Palette.DIM else Palette.CAUTION))
            addView(Ui.divider(context, 41f))
            val image = when {
                probe.imageOk -> "${Ui.size(probe.imageSize)} · ext2"
                probe.imagePresent -> "not a filesystem"
                probe.imagePartial -> "download unfinished"
                else -> "missing"
            }
            addView(Ui.fact(context, probe.imageOk, "Debian image", image, if (probe.imageOk) Palette.DIM else Palette.CAUTION))
            addView(Ui.divider(context, 41f))
            addView(Ui.fact(context, null, "Environment", if (probe.running) "running" else "stopped"))
        })

        val (heading, text) = when (action) {
            InstallAction.UPDATE -> "An update is available" to
                "Version ${Pinned.VERSION} replaces $installed. Your debian image and settings are kept."
            InstallAction.REPAIR -> "The tools cannot run" to
                "They are installed but lost their execute permission, which a flash can do. Repairing runs the installer again; nothing is downloaded."
            InstallAction.DOWNLOAD_IMAGE -> "The debian image is missing" to
                "The scripts are installed but there is no image to run. It is downloaded and checked against the checksum pinned in this app."
            InstallAction.REFETCH -> "A download was interrupted" to
                "The unfinished file is discarded and the image is fetched again."
            InstallAction.NEWER_INSTALLED -> "A newer DebDroid is installed" to
                "$installed is newer than this app (${Pinned.VERSION}), and the app never downgrades. Update the app to manage it."
            InstallAction.IMAGE_DAMAGED -> "The debian image is not usable" to
                "It does not carry an ext2 filesystem. A fresh image replaces it, and anything in the old one is lost."
            else -> "Ready" to ""
        }
        page.body.addView(Ui.card(this, stroke = Palette.CAUTION_CARD).apply {
            layoutParams = Ui.margins(context, top = 15f)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            addView(Ui.horizontal(context, 9f).apply {
                addView(Ui.icon(context, if (action == InstallAction.IMAGE_DAMAGED) R.drawable.ic_alert else R.drawable.ic_refresh, Palette.CAUTION, 15f))
                addView(Ui.text(context, heading, 13.5f, Palette.TEXT, Ui.medium))
            })
            addView(Ui.text(context, text, 12f, Palette.TEXT_MID).apply {
                setLineSpacing(0f, 1.4f)
                setPadding(0, dp(9), 0, 0)
            })
        })

        page.actions.setPadding(dp(20), dp(10), dp(20), dp(24))
        when (action) {
            InstallAction.NEWER_INSTALLED -> page.actions.addView(Ui.button(this, "Continue with $installed").apply {
                setOnClickListener {
                    DeviceState.updateDeclined = true
                    openHome()
                }
            })
            InstallAction.IMAGE_DAMAGED -> {
                // NOTE: Typed, not tapped. Red is the brand, so a destructive
                //       action cannot lean on colour, and this one deletes a
                //       file that may hold someone's work.
                val confirm = EditText(this).apply {
                    hint = "Type REPLACE to confirm"
                    setHintTextColor(Palette.DIM)
                    setTextColor(Palette.TEXT)
                    typeface = Ui.mono
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    background = Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                val go = Ui.button(this, "Replace the image", Ui.Kind.CAUTION).apply {
                    layoutParams = Ui.margins(context, top = 10f)
                    Ui.enable(this, false)
                    setOnClickListener { begin(action, probe, false) }
                }
                confirm.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) = Ui.enable(go, s.toString().trim() == "REPLACE")
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                page.actions.addView(confirm)
                page.actions.addView(go)
                page.actions.addView(fileButton(action, probe) { false }.apply {
                    layoutParams = Ui.margins(context, top = 11f)
                })
            }
            else -> {
                val label = when (action) {
                    InstallAction.UPDATE -> "Update to ${Pinned.VERSION}"
                    InstallAction.REPAIR -> "Repair"
                    else -> "Download the image"
                }
                page.actions.addView(Ui.button(this, label).apply {
                    setOnClickListener { begin(action, probe, false) }
                })
                if (action.downloads) {
                    page.actions.addView(fileButton(action, probe) { false }.apply {
                        layoutParams = Ui.margins(context, top = 11f)
                    })
                }
                if (action == InstallAction.UPDATE) {
                    page.actions.addView(Ui.button(this, "Keep $installed for now", Ui.Kind.SECONDARY, height = 46f).apply {
                        layoutParams = Ui.margins(context, top = 11f)
                        setOnClickListener {
                            DeviceState.updateDeclined = true
                            openHome()
                        }
                    })
                    page.actions.addView(Ui.text(this, "Your settings are kept. An environment file you changed stays, with the new default saved beside it as .new.",
                        11f, Palette.DIM).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(13), 0, 0)
                    })
                }
            }
        }
        return page
    }

    // The refusals, each saying why plainly and what to do
    private fun blocked(action: InstallAction, probe: Probe?): View {
        val page = Ui.Page(this)
        val (title, line) = when (action) {
            InstallAction.NO_ROOT -> if (Root.su == "su" && !DeviceState.root)
                "Root access is needed" to "DebDroid runs Debian as root, and this device did not grant it. If it is rooted, allow DebDroid in your root manager (Magisk, KernelSU or APatch), then try again. Without root, DebDroid cannot run here."
            else "Root access is needed" to "DebDroid runs Debian as root, and the root manager refused. Allow DebDroid in it, then try again."
            InstallAction.UNSUPPORTED_ARCH -> "This device is not supported yet" to
                "It is ${probe?.abi?.ifEmpty { "an unknown architecture" }}. DebDroid ships arm64 binaries only for now, so it cannot run here. Nothing was installed."
            InstallAction.PAYLOAD_BROKEN -> "The app's tools could not start" to
                "The scripts bundled in this app did not unpack correctly. Reinstall the app, then try again."
            InstallAction.FOREGROUND_SESSION -> "A session is using the debian image" to
                "Something started with \"debdroid.sh run\" holds the image. Exit that session, then try again."
            InstallAction.ENVIRONMENT_RUNNING -> "Stop Debian to continue" to
                "Debian is running, and the next step replaces files it is using. Stop it first; it starts again from Home."
            else -> "Cannot continue" to ""
        }
        intro(page, title, line)
        page.actions.setPadding(dp(20), dp(10), dp(20), dp(24))
        if (action == InstallAction.ENVIRONMENT_RUNNING) {
            page.actions.addView(Ui.button(this, "Stop Debian and continue").apply {
                setOnClickListener {
                    TaskSheet.follow(this@InstallActivity, SessionService.stopEnvironment(context), "Stopping Debian",
                        "Every process in it is stopped and everything is unmounted.") {
                        DeviceState.refresh(context, full = true) { render() }
                    }
                }
            })
            page.actions.addView(Ui.button(this, "Not now", Ui.Kind.SECONDARY, height = 46f).apply {
                layoutParams = Ui.margins(context, top = 11f)
                setOnClickListener {
                    DeviceState.updateDeclined = true
                    openHome()
                }
            })
            return page
        }
        if (action != InstallAction.UNSUPPORTED_ARCH) {
            page.actions.addView(Ui.button(this, "Try again").apply {
                setOnClickListener {
                    setContentView(checking())
                    DeviceState.refresh(context, full = true) { render() }
                }
            })
        }
        return page
    }

    // Starts the install, which carries on without this screen
    private fun begin(action: InstallAction, probe: Probe, startAtBoot: Boolean, imageFile: Uri? = null) {
        SessionService.keepAlive(this)
        showInstalling(Install.launch(this, action, probe, startAtBoot, imageFile))
    }

    // The offline way in: DebDroid-module-offline.zip, which carries the image
    // NOTE: For a device with no usable network, or an old one whose download
    //       fails. Only that zip is accepted; see ImageSource.
    private var pendingAction: InstallAction? = null
    private var pendingProbe: Probe? = null
    private var pendingBoot = false

    private fun fileButton(action: InstallAction, probe: Probe, boot: () -> Boolean) =
        Ui.button(this, "Use the offline installer instead", Ui.Kind.SECONDARY, R.drawable.ic_import, 46f).apply {
            setOnClickListener {
                pendingAction = action
                pendingProbe = probe
                pendingBoot = boot()
                pickImage()
            }
        }

    private fun pickImage() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, REQUEST_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val action = pendingAction ?: DeviceState.action
        val probe = pendingProbe ?: DeviceState.probe ?: return
        // NOTE: Kept for as long as the install takes, which outlives this
        //       screen when the user leaves it.
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not every provider offers it; the grant still lasts the process
        }
        begin(action, probe, pendingBoot, uri)
    }

    companion object {
        private const val REQUEST_IMAGE = 7
    }

    // Installing: the steps, the pinned checksum and the live output
    private fun showInstalling(t: Task) {
        task?.removeListener(this)
        task = t
        val page = Ui.Page(this)
        page.body.setPadding(dp(20), dp(42), dp(20), dp(8))
        page.body.addView(Ui.title(this, "Installing"))
        page.body.addView(Ui.body(this, "You can leave this screen. It keeps going.", 13f).apply { setPadding(0, dp(7), 0, dp(22)) })

        stepViews.clear()
        val steps = Ui.vertical(this)
        for (step in t.steps) {
            val row = Ui.horizontal(this, 12f).apply {
                setPadding(0, dp(9), 0, dp(9))
            }
            stepViews += row
            steps.addView(row)
        }
        page.body.addView(steps)

        val probe = DeviceState.probe
        val pin = probe?.let { Pinned.IMAGES[it.arch] }
        if (pin != null) {
            page.body.addView(Ui.card(this, 14f).apply {
                layoutParams = Ui.margins(context, top = 18f, bottom = 16f)
                setPadding(dp(15), dp(14), dp(15), dp(14))
                addView(Ui.horizontal(context, 8f).apply {
                    addView(Ui.icon(context, R.drawable.ic_shield, Palette.RUNNING, 14f))
                    addView(Ui.header(context, "Expected sha256").apply { setPadding(0, 0, 0, 0) })
                })
                addView(Ui.monoText(context, pin.sha256, 10.5f, Palette.OUTPUT).apply { setPadding(0, dp(8), 0, 0) })
                val fromFile = t.steps.getOrNull(1)?.label?.startsWith("Image taken") == true
                addView(Ui.text(context,
                    if (fromFile) "Shipped inside this app. A file that does not match is shown to you, with both checksums, before anything is installed."
                    else "Shipped inside this app, never taken from the download. A mismatch discards the file and installs nothing.",
                    11f, Palette.DIM).apply { setPadding(0, dp(9), 0, 0) })
            })
        }

        page.body.addView(Ui.horizontal(this, 8f).apply {
            layoutParams = Ui.margins(context, bottom = 8f)
            addView(Ui.header(context, "Output").apply { setPadding(0, 0, 0, 0) })
            addView(Ui.space(context))
            addView(Ui.monoText(context, "stdout + stderr", 10.5f))
        })
        output = Ui.Output(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
        }
        page.body.addView(output)

        summary = Ui.text(this, "", 13f, Palette.TEXT_SOFT).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 0, 0, dp(12))
        }
        footer = page.actions
        page.actions.setPadding(dp(20), dp(10), dp(20), dp(24))
        setContentView(page)
        t.addListener(this)
    }

    override fun onTaskChanged(task: Task) {
        for ((i, step) in task.steps.withIndex()) {
            val row = stepViews.getOrNull(i) ?: continue
            row.removeAllViews()
            row.alpha = if (step.status == Task.Step.Status.WAITING || step.status == Task.Step.Status.SKIPPED) 0.45f else 1f
            row.addView(stepMark(step.status))
            row.addView(Ui.text(this, step.label, 14f,
                if (step.status == Task.Step.Status.ACTIVE) Palette.TEXT else Palette.TEXT_SOFT,
                if (step.status == Task.Step.Status.ACTIVE) Ui.medium else Ui.sans).apply { layoutParams = Ui.weighted(1f) })
            val detail = if (step.status == Task.Step.Status.SKIPPED && step.detail.isEmpty()) "skipped" else step.detail
            if (detail.isNotEmpty()) row.addView(Ui.monoText(this, detail, 11f))
        }
        output?.show(task.lines())

        val actions = footer ?: return
        actions.removeAllViews()
        if (summary?.parent == null) actions.addView(summary)
        val question = task.question
        if (task.running && question != null) {
            summary?.visibility = View.GONE
            actions.addView(questionCard(task, question))
            return
        }
        if (task.running) {
            summary?.visibility = View.GONE
            actions.addView(Ui.button(this, "Cancel", Ui.Kind.SECONDARY, height = 46f).apply {
                setOnClickListener { task.cancel() }
            })
            return
        }
        summary?.text = task.summary
        summary?.visibility = if (task.summary.isEmpty()) View.GONE else View.VISIBLE
        DeviceState.refresh(this, full = true)
        if (task.state == Task.State.DONE) {
            actions.addView(Ui.button(this, "Open DebDroid").apply {
                setOnClickListener {
                    this@InstallActivity.task?.removeListener(this@InstallActivity)
                    DeviceState.refresh(context) { openHome() }
                }
            })
        } else {
            // A failed download leaves the offline way open, right where it failed
            val failedDownload = task.steps.getOrNull(1)?.let {
                it.label == "Image downloaded" && it.status == Task.Step.Status.FAILED
            } == true
            val probe = DeviceState.probe
            if (failedDownload && probe != null) {
                actions.addView(fileButton(DeviceState.action.takeIf { it.downloads } ?: InstallAction.INSTALL_DOWNLOAD, probe) { false }.apply {
                    layoutParams = Ui.margins(context, bottom = 11f)
                })
            }
            actions.addView(Ui.button(this, "Back", Ui.Kind.SECONDARY, height = 46f).apply {
                setOnClickListener {
                    this@InstallActivity.task?.removeListener(this@InstallActivity)
                    this@InstallActivity.task = null
                    DeviceState.refresh(context, full = true) { render() }
                }
            })
        }
    }

    // A decision the install needs from the user, warned in amber
    // NOTE: Both choices are offered plainly. The file is the user's, and
    //       refusing it outright would sabotage someone restoring their own
    //       image; hiding the mismatch would let them walk into it unaware.
    private fun questionCard(task: Task, question: Task.Question) = Ui.card(this, stroke = Palette.CAUTION_LINE).apply {
        setPadding(dp(15), dp(14), dp(15), dp(14))
        addView(Ui.horizontal(context, 9f).apply {
            addView(Ui.icon(context, R.drawable.ic_alert, Palette.CAUTION, 15f))
            addView(Ui.text(context, question.title, 14f, Palette.CAUTION, Ui.medium))
        })
        for (line in question.lines) {
            val technical = line.startsWith("expected:") || line.startsWith("actual:")
            addView((if (technical) Ui.monoText(context, line, 10.5f, Palette.OUTPUT) else Ui.text(context, line, 12.5f, Palette.TEXT_MID)).apply {
                setLineSpacing(0f, 1.35f)
                setPadding(0, dp(if (technical) 4 else 8), 0, 0)
            })
        }
        addView(Ui.horizontal(context, 10f).apply {
            layoutParams = Ui.margins(context, top = 14f)
            addView(Ui.button(context, question.decline, Ui.Kind.SECONDARY, height = 46f).apply {
                layoutParams = Ui.weighted(1f)
                setOnClickListener { task.answer(false) }
            })
            addView(Ui.button(context, question.accept, Ui.Kind.CAUTION, height = 46f).apply {
                layoutParams = Ui.weighted(1f)
                setOnClickListener { task.answer(true) }
            })
        })
    }

    private fun stepMark(status: Task.Step.Status): View {
        val size = dp(22)
        return when (status) {
            Task.Step.Status.DONE -> Ui.icon(this, R.drawable.ic_check, Palette.GROUND, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = Ui.rounded(context, Palette.RUNNING, 11f)
            }
            Task.Step.Status.FAILED -> Ui.icon(this, R.drawable.ic_alert, Palette.GROUND, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = Ui.rounded(context, Palette.CAUTION, 11f)
            }
            Task.Step.Status.ACTIVE -> View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    Ui.rounded(context, 0, 11f, Palette.PRIMARY, 2.4f),
                    android.graphics.drawable.InsetDrawable(Ui.rounded(context, Palette.PRIMARY, 4f), dp(7.5f)),
                ))
            }
            else -> View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = Ui.rounded(context, 0, 11f, Palette.BORDER, 2f)
            }
        }
    }
}
