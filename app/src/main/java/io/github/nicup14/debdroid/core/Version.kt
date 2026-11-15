package io.github.nicup14.debdroid.core

// Compares DebDroid versions, which are dotted numbers such as 1.2.6
//
// NOTE: A missing part counts as zero, so 1.2 equals 1.2.0, and anything that
//       is not a number counts as zero too: a version the app cannot read is
//       treated as old rather than new, which errs towards offering an update
//       and never towards a downgrade being mistaken for one.
object Version {
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
}
