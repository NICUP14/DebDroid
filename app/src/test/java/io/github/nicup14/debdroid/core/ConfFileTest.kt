package io.github.nicup14.debdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The manager's flags and the conf file the app writes them to
class ConfFileTest {

    // The real manager, so a change to its header is tested as it ships
    private val manager = File("../debdroid/debdroid_mgr.sh").readText()
    private val defaults = ConfFile.parseDefaults(manager)

    @Test fun readsEveryDefaultFromTheManager() {
        val names = defaults.map { it.name }
        for (name in listOf("CONFIG_DEV_OVERLAY", "CONFIG_ENV_FSCK", "CONFIG_ENV_WAKELOCK",
                "CONFIG_ENV_SWAP", "CONFIG_ENV_SWAPPINESS", "CONFIG_ENV_DNSSERVER", "CONFIG_ENV_SWAP_PRIO")) {
            assertTrue("missing $name", name in names)
        }
        assertEquals("no", defaults.first { it.name == "CONFIG_ENV_SWAP" }.default)
        assertEquals("60", defaults.first { it.name == "CONFIG_ENV_SWAPPINESS" }.default)
        assertEquals("", defaults.first { it.name == "CONFIG_ENV_SWAP_PRIO" }.default)
        assertEquals("1.1.1.1", defaults.first { it.name == "CONFIG_ENV_DNSSERVER" }.default)
    }

    @Test fun takesTheCommentAsTheDescription() {
        val dns = defaults.first { it.name == "CONFIG_ENV_DNSSERVER" }
        assertTrue(dns.comment.contains("dns", ignoreCase = true))
        assertTrue(defaults.first { it.name == "CONFIG_ENV_WAKELOCK" }.isBoolean)
    }

    @Test fun onlyAChangedFlagIsWritten() {
        val conf = ConfFile.withValue("", defaults, "CONFIG_ENV_SWAP", "yes")!!
        assertTrue(conf.contains("CONFIG_ENV_SWAP=yes\n"))
        assertFalse(conf.contains("CONFIG_ENV_WAKELOCK"))
        assertEquals("yes", ConfFile.effective(defaults, conf)["CONFIG_ENV_SWAP"])
        assertEquals("yes", ConfFile.effective(defaults, conf)["CONFIG_ENV_WAKELOCK"])
    }

    @Test fun settingTheDefaultWritesNothing() =
        assertNull(ConfFile.withValue("", defaults, "CONFIG_ENV_SWAP", "no"))

    @Test fun aFlagSetBackToItsDefaultIsRemoved() {
        val conf = "# mine\nCONFIG_ENV_SWAP=yes\nCONFIG_ENV_FSCK=no\n"
        val result = ConfFile.withValue(conf, defaults, "CONFIG_ENV_SWAP", "no")!!
        assertEquals("# mine\nCONFIG_ENV_FSCK=no\n", result)
    }

    @Test fun theUsersOtherLinesAreKept() {
        val conf = "# my notes\nCONFIG_ENV_FSCK=no # slow\n\n# end\n"
        val result = ConfFile.withValue(conf, defaults, "CONFIG_ENV_FSCK", "yes")!!
        assertEquals("# my notes\n\n# end\n", result)
        val again = ConfFile.withValue(conf, defaults, "CONFIG_ENV_SWAPPINESS", "10")!!
        assertEquals("# my notes\nCONFIG_ENV_FSCK=no # slow\n\n# end\nCONFIG_ENV_SWAPPINESS=10\n", again)
    }

    @Test fun theLastAssignmentWins() {
        val values = ConfFile.parseValues("CONFIG_ENV_SWAP=yes\nCONFIG_ENV_SWAP=\"no\"\n")
        assertEquals("no", values["CONFIG_ENV_SWAP"])
    }

    @Test fun unknownFlagsAreNotWritten() =
        assertNull(ConfFile.withValue("", defaults, "CONFIG_NOT_A_FLAG", "yes"))

    @Test(expected = IllegalArgumentException::class)
    fun aValueThatCouldRunCodeIsRefused() {
        ConfFile.withValue("", defaults, "CONFIG_ENV_DNSSERVER", "1.1.1.1; rm -rf /")
    }

    @Test fun addressesArePermitted() {
        assertTrue(ConfFile.isSafeValue("9.9.9.9"))
        assertTrue(ConfFile.isSafeValue("-2"))
        assertFalse(ConfFile.isSafeValue("\$(id)"))
        assertFalse(ConfFile.isSafeValue("a b"))
    }
}
