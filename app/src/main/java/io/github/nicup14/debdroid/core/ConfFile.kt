package io.github.nicup14.debdroid.core

// The manager's flags: their defaults, the user's values, and how to save them
//
// The defaults live at the top of debdroid_mgr.sh, which every update
// replaces. The user's values live in debdroid.conf beside it, which nothing
// ships and nothing overwrites. That split is the fix for updates silently
// discarding settings (AGENTS.md section 5), and this file keeps to it:
//
//   - only a flag that differs from its default is ever written, because a
//     default copied into the conf would pin it against every later release
//     that improves it (the bug install.sh's migration once had)
//   - a flag set back to its default is removed from the conf
//   - every other line in the conf, comments included, is kept as it was
data class Flag(val name: String, val default: String, val comment: String) {
    val isBoolean: Boolean get() = default == "yes" || default == "no"
}

object ConfFile {
    private val flagLine = Regex("^(CONFIG_[A-Z0-9_]+)=([^#]*)(#\\s?(.*))?$")

    // Reads the default flags out of the manager, in the order it declares them
    fun parseDefaults(manager: String): List<Flag> {
        val flags = ArrayList<Flag>()
        val seen = HashSet<String>()
        var lastComment = ""
        for (raw in manager.lineSequence()) {
            val line = raw.trimEnd('\r')
            // Only the header block: the first function ends it
            if (line.matches(Regex("^[a-z_]+\\(\\)\\s*$"))) break
            if (line.startsWith("#")) {
                lastComment = line.removePrefix("#").trim()
                continue
            }
            val match = flagLine.find(line)
            if (match == null) {
                if (line.isBlank()) lastComment = ""
                continue
            }
            val name = match.groupValues[1]
            if (!seen.add(name)) continue
            val trailing = match.groupValues[4].trim()
            flags += Flag(name, match.groupValues[2].trim(), trailing.ifEmpty { lastComment })
        }
        return flags
    }

    // Reads the values a conf file sets, the last assignment winning, as the
    // shell would when it sources the file
    fun parseValues(conf: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (raw in conf.lineSequence()) {
            val match = flagLine.find(raw.trimEnd('\r').trim()) ?: continue
            values[match.groupValues[1]] = unquote(match.groupValues[2].trim())
        }
        return values
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && (value.first() == '"' && value.last() == '"' ||
                value.first() == '\'' && value.last() == '\'')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    // The effective value of every flag: the user's where set, the default otherwise
    fun effective(defaults: List<Flag>, conf: String): Map<String, String> {
        val user = parseValues(conf)
        return defaults.associate { it.name to (user[it.name] ?: it.default) }
    }

    // Returns the conf with one flag changed, or null when it would not change
    // NOTE: A value is rejected rather than quoted if it could do anything but
    //       assign itself when the manager sources the file as root.
    fun withValue(conf: String, defaults: List<Flag>, name: String, value: String): String? {
        require(isSafeValue(value)) { "Unsafe value for $name" }
        val flag = defaults.firstOrNull { it.name == name } ?: return null
        val wantDefault = value == flag.default

        val lines = if (conf.isEmpty()) ArrayList() else conf.removeSuffix("\n").split("\n").toMutableList()
        var found = false
        val out = ArrayList<String>()
        for (line in lines) {
            val match = flagLine.find(line.trimEnd('\r').trim())
            if (match != null && match.groupValues[1] == name) {
                if (found) continue
                found = true
                if (!wantDefault) out += "$name=$value"
                continue
            }
            out += line
        }
        if (!found) {
            if (wantDefault) return null
            if (out.isEmpty()) out += HEADER
            out += "$name=$value"
        }

        val result = out.joinToString("\n") + "\n"
        return if (result == normalise(conf)) null else result
    }

    private fun normalise(conf: String) = if (conf.isEmpty()) "\n" else conf.removeSuffix("\n") + "\n"

    // Letters, digits and the few characters a flag value uses: dots in an
    // address, a minus in a priority. Never a quote, space, $ or ;.
    fun isSafeValue(value: String) = value.all { it.isLetterOrDigit() && it.code < 128 || it in ".-_:/" }

    // Written when the app creates the file, so a person opening it knows what it is
    val HEADER = listOf(
        "# Your DebDroid manager flags.",
        "#",
        "# Written by the DebDroid app. debdroid_mgr.sh holds the defaults and is",
        "# replaced on every update; this file never is. Only the flags you changed",
        "# are listed; everything else follows the defaults.",
        "",
    ).joinToString("\n")
}
