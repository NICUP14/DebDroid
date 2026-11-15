package io.github.nicup14.debdroid.core

// What "debdroid.sh probe" reported, parsed
//
// The format is the contract in docs/reference/probe.md: one key=value per
// line, unknown keys ignored, an empty value meaning "could not be told". This
// is the only place the app reads the device's state from, so every screen
// that shows state agrees with every other one and with the shell.
class Probe(private val values: Map<String, String>) {

    operator fun get(key: String): String? = values[key]

    private fun yes(key: String) = values[key] == "yes"

    private fun number(key: String) = values[key]?.toLongOrNull()

    // The probing copy's own version, and the installed scripts' version
    val version: String get() = values["version"].orEmpty()
    val installedVersion: String? get() = values["installed_version"]?.takeIf { it.isNotEmpty() }

    val busyboxFound: Boolean get() = values["busybox"] == "ok"
    val root: Boolean get() = yes("root")
    val abi: String get() = values["abi"].orEmpty()
    val arch: String get() = values["arch"].orEmpty()
    val archBuilt: Boolean get() = yes("arch_built")
    val sdk: Int? get() = values["sdk"]?.toIntOrNull()

    val scriptsPresent: Boolean get() = yes("scripts_present")
    val binariesPresent: Boolean get() = yes("binaries_present")
    val binariesOk: Boolean get() = yes("binaries_ok")

    val imagePresent: Boolean get() = yes("image_present")
    val imageSize: Long? get() = number("image_size")
    val imageOk: Boolean get() = yes("image_ok")
    val imagePartial: Boolean get() = yes("image_partial")

    val running: Boolean get() = yes("running")
    val session: String get() = values["session"].orEmpty()
    val pid: Long? get() = number("pid")
    val mounts: Long? get() = number("mounts")
    val processes: Long? get() = number("processes")
    val wakelock: String get() = values["wakelock"].orEmpty()
    val swapOn: Boolean get() = values["swap"] == "on"
    val swapfile: Boolean get() = yes("swapfile")
    val started: Long? get() = number("started")
    val services: Set<String> get() = values["services"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
    val mountNamespace: Boolean get() = yes("mount_ns")

    val moduleInstalled: Boolean get() = yes("module_installed")
    val rootManager: String get() = values["root_manager"].orEmpty()
    val bootHook: String get() = values["boot_hook"].orEmpty()
    val log: String get() = values["log"].orEmpty()

    // A foreground "run" holds the image, so nothing can start beside it
    val foregroundSession: Boolean get() = session == "foreground"

    companion object {
        // Reads a report, ignoring anything that is not a key=value line
        // NOTE: The report comes through su with stderr merged, so a stray
        //       warning line can sit among the keys. A key is lower case and
        //       underscores only, which no message line looks like.
        fun parse(text: String): Probe {
            val map = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq)
                if (!key.all { it in 'a'..'z' || it == '_' }) continue
                if (!map.containsKey(key)) map[key] = line.substring(eq + 1).trimEnd('\r')
            }
            return Probe(map)
        }

        // A report counts only when it says which format it is in
        fun isValid(probe: Probe) = probe["probe"] == "1"
    }
}
