package io.github.nicup14.debdroid.term

import java.io.IOException

// The pty, implemented in src/main/cpp/pty.c
object Pty {
    init {
        System.loadLibrary("debdroidpty")
    }

    // Starts argv[0] on a new pty, returning the master fd; pidOut[0] gets the pid
    @JvmStatic
    @Throws(IOException::class)
    external fun open(argv: Array<String>, env: Array<String>?, cwd: String?, rows: Int, columns: Int, pidOut: IntArray): Int

    @JvmStatic external fun resize(fd: Int, rows: Int, columns: Int)
    @JvmStatic external fun waitFor(pid: Int): Int
    @JvmStatic external fun hangup(pid: Int)
    @JvmStatic external fun close(fd: Int)
}
