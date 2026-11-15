package io.github.nicup14.debdroid.core

// The one action the install screen offers, decided from the probe
//
// Install is autodetected, never asked: the filesystem already knows the
// answer, so the user is shown exactly one thing to do: a detection table,
// plus the refusals that come before it.
enum class InstallAction {
    // Cannot proceed, and the screen says why
    NO_ROOT,
    PAYLOAD_BROKEN,
    UNSUPPORTED_ARCH,
    NEWER_INSTALLED,
    FOREGROUND_SESSION,

    // Something needs installing, but Debian is running on the files it would
    // replace, so it is stopped first
    ENVIRONMENT_RUNNING,

    // Runs the installer
    INSTALL_DOWNLOAD,
    INSTALL_KEEP_IMAGE,
    REFETCH,
    DOWNLOAD_IMAGE,
    UPDATE,
    REPAIR,

    // The image is there but is not a filesystem; replacing it deletes it, so
    // this one asks for a typed confirmation
    IMAGE_DAMAGED,

    // Everything matches: straight to Home
    NONE;

    // Whether the action downloads the image
    val downloads: Boolean
        get() = this == INSTALL_DOWNLOAD || this == REFETCH || this == DOWNLOAD_IMAGE || this == IMAGE_DAMAGED

    // Whether the action runs the installer at all
    val installs: Boolean
        get() = downloads || this == INSTALL_KEEP_IMAGE || this == UPDATE || this == REPAIR

    // Whether the device can be used as it is
    val blocks: Boolean
        get() = this == NO_ROOT || this == PAYLOAD_BROKEN || this == UNSUPPORTED_ARCH
}

object InstallPlan {
    // NOTE: Checked after the table rather than inside it. Installing under a
    //       running environment replaced the busybox its supervisor was
    //       executing ("Text file busy", so that copy silently failed) and put
    //       a new image beside the one still mounted. Nothing in the table is
    //       wrong while it runs; it only has to wait.
    fun decide(probe: Probe, appVersion: String): InstallAction {
        val action = table(probe, appVersion)
        return if (action.installs && probe.running) InstallAction.ENVIRONMENT_RUNNING else action
    }

    private fun table(probe: Probe, appVersion: String): InstallAction {
        // Root is asked for before the probe, so a report without it means no
        if (!probe.root) return InstallAction.NO_ROOT

        // The probe runs on the app's own busybox, so this means the payload
        // it unpacked is unusable, which reinstalling the app fixes
        if (!probe.busyboxFound) return InstallAction.PAYLOAD_BROKEN

        // NOTE: Refused here, before anything is written, the way arch_is_built
        //       refuses in the shell. armv7 is recognised but has no binaries,
        //       and failing later reads as "cannot execute binary file".
        if (!probe.archBuilt) return InstallAction.UNSUPPORTED_ARCH

        val installed = probe.installedVersion
        if (probe.scriptsPresent && installed != null && Version.compare(installed, appVersion) > 0) {
            // Never downgrade
            return InstallAction.NEWER_INSTALLED
        }

        // The installer would write under a session that holds the image
        if (probe.foregroundSession) return InstallAction.FOREGROUND_SESSION

        if (probe.imagePresent && !probe.imageOk) return InstallAction.IMAGE_DAMAGED

        if (!probe.scriptsPresent) {
            return when {
                probe.imagePresent -> InstallAction.INSTALL_KEEP_IMAGE
                probe.imagePartial -> InstallAction.REFETCH
                else -> InstallAction.INSTALL_DOWNLOAD
            }
        }

        if (installed == null || Version.compare(installed, appVersion) < 0) {
            return InstallAction.UPDATE
        }

        // The 0644 failure class: the files are there and nothing can run them
        if (!probe.binariesOk) return InstallAction.REPAIR

        if (!probe.imagePresent) {
            return if (probe.imagePartial) InstallAction.REFETCH else InstallAction.DOWNLOAD_IMAGE
        }

        return InstallAction.NONE
    }
}
