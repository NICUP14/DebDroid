package io.github.nicup14.debdroid.term

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

// A process on a pty, and the emulator its output feeds
//
// Three threads: a reader that feeds the emulator, a writer that drains what
// the user typed (so a program that stops reading cannot block the main
// thread), and a waiter that collects the exit code. Listeners are always
// called on the main thread, and screen updates are coalesced: however much
// output arrives, at most one update is pending at a time.
//
// An empty `env` means "inherit the app's environment".
class TerminalSession(
    private val argv: Array<String>,
    private val env: Array<String>,
    private val cwd: String?,
    columns: Int,
    rows: Int,
    scrollbackLines: Int,
) {
    interface Listener {
        fun onScreenUpdate(session: TerminalSession)
        fun onExit(session: TerminalSession, code: Int)
        fun onTitleChanged(session: TerminalSession) {}
        fun onBell(session: TerminalSession) {}
    }

    // NOTE: Hold synchronized(emulator) for any access from outside, as the
    //       reader thread writes it.
    val emulator = TerminalEmulator(columns, rows, scrollbackLines)

    @Volatile
    var title: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val updatePending = AtomicBoolean(false)
    private val output = LinkedBlockingQueue<ByteArray>()
    private val lock = Any()

    private var master: ParcelFileDescriptor? = null
    private var reader: Thread? = null

    @Volatile
    private var pid = 0

    @Volatile
    private var started = false

    @Volatile
    private var finished = false

    // NOTE: Set the moment waitpid returns, before anything else, so nothing
    //       signals a pid the system may already have handed to someone else.
    @Volatile
    private var reaped = false

    // The exit code, or minus the signal that killed it; null while running
    @Volatile
    var exitCode: Int? = null
        private set

    val isRunning: Boolean get() = started && exitCode == null

    init {
        emulator.onResponse = { write(it) }
        emulator.onTitle = { text ->
            title = text
            handler.post { for (l in listeners) l.onTitleChanged(this) }
        }
        emulator.onBell = { handler.post { for (l in listeners) l.onBell(this) } }
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // Opens the pty and starts the process and the threads
    @Throws(IOException::class)
    fun start() {
        synchronized(lock) {
            if (started) return
            if (finished) throw IOException("The session was already finished.")

            val size = synchronized(emulator) { intArrayOf(emulator.columns, emulator.rows) }
            val pidOut = IntArray(1)
            val fd = try {
                Pty.open(argv, if (env.isEmpty()) null else env, cwd, size[1], size[0], pidOut)
            } catch (e: LinkageError) {
                // NOTE: A missing or broken libdebdroidpty.so surfaces as an
                //       Error, which callers expecting IOException would not catch.
                throw IOException("The terminal library could not be loaded.", e)
            }
            pid = pidOut[0]
            val pfd = ParcelFileDescriptor.adoptFd(fd)
            master = pfd
            started = true

            reader = Thread({ readLoop(pfd) }, "DebDroid pty reader").apply {
                isDaemon = true
                start()
            }
            Thread({ writeLoop(pfd) }, "DebDroid pty writer").apply {
                isDaemon = true
                start()
            }
            Thread({ waitLoop() }, "DebDroid pty waiter").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                synchronized(emulator) { emulator.append(buffer, 0, n) }
                notifyScreenUpdate()
            }
        } catch (ignored: IOException) {
            // NOTE: EIO is how a pty master reports that the last holder of
            //       the slave closed it. It is the normal end, not a failure.
        }
    }

    private fun writeLoop(pfd: ParcelFileDescriptor) {
        val out = FileOutputStream(pfd.fileDescriptor)
        try {
            while (true) {
                val bytes = output.take()
                if (bytes === STOP) break
                out.write(bytes)
            }
        } catch (ignored: IOException) {
        } catch (ignored: InterruptedException) {
        }
    }

    private fun waitLoop() {
        val code = Pty.waitFor(pid)
        reaped = true
        // NOTE: The last output can still be in the pty when the process is
        //       reaped; waiting briefly for the reader means it is on screen
        //       before onExit. A background job still holding the slave keeps
        //       the reader alive, which is what the timeout is for.
        try {
            reader?.join(READER_DRAIN_MS)
        } catch (ignored: InterruptedException) {
        }
        exitCode = code
        output.offer(STOP)
        if (reader?.isAlive != true) closeMaster()
        handler.post {
            for (l in listeners) l.onScreenUpdate(this)
            for (l in listeners) l.onExit(this, code)
        }
    }

    fun write(bytes: ByteArray) {
        if (!started || finished || exitCode != null || bytes.isEmpty()) return
        output.offer(bytes)
    }

    fun write(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    // Resizes the emulator and tells the pty, which raises SIGWINCH in the program
    fun resize(columns: Int, rows: Int) {
        val changed = synchronized(emulator) {
            if (emulator.columns == columns && emulator.rows == rows) {
                false
            } else {
                emulator.resize(columns, rows)
                true
            }
        }
        if (!changed) return
        synchronized(lock) {
            master?.let { Pty.resize(it.fd, rows, columns) }
        }
        notifyScreenUpdate()
    }

    // Hangs up and closes; safe to call more than once and before start()
    fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        if (!started) return
        if (!reaped) {
            Pty.hangup(pid)
            // NOTE: A process that survives the hangup is killed outright a
            //       little later. Only a process the app owns can be signalled;
            //       a root shell goes down with its su client.
            val target = pid
            handler.postDelayed({
                if (!reaped) {
                    try {
                        Os.kill(target, OsConstants.SIGKILL)
                    } catch (ignored: Exception) {
                    }
                }
            }, KILL_DELAY_MS)
        }
        output.offer(STOP)
        closeMaster()
    }

    private fun closeMaster() {
        val pfd = synchronized(lock) {
            val current = master
            master = null
            current
        }
        try {
            pfd?.close()
        } catch (ignored: IOException) {
        }
    }

    private fun notifyScreenUpdate() {
        if (updatePending.compareAndSet(false, true)) {
            handler.post {
                updatePending.set(false)
                for (l in listeners) l.onScreenUpdate(this)
            }
        }
    }

    private companion object {
        val STOP = ByteArray(0)
        const val READER_DRAIN_MS = 1000L
        const val KILL_DELAY_MS = 2000L
    }
}
