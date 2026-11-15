package io.github.nicup14.debdroid.work

import android.content.Context
import android.net.Uri
import io.github.nicup14.debdroid.Pinned
import io.github.nicup14.debdroid.core.ImageSource
import io.github.nicup14.debdroid.core.InstallAction
import io.github.nicup14.debdroid.core.Probe
import io.github.nicup14.debdroid.core.Shell
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Payload
import io.github.nicup14.debdroid.root.Root
import java.io.IOException

// Runs the one install action the probe decided on
//
// It does not install anything itself. It unpacks the payload, takes the
// image from the offline installer zip when the user brought one, and runs
// install.sh, the same
// installer the module flash and the terminal route run, which downloads the
// image when there is none. One installer, three front doors.
object Install {

    // Starts the install; with a file, the image comes from it instead of the network
    fun launch(context: Context, action: InstallAction, probe: Probe, startAtBoot: Boolean, imageFile: Uri? = null): Task {
        val app = context.applicationContext
        val task = Task("Installing")
        val fromFile = imageFile != null
        val unpack = Task.Step("Scripts and tools unpacked")
        val image = Task.Step(if (fromFile) "Image taken from the offline installer" else "Image downloaded")
        val verify = Task.Step(if (fromFile) "Checked against the pinned checksum" else "Verifying the checksum")
        val install = Task.Step(if (action == InstallAction.UPDATE) "Updating, keeping your settings" else "Installing")
        task.steps += listOf(unpack, image, verify, install)
        return Tasks.launch(task) { run(app, it, action, probe, startAtBoot, imageFile, unpack, image, verify, install) }
    }

