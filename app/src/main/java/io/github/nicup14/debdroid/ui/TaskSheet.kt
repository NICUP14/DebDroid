package io.github.nicup14.debdroid.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.service.SessionService
import io.github.nicup14.debdroid.ui.Ui.dp
import io.github.nicup14.debdroid.work.Task
import io.github.nicup14.debdroid.work.Tasks

// The action sheet: a running action with its merged stdout and stderr
//
// The work belongs to the task, not to the sheet. Closing the sheet, or the
// app, leaves it running, and the notification keeps it alive.
object TaskSheet {

    // A sheet anchored to the bottom of the screen, like the canvas draws it
    private fun sheet(activity: Activity): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        val content = Ui.vertical(activity).apply {
            background = GradientDrawable().apply {
                setColor(Palette.CARD)
                val r = activity.dp(22).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(1, Palette.LINE_INSET)
            }
            setPadding(activity.dp(18), activity.dp(10), activity.dp(18), activity.dp(18))
            addView(View(context).apply {
                background = Ui.rounded(context, Palette.BORDER, 2f)
                layoutParams = LinearLayout.LayoutParams(context.dp(38), context.dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = context.dp(16)
                }
            })
        }
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.82).toInt())
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
            // NOTE: The screen stays on while the sheet shows a running action,
            //       as the board asks ("Keep the screen on").
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog to content
    }

    private fun heading(content: LinearLayout, title: String, description: String) {
        val context = content.context
        content.addView(Ui.title(context, title, 21f))
        if (description.isNotEmpty()) {
            content.addView(Ui.body(context, description, 13f).apply { setPadding(0, context.dp(4), 0, context.dp(18)) })
        } else {
            content.addView(Ui.gap(context, 14f))
        }
    }

    private fun outputHeader(content: LinearLayout) {
        val context = content.context
        content.addView(Ui.horizontal(context, 8f).apply {
            layoutParams = Ui.margins(context, bottom = 8f)
            addView(Ui.header(context, "Output").apply { setPadding(0, 0, 0, 0) })
            addView(Ui.space(context))
            addView(Ui.monoText(context, "stdout + stderr", 10.5f))
        })
    }

    // Shows a task's progress, then hands the result to onDone once
    fun follow(activity: Activity, task: Task, title: String, description: String, onDone: (Boolean) -> Unit = {}) {
        val (dialog, content) = sheet(activity)
        heading(content, title, description)
        attach(activity, dialog, content, task, onDone)
        dialog.show()
    }

    private fun attach(activity: Activity, dialog: Dialog, content: LinearLayout, task: Task, onDone: (Boolean) -> Unit) {
        outputHeader(content)
        val output = Ui.Output(activity)
        content.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = Ui.horizontal(activity, 10f).apply { layoutParams = Ui.margins(context, top = 16f) }
        content.addView(actions)
        val note = Ui.text(activity, "Keep the screen on. Closing the app will not stop this.", 11.5f, Palette.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, activity.dp(12), 0, 0)
        }
        content.addView(note)

        var reported = false
        val listener = object : Task.Listener {
            override fun onTaskChanged(task: Task) {
                output.show(task.lines())
                actions.removeAllViews()
                if (task.running) {
                    actions.addView(Ui.button(activity, "Cancel", Ui.Kind.SECONDARY, height = 48f).apply {
                        layoutParams = Ui.weighted(1f)
                        setOnClickListener { task.cancel() }
                    })
                    actions.addView(Ui.button(activity, "Working…", height = 48f).apply {
                        layoutParams = Ui.weighted(2f)
                        isEnabled = false
                        alpha = 0.72f
                    })
                    return
                }
                note.text = when (task.state) {
                    Task.State.DONE -> "Finished."
                    Task.State.CANCELLED -> "Cancelled."
                    else -> "It stopped with exit code ${task.exitCode}. The reason is in the output."
                }
                note.setTextColor(if (task.state == Task.State.DONE) Palette.RUNNING else Palette.CAUTION)
                actions.addView(Ui.button(activity, "Close", if (task.state == Task.State.DONE) Ui.Kind.PRIMARY else Ui.Kind.SECONDARY, height = 48f).apply {
                    layoutParams = Ui.weighted(1f)
                    setOnClickListener { dialog.dismiss() }
                })
                if (!reported) {
                    reported = true
                    onDone(task.state == Task.State.DONE)
                }
            }
        }
        task.addListener(listener)
        dialog.setOnDismissListener { task.removeListener(listener) }
    }

    // Runs a debdroid verb in a sheet
    fun run(activity: Activity, title: String, description: String, command: String, onDone: (Boolean) -> Unit = {}) {
        val running = Tasks.current
        if (running != null && running.running) {
            follow(activity, running, running.title, "Another action is still running. It is shown here instead.")
            return
        }
        val task = Tasks.command(title, command)
        SessionService.keepAlive(activity)
        follow(activity, task, title, description) { ok ->
            DeviceState.refresh(activity, full = true)
            onDone(ok)
        }
    }

    // A size field, checked the way check_size checks it: a number and K, M or G
    fun sizeField(activity: Activity, initial: String): EditText = EditText(activity).apply {
        setText(initial)
        setSelection(text.length)
        typeface = Ui.mono
        textSize = Ui.sp(context, 18f)
        setTextColor(Palette.TEXT)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        background = null
        setPadding(0, activity.dp(3), 0, 0)
    }

    fun validSize(text: String) = Regex("^[0-9]+[KMG]$").matches(text)

    // Resize image: now, new, and the live output once it runs
    // NOTE: The environment must be stopped, as the resizer takes the image
    //       lock, and says so here rather than after the user typed a size.
    fun resize(activity: Activity) {
        val probe = DeviceState.probe
        val (dialog, content) = sheet(activity)
        val now = Ui.size(probe?.imageSize)
        heading(content, "Resize image",
            if (probe?.running == true) "Stop Debian first: the image cannot be resized while it is in use."
            else "Growing is always safe. Shrinking needs that much free inside Debian.")

        val field = sizeField(activity, suggestSize(probe?.imageSize))
        content.addView(Ui.horizontal(activity, 12f).apply {
            layoutParams = Ui.margins(context, bottom = 18f)
            addView(box(activity, "Now", Ui.monoText(activity, now, 18f, Palette.DIM), Palette.LINE_INSET))
            addView(Ui.icon(activity, R.drawable.ic_arrow_right, Palette.FAINT))
            addView(box(activity, "New", field, Palette.PRIMARY))
        })

        val go = Ui.button(activity, "Resize", height = 48f)
        val cancel = Ui.button(activity, "Cancel", Ui.Kind.SECONDARY, height = 48f)
        content.addView(Ui.space(activity), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(Ui.horizontal(activity, 10f).apply {
            addView(cancel.apply { layoutParams = Ui.weighted(1f) })
            addView(go.apply { layoutParams = Ui.weighted(2f) })
        })
        Ui.enable(go, probe?.running != true)
        cancel.setOnClickListener { dialog.dismiss() }
        go.setOnClickListener {
            val size = field.text.toString().trim().uppercase()
            if (!validSize(size)) {
                field.error = "A number then K, M or G, such as 4G"
                return@setOnClickListener
            }
            dialog.dismiss()
            run(activity, "Resize image", "Resizing to $size. Keep the device on charge for a large image.",
                DebDroid.verb("resize", size))
        }
        dialog.show()
    }

    // The next size up in whole gigabytes, as a starting point to edit
    private fun suggestSize(bytes: Long?): String {
        val gb = ((bytes ?: 0L) + (1L shl 30) - 1) / (1L shl 30)
        return "${(gb + 1).coerceAtLeast(2)}G"
    }

    private fun box(activity: Activity, label: String, value: View, stroke: Int) = Ui.vertical(activity).apply {
        background = Ui.rounded(context, Palette.INSET, 12f, stroke)
        setPadding(context.dp(14), context.dp(11), context.dp(14), context.dp(11))
        layoutParams = Ui.weighted(1f)
        addView(Ui.header(context, label).apply { setPadding(0, 0, 0, 0) })
        addView(value)
    }

    // Asks for one value, then runs a command built from it
    fun ask(
        activity: Activity, title: String, description: String, label: String, initial: String,
        mono: Boolean = true, valid: (String) -> String?, build: (String) -> Unit,
    ) {
        val (dialog, content) = sheet(activity)
        heading(content, title, description)
        val field = EditText(activity).apply {
            setText(initial)
            setSelection(text.length)
            if (mono) typeface = Ui.mono
            textSize = Ui.sp(context, 16f)
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.DIM)
            hint = label
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = Ui.rounded(context, Palette.INSET, 12f, Palette.LINE_INSET)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        content.addView(field)
        content.addView(Ui.space(activity), LinearLayout.LayoutParams(1, 0, 1f))
        val go = Ui.button(activity, "Continue", height = 48f)
        content.addView(Ui.horizontal(activity, 10f).apply {
            addView(Ui.button(activity, "Cancel", Ui.Kind.SECONDARY, height = 48f).apply {
                layoutParams = Ui.weighted(1f)
                setOnClickListener { dialog.dismiss() }
            })
            addView(go.apply { layoutParams = Ui.weighted(2f) })
        })
        go.setOnClickListener {
            val value = field.text.toString().trim()
            val problem = valid(value)
            if (problem != null) {
                field.error = problem
                return@setOnClickListener
            }
            dialog.dismiss()
            build(value)
        }
        dialog.show()
    }
}
