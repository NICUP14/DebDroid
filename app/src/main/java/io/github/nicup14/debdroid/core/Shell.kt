package io.github.nicup14.debdroid.core

// Builds the shell strings the app hands to "su -c"
//
// Every action is one string run by a root shell, so every argument a user
// typed goes through quote(). This is shell_quote from debdroid_lib.sh, in the
// same form: single quotes, with a single quote closed, escaped and reopened.
object Shell {
    fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }

    // The front end every action goes through
    const val FRONTEND = "/sdcard/debdroid/debdroid.sh"

    // "sh /sdcard/debdroid/debdroid.sh <verb> <args...>", quoted
    fun debdroid(vararg args: String): String = "sh $FRONTEND" +
        (if (args.isEmpty()) "" else " " + join(args.toList()))
}

// The rule debdroid.sh applies to a command script's name
//
// NOTE: The runtime refuses anything else outright, so a name the app let
//       through, such as "my backup", would create a file nobody can run, and
//       the user would have no way to learn why.
object ScriptName {
    private val allowed = Regex("^[A-Za-z0-9_-]+$")

    fun isValid(name: String) = allowed.matches(name)

    // Drops every character the runtime would refuse, for a name field
    fun clean(input: String) = input.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
}