    private fun run(
        context: Context, task: Task, action: InstallAction, probe: Probe, startAtBoot: Boolean, imageFile: Uri?,
        unpack: Task.Step, image: Task.Step, verify: Task.Step, install: Task.Step,
    ) {
        // NOTE: Asked again here, not only when the screen was drawn: Debian
        //       may have been started since, and installing under it replaces
        //       the busybox its supervisor is running.
        if (DebDroid.probe(context).running) {
            task.log("Error: Debian is running. Stop it, then install again.")
            unpack.status = Task.Step.Status.FAILED
            task.finish(1, "Debian is running. Stop it on the Home screen, then install again.")
            return
        }

        unpack.status = Task.Step.Status.ACTIVE
        task.changed()
        val payload = Payload.unpack(context)
        unpack.detail = megabytes(payload.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        unpack.status = Task.Step.Status.DONE
        task.changed()

        // NOTE: Only reached after the user typed the confirmation the
        //       damaged-image screen asks for, as this deletes the file.
        if (action == InstallAction.IMAGE_DAMAGED) {
            task.log(" -> Removing the damaged image")
            Root.run("rm -f " + Shell.quote(DebDroid.IMAGE))
        }

        if (imageFile != null) {
            if (!importImage(context, task, probe, imageFile, image, verify)) return
        } else if (!action.downloads) {
            image.status = Task.Step.Status.SKIPPED
            verify.status = Task.Step.Status.SKIPPED
            image.detail = if (probe.imagePresent) "kept" else ""
        }
        if (task.cancelled) return cancelled(task)

        install.status = Task.Step.Status.ACTIVE
        task.changed()
        task.log("")

        // NOTE: The installer downloads the image itself when there is none,
        //       through debdroid_image.sh, the same route the slim module zip
        //       takes. The app keeps no second downloader: it only watches the
        //       staging files to show how far the download has got.
        val watching = imageFile == null && action.downloads
        val watcher = if (watching) watchDownload(task, image, verify) else null
        // NOTE: DEBDROID_NONINTERACTIVE skips the image size prompt, as a
        //       module flash does: there is no stdin here either.
        val code = task.exec("cd " + Shell.quote(payload.path) + " && DEBDROID_NONINTERACTIVE=1 sh ./install.sh")
        watcher?.interrupt()
        if (code != 0) {
            if (task.cancelled) return cancelled(task)
            val downloadFailed = watching && image.status != Task.Step.Status.DONE
            if (watching) removeStaging()
            when {
                downloadFailed -> image.status = Task.Step.Status.FAILED
                verify.status == Task.Step.Status.ACTIVE -> verify.status = Task.Step.Status.FAILED
                else -> install.status = Task.Step.Status.FAILED
            }
            task.finish(code, if (downloadFailed || verify.status == Task.Step.Status.FAILED)
                "The image could not be downloaded. The reason is in the output. The offline installer can be used instead."
                else "The installer stopped. The reason is in the output.")
            return
        }
        if (watching) {
            image.status = Task.Step.Status.DONE
            verify.status = Task.Step.Status.DONE
        }

        if (startAtBoot) {
            val boot = BootHook.enable(context, DebDroid.probe(context))
            task.log(" -> $boot")
        }

        // Proves the install by asking again, rather than trusting the exit code
        val after = DebDroid.probe(context)
        if (!after.scriptsPresent || !after.binariesOk || !after.imagePresent) {
            install.status = Task.Step.Status.FAILED
            task.log("Error: The installer finished, but the device still is not ready.")
            task.log("    scripts: ${after.scriptsPresent}, tools: ${after.binariesOk}, image: ${after.imagePresent}")
            task.finish(1, "The installer finished, but the device still is not ready.")
            return
        }
        install.status = Task.Step.Status.DONE
        if (!after.imageOk) {
            task.log("Warning: The image does not carry an ext2 filesystem, so it may not start.")
            task.finish(0, "Installed, but the image may not start: it is not an ext2 filesystem.")
            return
        }
        task.finish(0, "DebDroid ${after.installedVersion ?: Pinned.VERSION} is installed.")
    }

    // Copies the image out of the offline installer, warning when it is not the shipped one
    // NOTE: A mismatch is warned about and then allowed, never refused (the
    //       maintainer's call). A file the user chose may be their own resized
    //       image or a backup, which cannot match the shipped checksum, and
    //       refusing it would only get in their way. They see both checksums
    //       and decide. A download is the opposite case: nobody chose those
    //       bytes, so debdroid_image.sh still discards a mismatch.
    private fun importImage(context: Context, task: Task, probe: Probe, file: Uri, image: Task.Step, verify: Task.Step): Boolean {
        val staging = "${DebDroid.IMAGE}.download"
        val qStaging = Shell.quote(staging)
        image.status = Task.Step.Status.ACTIVE
        task.changed()
        task.log(":: Taking the debian image from the offline installer")
        Root.run("mkdir -p " + Shell.quote(DebDroid.IMAGE.substringBeforeLast('/')) + " && rm -f $qStaging")

        val copied = try {
            val input = context.contentResolver.openInputStream(file) ?: throw IOException("The file could not be opened.")
            val writer = Root.start("cat > $qStaging")
            var last = 0L
            val result = try {
                input.use {
                    ImageSource.open(it).use { source ->
                        ImageSource.copy(source, writer.stdin, cancelled = { task.cancelled }) { total ->
                            if (total - last >= (8L shl 20)) {
                                last = total
                                image.detail = megabytes(total)
                                task.changed()
                            }
                        }
                    }
                }
            } finally {
                try {
                    writer.stdin.close()
                } catch (e: IOException) {
                    // Already closed by a failed write
                }
            }
            if (writer.waitFor() != 0) throw IOException("Could not write \"$staging\". Check the free space on /sdcard.")
            result
        } catch (e: Exception) {
            Root.run("rm -f $qStaging")
            if (task.cancelled) {
                cancelled(task)
                return false
            }
            image.status = Task.Step.Status.FAILED
            task.log("Error: ${e.message ?: "The file could not be read."}")
            task.finish(1, e.message ?: "The file could not be read.")
            return false
        }
        image.detail = megabytes(copied.size)
        image.status = Task.Step.Status.DONE
        task.log(" -> ${copied.size} bytes")
        verify.status = Task.Step.Status.ACTIVE
        task.changed()

        val pin = Pinned.IMAGES[probe.arch]
        if (pin != null && copied.matches(pin.sha256, pin.size)) {
            task.log(" -> Matches the checksum pinned in this app")
            verify.status = Task.Step.Status.DONE
        } else {
            val expected = pin?.let { "${it.sha256}, ${it.size} bytes" } ?: "no image is pinned for \"${probe.arch}\""
            task.log("Warning: This file is not the image this version of DebDroid ships.")
            task.log("    expected: $expected")
            task.log("    actual:   ${copied.sha256}, ${copied.size} bytes")
            val keep = task.ask(Task.Question(
                "This is not the image DebDroid ships",
                listOf(
                    "Its checksum does not match the one pinned in this app. It may be your own resized image or a backup, an older release, or a damaged or altered file.",
                    "It may fail to start or behave differently. Use it only if you know where it came from.",
                    "expected: $expected",
                    "actual:   ${copied.sha256}, ${copied.size} bytes",
                ),
                "Use it anyway", "Discard it",
            ))
            if (!keep) {
                Root.run("rm -f $qStaging")
                verify.status = Task.Step.Status.FAILED
                if (task.cancelled) {
                    cancelled(task)
                } else {
                    task.log("Discarded. Nothing was installed.")
                    task.finish(1, "The file was discarded. Nothing was installed.")
                }
                return false
            }
            task.log(" -> Using it anyway, as asked")
            verify.status = Task.Step.Status.DONE
            verify.detail = "mismatch accepted"
        }

        val moved = Root.run("mv -f $qStaging " + Shell.quote(DebDroid.IMAGE))
        if (!moved.ok) {
            verify.status = Task.Step.Status.FAILED
            task.log("Error: Could not move the image into place: ${moved.output.trim()}")
            task.finish(1, "Could not move the image into place.")
            return false
        }
        task.changed()
        return true
    }

    // Follows the installer's download through the files it stages
    // NOTE: Reads files, not the installer's wording, so rewording a message
    //       in debdroid_image.sh cannot break this screen. debdroid_image.sh
    //       downloads to "<image>.download.gz", unpacks to "<image>.download"
    //       and verifies that before moving it into place.
    private fun watchDownload(task: Task, image: Task.Step, verify: Task.Step): Thread {
        val gz = Shell.quote("${DebDroid.IMAGE}.download.gz")
        val raw = Shell.quote("${DebDroid.IMAGE}.download")
        return Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val out = Root.run("[ -f $gz ] && echo \"gz \$(wc -c < $gz)\"; [ -f $raw ] && echo raw").output
                    val gzSize = out.lines().firstOrNull { it.startsWith("gz ") }?.substringAfter(' ')?.trim()?.toLongOrNull()
                    when {
                        out.lines().any { it.trim() == "raw" } -> {
                            image.status = Task.Step.Status.DONE
                            verify.status = Task.Step.Status.ACTIVE
                        }
                        gzSize != null -> {
                            image.status = Task.Step.Status.ACTIVE
                            image.detail = megabytes(gzSize)
                        }
                    }
                    task.changed()
                    Thread.sleep(2000)
                }
            } catch (e: InterruptedException) {
                // The installer finished
            }
        }.also { it.start() }
    }

    private fun removeStaging() {
        Root.run("rm -f " + Shell.quote(DebDroid.IMAGE + ".download") + " " + Shell.quote(DebDroid.IMAGE + ".download.gz"))
    }

    private fun cancelled(task: Task) {
        removeStaging()
        task.log("Cancelled. Nothing was installed.")
        task.finish(1, "Cancelled. Nothing was installed.")
    }

    fun megabytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    }
}

