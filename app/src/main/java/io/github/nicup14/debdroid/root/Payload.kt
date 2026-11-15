package io.github.nicup14.debdroid.root

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

// The scripts and binaries carried in the apk, unpacked for the installer
//
// The payload is the same file set the module zip carries (module/payload.list)
// plus install.sh, staged at build time. It is unpacked into the app's own
// files directory, which root can read, and the installer runs from there,
// exactly as it runs from an unpacked module or a checkout. There is one
// installer and this is one of its three front doors.
object Payload {
    private const val ASSET = "payload.zip"
    private const val ID_ASSET = "payload.id"

    fun dir(context: Context) = File(context.filesDir, "payload")

    // The probe, run from the bundled copy so it works with nothing installed
    fun probeScript(context: Context) = File(dir(context), "debdroid/debdroid_probe.sh").path

    // Unpacks the payload unless this exact payload is already unpacked
    // NOTE: Compared by the id the build wrote beside the zip, a hash of every
    //       entry, so an app update with changed scripts always re-unpacks
    //       and an unchanged one never pays for it.
    @Synchronized
    fun unpack(context: Context): File {
        val target = dir(context)
        val id = context.assets.open(ID_ASSET).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val stamp = File(target, ".payload-id")
        if (stamp.isFile && stamp.readText().trim() == id) return target

        val staging = File(context.filesDir, "payload.unpacking")
        staging.deleteRecursively()
        staging.mkdirs()

        ZipInputStream(context.assets.open(ASSET).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(staging, entry.name)
                // NOTE: Refuses an entry that would land outside the directory.
                //       The zip is built by this project, but it is unpacked by
                //       code that then runs it as root, so the check is cheap.
                if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw IOException("Refusing payload entry ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }

        // NOTE: A zip carries no execute bit through java.util.zip, the same
        //       0644 problem a module flash has. install.sh and the probe both
        //       restore it on the busybox they need, and the rest is restored
        //       here so nothing depends on that.
        File(staging, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        // Root reads these from the app's private directory
        staging.walkTopDown().forEach {
            it.setReadable(true, false)
            if (it.isDirectory) it.setExecutable(true, false)
        }

        File(staging, ".payload-id").writeText(id)
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("Cannot move the payload into place")
        // The parent must be traversable for root's shell to reach it on
        // devices whose su keeps the app's uid checks
        context.filesDir.setExecutable(true, false)
        return target
    }

    // The bundled boot hook, which the app installs where the root manager runs it
    fun bootHook(context: Context) = File(dir(context), "boot/debdroid.service.sh")
}
