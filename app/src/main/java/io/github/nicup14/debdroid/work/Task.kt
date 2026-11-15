package io.github.nicup14.debdroid.work

import android.os.Handler
import android.os.Looper
import io.github.nicup14.debdroid.root.Root

// A piece of work the user is watching: an install, a resize, a command
//
// It runs on its own thread and outlives the screen that started it, which is
// what "You can leave this screen. It keeps going." needs. The output is every
// line the command printed, stdout and stderr merged, in order.
class Task(val title: String) {

    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    // One line of the step list an install shows
    class Step(val label: String) {
        enum class Status { WAITING, ACTIVE, DONE, FAILED, SKIPPED }
        @Volatile var status = Status.WAITING
        @Volatile var detail = ""
    }

    interface Listener {
        fun onTaskChanged(task: Task)
    }

    // A decision the work cannot make for the user, shown until answered
    class Question(val title: String, val lines: List<String>, val accept: String, val decline: String)

    @Volatile var question: Question? = null
        private set
    private val answerLock = Object()
    private var answer: Boolean? = null

    // Waits on the work's thread for the user's answer; a cancel answers no
    fun ask(q: Question): Boolean {
        synchronized(answerLock) {
            answer = null
            question = q
        }
        changed()
        synchronized(answerLock) {
            while (answer == null && !cancelled) answerLock.wait(500)
            question = null
        }
        changed()
        return answer == true && !cancelled
    }

    // Called from the screen
    fun answer(yes: Boolean) {
        synchronized(answerLock) {
            answer = yes
            answerLock.notifyAll()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val output = ArrayList<String>()

    @Volatile var state = State.RUNNING
        private set
    @Volatile var exitCode = -1
        private set
    @Volatile var summary = ""
    val steps = ArrayList<Step>()

    @Volatile private var job: Root.Job? = null
    @Volatile var cancelled = false
        private set
    private var posted = false

    @Synchronized
    fun lines(): List<String> = ArrayList(output)

    fun addListener(l: Listener) {
        listeners += l
        l.onTaskChanged(this)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    // Appends a line and lets the screen know, coalescing bursts into one update
    fun log(line: String) {
        synchronized(this) {
            output += line
            // NOTE: Bounded, as an apt upgrade prints tens of thousands of lines
            //       and every one is held for the screen.
            if (output.size > 5000) output.subList(0, output.size - 5000).clear()
        }
        changed()
    }

    fun changed() {
        synchronized(this) {
            if (posted) return
            posted = true
        }
        main.post {
            synchronized(this) { posted = false }
            for (l in ArrayList(listeners)) l.onTaskChanged(this)
        }
    }

    // Runs one root command, streaming its lines into the output
    fun exec(command: String, stdin: ByteArray? = null): Int {
        if (cancelled) return -1
        val started = try {
            Root.start(command)
        } catch (e: Exception) {
            log("Error: Cannot run su: ${e.message}")
            return 127
        }
        job = started
        try {
            started.stdin.use { if (stdin != null) it.write(stdin) }
        } catch (e: Exception) {
            // Closed without reading, which its output explains
        }
        try {
            started.input.bufferedReader().forEachLine { log(it) }
        } catch (e: Exception) {
            // Cancelled
        }
        val code = started.waitFor()
        job = null
        return code
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        changed()
    }

    fun finish(code: Int, summary: String = "") {
        exitCode = code
        if (summary.isNotEmpty()) this.summary = summary
        state = when {
            cancelled -> State.CANCELLED
            code == 0 -> State.DONE
            else -> State.FAILED
        }
        changed()
        Tasks.finished(this)
    }

    val running: Boolean get() = state == State.RUNNING
}

// The one task that runs at a time, so two writers never meet in the image
// NOTE: The shell has its own locks, and this is not a replacement for them.
//       It stops the app from starting a resize while it is still installing,
//       which the lock would refuse anyway, but with a worse message.
object Tasks {
    interface Listener {
        fun onTasksChanged()
    }

    @Volatile var current: Task? = null
        private set

    private val listeners = ArrayList<Listener>()

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    // Starts a task unless one is running, returning the one that runs
    @Synchronized
    fun launch(task: Task, body: (Task) -> Unit): Task {
        val existing = current
        if (existing != null && existing.running) return existing
        current = task
        notifyListeners()
        Thread({
            try {
                body(task)
            } catch (e: Exception) {
                task.log("Error: ${e.message ?: e.javaClass.simpleName}")
                if (task.running) task.finish(1)
            }
            if (task.running) task.finish(task.exitCode.coerceAtLeast(0))
        }, "task").start()
        return task
    }

    // Runs one debdroid command as a task
    fun command(title: String, command: String): Task =
        launch(Task(title)) { task -> task.finish(task.exec(command)) }

    internal fun finished(task: Task) {
        notifyListeners()
    }

    private fun notifyListeners() {
        Handler(Looper.getMainLooper()).post { for (l in ArrayList(listeners)) l.onTasksChanged() }
    }
}