// Starting at boot, through whichever mechanism this device has
//
// Same checkbox, different mechanism, and the app picks. With a root manager, the boot hook goes where it runs service scripts,
// the way the module installs it. Without one, the app's own boot receiver
// runs it through su. Someone who flashed the module already has a hook, and
// gets no second one.
object BootHook {
    private const val PREF = "boot_receiver"

    enum class Mechanism { MODULE, SERVICE_D, APP, NONE }

    fun current(context: Context, probe: Probe): Mechanism = when {
        probe.bootHook == "module" -> Mechanism.MODULE
        probe.bootHook == "service_d" -> Mechanism.SERVICE_D
        prefs(context).getBoolean(PREF, false) -> Mechanism.APP
        else -> Mechanism.NONE
    }

    // Whether a root manager runs service scripts on this device
    fun hasServiceDir(probe: Probe) = probe.rootManager in listOf("magisk", "kernelsu", "apatch", "unknown")

    fun describe(mechanism: Mechanism) = when (mechanism) {
        Mechanism.MODULE -> "Through the DebDroid module"
        Mechanism.SERVICE_D -> "Through the root manager's boot scripts"
        Mechanism.APP -> "Through this app, at boot"
        Mechanism.NONE -> "Off"
    }

    // Turns starting at boot on, returning a line saying how
    fun enable(context: Context, probe: Probe): String {
        if (current(context, probe) != Mechanism.NONE) return "Starting at boot: already on"
        if (hasServiceDir(probe)) {
            val hook = Payload.bootHook(context)
            val result = Root.run(
                "mkdir -p /data/adb/service.d && cat " + Shell.quote(hook.path) + " > " +
                    Shell.quote(DebDroid.SERVICE_D) + " && chmod 755 " + Shell.quote(DebDroid.SERVICE_D)
            )
            if (result.ok) return "Starting at boot: enabled through the root manager"
        }
        prefs(context).edit().putBoolean(PREF, true).apply()
        return "Starting at boot: enabled through this app"
    }

    // Turns it off, or says why it cannot be turned off from here
    fun disable(context: Context, probe: Probe): String? {
        return when (current(context, probe)) {
            Mechanism.MODULE -> "The DebDroid module starts it at boot. Disable the module in your root manager to stop that."
            Mechanism.SERVICE_D -> {
                Root.run("rm -f " + Shell.quote(DebDroid.SERVICE_D))
                prefs(context).edit().putBoolean(PREF, false).apply()
                null
            }
            else -> {
                prefs(context).edit().putBoolean(PREF, false).apply()
                null
            }
        }
    }

    fun appReceiverEnabled(context: Context) = prefs(context).getBoolean(PREF, false)

    private fun prefs(context: Context) = context.getSharedPreferences("debdroid", Context.MODE_PRIVATE)
}
