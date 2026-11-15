package io.github.nicup14.debdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.R
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.term.TerminalSession
import io.github.nicup14.debdroid.ui.MainActivity
import io.github.nicup14.debdroid.ui.Palette
import io.github.nicup14.debdroid.work.Task
import io.github.nicup14.debdroid.work.Tasks

// Keeps the app's work alive outside the screen, and says so
//
// Android requires the ongoing notification: without a foreground service the
// system reclaims work that outlives the UI, and a terminal session or an
// install is exactly that. It is also the only honest way to stop a session
// started hours ago, so it carries Attach and Stop.
//
// The environment itself does not depend on this service. Its supervisor is a
// root process the shell detached, and it runs whether the app does or not.
class SessionService : Service(), Tasks.Listener, DeviceState.Listener {

    private val main = Handler(Looper.getMainLooper())
    private var foreground = false

    // Re-reads the device while it is in the notification, so the counts are true
    private val tick = object : Runnable {
        override fun run() {
            DeviceState.refresh(this@SessionService)
            main.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Tasks.addListener(this)
        DeviceState.addListener(this)
        main.postDelayed(tick, 60_000)
    }

    override fun onDestroy() {
        instance = null
        Tasks.removeListener(this)
        DeviceState.removeListener(this)
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEnvironment(this)
        }
        update()
        return START_NOT_STICKY
    }

    override fun onTasksChanged() = update()

    override fun onDeviceState() = update()

    // Decides whether the service still has a reason to run
    private fun update() {
        val task = Tasks.current
        val busy = task != null && task.running
        val running = DeviceState.probe?.running == true
        val notification = build(task, running)
        // NOTE: Promoted before anything else, even when there turns out to be
        //       nothing to do: a service started with startForegroundService
        //       that never calls startForeground crashes the app on android 8+.
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, if (Build.VERSION.SDK_INT >= 34)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            } else {
                startForeground(ID, notification)
            }
            foreground = true
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification)
        }
        if (!busy && !running && sessions.isEmpty()) {
            stopForegroundCompat()
            foreground = false
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    private fun build(task: Task?, running: Boolean): Notification {
        channel(this)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else
            @Suppress("DEPRECATION") Notification.Builder(this)

        val probe = DeviceState.probe
        val (title, text) = when {
            task != null && task.running -> task.title to (task.lines().lastOrNull { it.isNotBlank() } ?: "Working")
            running -> "Debian is running" to listOfNotNull(
                probe?.started?.let { uptime(it) },
                probe?.processes?.let { "$it processes" },
                if (probe?.services?.contains("ssh") == true) "ssh on :8022" else null,
                if (sessions.isNotEmpty()) "${sessions.size} open" else null,
            ).joinToString(" · ")
            else -> "Terminal open" to "${sessions.size} session(s)"
        }

        builder.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            // NOTE: The lighter tint, as android draws actions in its own theme,
            //       where #E0103A on a notification surface is too dark to read.
            .setColor(Palette.ON_SYSTEM)
            .setContentIntent(open(this, MainActivity.TAB_HOME))

        if (running) {
            @Suppress("DEPRECATION")
            builder.addAction(R.drawable.ic_terminal, "Attach", open(this, MainActivity.TAB_TERMINAL))
            @Suppress("DEPRECATION")
            builder.addAction(R.drawable.ic_trash, "Stop", PendingIntent.getService(this, 2,
                Intent(this, SessionService::class.java).setAction(ACTION_STOP), immutable()))
        }
        return builder.build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "session"
        const val ACTION_STOP = "io.github.nicup14.debdroid.STOP"

        @Volatile var instance: SessionService? = null
            private set

        // The terminal sessions the app holds open, which outlive any screen
        val sessions = ArrayList<TerminalSession>()

        fun immutable() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or
            PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT

        fun open(context: Context, tab: String): PendingIntent = PendingIntent.getActivity(
            context, tab.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            immutable(),
        )

        fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Running session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Debian runs, an install works, or a terminal is open"
                setShowBadge(false)
            })
        }

        // Starts the service, or lets it re-check whether it still has work
        fun keepAlive(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                // NOTE: Android 12 refuses a foreground start from the
                //       background. The app is in the foreground whenever
                //       this is called from a screen, and a refusal here only
                //       costs the notification, never the work.
            }
        }

        fun uptime(startedEpoch: Long): String {
            val seconds = (System.currentTimeMillis() / 1000 - startedEpoch).coerceAtLeast(0)
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        // Stops the environment, closing the app's sessions into it first
        fun stopEnvironment(context: Context): Task {
            val app = context.applicationContext
            return Tasks.launch(Task("Stopping Debian")) { task ->
                main().post { closeAll() }
                val code = task.exec(DebDroid.verb("stop"))
                task.finish(code)
                DeviceState.refresh(app)
            }
        }

        fun closeAll() {
            for (s in ArrayList(sessions)) s.finish()
            sessions.clear()
            instance?.update()
        }

        // Called when a session ends or opens, so the notification follows
        fun sessionsChanged(context: Context) {
            val service = instance
            if (service != null) service.update() else if (sessions.isNotEmpty()) keepAlive(context)
        }

        private fun main() = Handler(Looper.getMainLooper())
    }
}
