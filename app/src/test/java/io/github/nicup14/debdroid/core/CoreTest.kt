package io.github.nicup14.debdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The probe parser, the detection table, versions, quoting and names
class CoreTest {

    // A device with DebDroid installed, running nothing, as the probe reports it
    private fun installed(vararg overrides: Pair<String, String>): Probe {
        val base = linkedMapOf(
            "probe" to "1", "version" to "1.2.6", "installed_version" to "1.2.6",
            "busybox" to "ok", "root" to "yes", "abi" to "arm64-v8a", "arch" to "arm64",
            "arch_built" to "yes", "sdk" to "24", "scripts_present" to "yes",
            "binaries_present" to "yes", "binaries_ok" to "yes", "image_present" to "yes",
            "image_size" to "1073741824", "image_ok" to "yes", "image_partial" to "no",
            "running" to "no", "session" to "none",
        )
        base.putAll(overrides)
        return Probe.parse(base.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    // A device with nothing on it
    private fun empty(vararg overrides: Pair<String, String>) = installed(
        "installed_version" to "", "scripts_present" to "no", "binaries_present" to "no",
        "binaries_ok" to "no", "image_present" to "no", "image_size" to "", "image_ok" to "no",
        *overrides,
    )

    @Test fun parsesKeysAndIgnoresNoise() {
        val probe = Probe.parse("probe=1\nWarning: something\nversion=1.2.6\nimage_size=42\n  junk\nlog=/a=b\n")
        assertTrue(Probe.isValid(probe))
        assertEquals("1.2.6", probe.version)
        assertEquals(42L, probe.imageSize)
        assertEquals("/a=b", probe.log)
        assertNull(probe["Warning: something"])
    }

    @Test fun emptyValueMeansUnknown() {
        val probe = Probe.parse("probe=1\ninstalled_version=\npid=\n")
        assertNull(probe.installedVersion)
        assertNull(probe.pid)
    }

    @Test fun anInvalidReportIsRecognised() {
        assertFalse(Probe.isValid(Probe.parse("su: permission denied\n")))
    }

    @Test fun firstValueWins() {
        assertEquals("1", Probe.parse("probe=1\nprobe=2\n")["probe"])
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(Version.compare("1.2.10", "1.2.9") > 0)
        assertTrue(Version.compare("1.2", "1.2.0") == 0)
        assertTrue(Version.compare("v1.3.0", "1.2.6") > 0)
        assertTrue(Version.compare("junk", "1.0.0") < 0)
    }

    // The detection table, row by row
    @Test fun nothingFoundDownloads() =
        assertEquals(InstallAction.INSTALL_DOWNLOAD, InstallPlan.decide(empty(), "1.2.6"))

    @Test fun anIntactImageWithoutScriptsSkipsTheDownload() = assertEquals(
        InstallAction.INSTALL_KEEP_IMAGE,
        InstallPlan.decide(empty("image_present" to "yes", "image_ok" to "yes"), "1.2.6"),
    )

    @Test fun aLeftoverDownloadIsRefetched() = assertEquals(
        InstallAction.REFETCH, InstallPlan.decide(empty("image_partial" to "yes"), "1.2.6"),
    )

    @Test fun everythingMatchingGoesHome() =
        assertEquals(InstallAction.NONE, InstallPlan.decide(installed(), "1.2.6"))

    @Test fun olderScriptsUpdate() = assertEquals(
        InstallAction.UPDATE, InstallPlan.decide(installed("installed_version" to "1.2.5"), "1.2.6"),
    )

    @Test fun scriptsWithNoVersionUpdate() = assertEquals(
        InstallAction.UPDATE, InstallPlan.decide(installed("installed_version" to ""), "1.2.6"),
    )

    @Test fun newerScriptsAreNeverDowngraded() = assertEquals(
        InstallAction.NEWER_INSTALLED,
        InstallPlan.decide(installed("installed_version" to "1.3.0"), "1.2.6"),
    )

    @Test fun toolsThatLostTheirExecuteBitAreRepaired() = assertEquals(
        InstallAction.REPAIR, InstallPlan.decide(installed("binaries_ok" to "no"), "1.2.6"),
    )

    @Test fun noRootCannotProceed() = assertEquals(
        InstallAction.NO_ROOT, InstallPlan.decide(installed("root" to "no"), "1.2.6"),
    )

    @Test fun armv7IsRefusedBeforeAnythingIsWritten() = assertEquals(
        InstallAction.UNSUPPORTED_ARCH,
        InstallPlan.decide(empty("arch" to "armhf", "arch_built" to "no"), "1.2.6"),
    )

    @Test fun aMissingBusyboxMeansTheAppsPayloadIsBroken() = assertEquals(
        InstallAction.PAYLOAD_BROKEN,
        InstallPlan.decide(Probe.parse("probe=1\nbusybox=missing\nroot=yes\n"), "1.2.6"),
    )

    @Test fun aDamagedImageIsNeverReplacedSilently() = assertEquals(
        InstallAction.IMAGE_DAMAGED, InstallPlan.decide(installed("image_ok" to "no"), "1.2.6"),
    )

    @Test fun scriptsWithoutAnImageFetchOne() = assertEquals(
        InstallAction.DOWNLOAD_IMAGE,
        InstallPlan.decide(installed("image_present" to "no", "image_ok" to "no", "image_size" to ""), "1.2.6"),
    )

    @Test fun aForegroundSessionBlocksTheInstaller() = assertEquals(
        InstallAction.FOREGROUND_SESSION,
        InstallPlan.decide(installed("installed_version" to "1.2.5", "session" to "foreground"), "1.2.6"),
    )

    @Test fun nothingIsInstalledUnderARunningEnvironment() {
        val running = arrayOf("running" to "yes", "session" to "background")
        assertEquals(InstallAction.ENVIRONMENT_RUNNING,
            InstallPlan.decide(installed("installed_version" to "1.2.5", *running), "1.2.6"))
        assertEquals(InstallAction.ENVIRONMENT_RUNNING,
            InstallPlan.decide(installed("image_present" to "no", "image_ok" to "no", *running), "1.2.6"))
        assertEquals(InstallAction.ENVIRONMENT_RUNNING,
            InstallPlan.decide(installed("binaries_ok" to "no", *running), "1.2.6"))
    }

    @Test fun aRunningEnvironmentStillCountsAsInstalled() = assertEquals(
        InstallAction.NONE,
        InstallPlan.decide(installed("running" to "yes", "session" to "background"), "1.2.6"),
    )

    @Test fun quotingSurvivesQuotesAndSpaces() {
        assertEquals("'it'\\''s'", Shell.quote("it's"))
        assertEquals("sh /sdcard/debdroid/debdroid.sh 'command' 'user' 'a b'",
            Shell.debdroid("command", "user", "a b"))
        assertEquals("sh /sdcard/debdroid/debdroid.sh", Shell.debdroid())
    }

    @Test fun scriptNamesFollowTheRuntimesRule() {
        assertTrue(ScriptName.isValid("my-backup_2"))
        assertFalse(ScriptName.isValid("my backup"))
        assertFalse(ScriptName.isValid("../x"))
        assertFalse(ScriptName.isValid(""))
        assertFalse(ScriptName.isValid("café"))
        assertEquals("mybackup", ScriptName.clean("my backup!"))
        assertEquals("caf", ScriptName.clean("café"))
    }

    // Every probe key the app reads is one the probe prints
    // NOTE: Read from the real script, so renaming a key in the shell without
    //       the app fails here and not on a device.
    @Test fun everyKeyTheAppReadsIsPrinted() {
        val script = File("../debdroid/debdroid_probe.sh").readText()
        val keys = Regex("report_(?:key|test) ([a-z_]+)").findAll(script).map { it.groupValues[1] }.toSet()
        val read = listOf(
            "probe", "version", "installed_version", "busybox", "root", "abi", "arch", "arch_built",
            "sdk", "scripts_present", "binaries_present", "binaries_ok", "image_present",
            "image_size", "image_ok", "image_partial", "running", "session", "pid", "mounts",
            "processes", "wakelock", "swap", "swapfile", "module_installed", "root_manager",
            "boot_hook", "log", "started", "services", "mount_ns",
        )
        for (key in read) assertTrue("probe does not print \"$key\"", key in keys)
    }
}
