package io.github.nicup14.debdroid.root

import android.content.Context
import io.github.nicup14.debdroid.core.Probe
import io.github.nicup14.debdroid.core.Shell

// The DebDroid verbs, each one a "debdroid.sh" call through Root
//
// Nothing here knows how a session starts, mounts or stops: those belong to
// the shell, and the app owning a second copy of them would be the copy nobody
// tests. This file only names the verbs.
object DebDroid {
    const val SDHOME = "/sdcard/debdroid"
    const val HOME = "/data/local/debdroid"
    const val CONF = "$SDHOME/debdroid.conf"
    const val MANAGER = "$SDHOME/debdroid_mgr.sh"
    const val ENVIRONMENT = "$SDHOME/environment"
    const val COMMANDS = "$SDHOME/command"
    const val IMAGE = "$SDHOME/img/debian.img"
    const val LOG = "$HOME/mnt.log"
    const val SERVICE_D = "/data/adb/service.d/debdroid.sh"

    // Asks the device what state DebDroid is in
    // NOTE: Runs the bundled probe, not the installed one, because the whole
    //       point of asking is that nothing may be installed yet. The bundled
    //       probe still reads the installed paths when there are any.
    fun probe(context: Context): Probe {
        Payload.unpack(context)
        val result = Root.run("sh " + Shell.quote(Payload.probeScript(context)))
        return Probe.parse(result.output)
    }

    // The front-end call for a verb
    fun verb(vararg args: String) = Shell.debdroid(*args)

    // Starts the background environment; the app never calls "run"
    // NOTE: Start always means start then attach: a
    //       session that died with the app would be a bug, not a feature.
    fun start(onLine: ((String) -> Unit)? = null) = Root.run(verb("start"), null, onLine)

    fun stop(onLine: ((String) -> Unit)? = null) = Root.run(verb("stop"), null, onLine)

    // The command a terminal session runs to attach a login shell
    // NOTE: TERM is set inside the root shell, as debian's "su -" keeps TERM
    //       and resets everything else, and a root manager's su may not pass
    //       the app's environment through.
    fun attachCommand(): String = "TERM=xterm-256color; export TERM; " + verb("attach")

    // Free space on the filesystem holding a path, in bytes, or null
    // NOTE: Uses the payload's busybox, which exists before anything is
    //       installed, rather than android's df, whose options vary by release.
    fun freeBytes(context: Context, path: String): Long? {
        // NOTE: "-P" forces one line per filesystem: busybox df wraps a long
        //       device name otherwise, which once read 17G free as 0M.
        val busybox = Shell.quote(java.io.File(Payload.unpack(context), "bin/busybox").path)
        val q = Shell.quote(path)
        val result = Root.run("$busybox df -Pk $q")
        val line = result.output.lineSequence().filter { it.isNotBlank() }.lastOrNull() ?: return null
        val fields = line.trim().split(Regex("\\s+"))
        // Filesystem 1K-blocks Used Available Use% Mounted
        return fields.getOrNull(3)?.toLongOrNull()?.times(1024)
    }
}
