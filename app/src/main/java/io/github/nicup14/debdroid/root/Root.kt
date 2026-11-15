package io.github.nicup14.debdroid.root

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

// Runs shell strings as root, through whatever su the device has
//
// This is the whole boundary of the app: every action
// is "su -c <string>", and the app only renders what comes back. stderr is
// merged into stdout on every call. error and warn write to stderr, and an app
// that dropped it would turn every failure into a spinner that stops: the same
// trap that made a failed module flash print one abort line and no reason.
object Root {

    // Where su lives across Magisk, KernelSU, APatch and SuperSU
    // NOTE: Looked up by path first, as an app process's PATH need not contain
    //       the directory a root manager puts su in.
    private val candidates = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/debug_ramdisk/su", "/system/sbin/su", "/vendor/bin/su",
    )

    val su: String by lazy { candidates.firstOrNull { File(it).exists() } ?: "su" }

    // The PATH every root command runs with, so android's toolbox resolves
    // NOTE: The manager calls busybox by path, so this only matters for the
    //       toolbox commands the scripts deliberately use (getprop, curl).
    private const val PATH = "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/su/bin"

    class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    // A running root command, for callers that stream its output or cancel it
    class Job internal constructor(private val process: Process) {
        val input: InputStream get() = process.inputStream
        val stdin: OutputStream get() = process.outputStream

        fun waitFor(): Int = process.waitFor()

        fun cancel() {
            process.destroy()
        }

        fun isAlive(): Boolean = try {
            process.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    // Starts a command, stderr merged, for the caller to read and wait on
    fun start(command: String): Job {
        val builder = ProcessBuilder(su, "-c", "PATH=$PATH; export PATH; $command")
        builder.redirectErrorStream(true)
        builder.environment()["NO_COLOR"] = "1"
        return Job(builder.start())
    }

    // Runs a command to completion, handing each line to onLine as it arrives
    // NOTE: Blocking. Never call it on the main thread.
    fun run(command: String, stdin: ByteArray? = null, onLine: ((String) -> Unit)? = null): Result {
        val job = try {
            start(command)
        } catch (e: IOException) {
            return Result(127, "Cannot run su: ${e.message}")
        }

        // NOTE: stdin is fed and closed first, so a command that reads it to
        //       EOF (cat > file) finishes, and one that does not sees EOF at
        //       once rather than waiting on a pipe nobody writes to.
        try {
            job.stdin.use { out -> if (stdin != null) out.write(stdin) }
        } catch (e: IOException) {
            // The command exited without reading, which its output explains
        }

        val output = StringBuilder()
        job.input.bufferedReader().useLines { lines ->
            for (line in lines) {
                output.append(line).append('\n')
                onLine?.invoke(line)
            }
        }
        return Result(job.waitFor(), output.toString())
    }

    // Asks for root, which on most root managers shows the grant prompt
    // NOTE: Root is requested before anything is probed,
    //       as /data/local/debdroid is unreadable without it.
    fun available(): Boolean {
        val result = run("id -u")
        return result.ok && result.output.lineSequence().any { it.trim() == "0" }
    }

    // Names the root manager's version for display, best effort
    fun managerVersion(): String {
        val magisk = run("magisk -v 2> /dev/null")
        if (magisk.ok && magisk.output.isNotBlank()) {
            return "Magisk " + magisk.output.trim().substringBefore(':').substringBefore('\n')
        }
        val version = run("su -v 2> /dev/null")
        return version.output.trim().substringBefore('\n')
    }

    // Reads a file as root, or null when it does not exist
    fun read(path: String): String? {
        val q = io.github.nicup14.debdroid.core.Shell.quote(path)
        val result = run("[ -f $q ] && cat $q")
        return if (result.ok) result.output else null
    }

    // Writes a file as root, atomically: a temporary file beside it, then a rename
    // NOTE: The rename also replaces a symlink rather than writing through it,
    //       the same reason write_env_file in the manager unlinks first.
    fun write(path: String, content: String, mode: String = "644"): Result {
        val q = io.github.nicup14.debdroid.core.Shell.quote(path)
        val tmp = io.github.nicup14.debdroid.core.Shell.quote("$path.app-tmp")
        return run("cat > $tmp && chmod $mode $tmp && mv -f $tmp $q", content.toByteArray())
    }

    // Waits for a job with a deadline, for commands that must not hang a screen
    fun runWithTimeout(command: String, seconds: Long): Result {
        val job = try {
            start(command)
        } catch (e: IOException) {
            return Result(127, "Cannot run su: ${e.message}")
        }
        try {
            job.stdin.close()
        } catch (e: IOException) {
            // Nothing to do
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                job.input.bufferedReader().forEachLine { output.append(it).append('\n') }
            } catch (e: IOException) {
                // Closed by cancel
            }
        }
        reader.start()
        reader.join(TimeUnit.SECONDS.toMillis(seconds))
        if (reader.isAlive) {
            job.cancel()
            return Result(124, output.toString())
        }
        return Result(job.waitFor(), output.toString())
    }
}
