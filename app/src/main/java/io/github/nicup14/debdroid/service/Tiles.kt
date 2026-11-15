package io.github.nicup14.debdroid.service

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.nicup14.debdroid.DeviceState
import io.github.nicup14.debdroid.core.Shell
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Payload
import io.github.nicup14.debdroid.root.Root
import io.github.nicup14.debdroid.ui.MainActivity
import io.github.nicup14.debdroid.work.BootHook
import io.github.nicup14.debdroid.work.Tasks

// The Quick Settings tile: shows whether Debian runs, and starts or stops it
// NOTE: Quick Settings tiles exist from android 7. On older releases the
//       manifest entry is inert, as the system has no tile host to bind it.
@TargetApi(24)
class EnvironmentTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        show()
        DeviceState.refresh(this) { show() }
    }

    private fun show() {
        val tile = qsTile ?: return
        val probe = DeviceState.probe
        val running = probe?.running == true
        val busy = Tasks.current?.running == true
        tile.state = when {
            probe == null || !probe.scriptsPresent -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "DebDroid"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                busy -> Tasks.current?.title
                running -> "Running"
                else -> "Stopped"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val probe = DeviceState.probe ?: return
        if (Tasks.current?.running == true) return
        if (probe.running) {
            SessionService.stopEnvironment(this)
        } else {
            val app = applicationContext
            Tasks.launch(io.github.nicup14.debdroid.work.Task("Starting Debian")) { task ->
                task.finish(task.exec(DebDroid.verb("start")))
                DeviceState.refresh(app)
            }
        }
        SessionService.keepAlive(this)
        qsTile?.let {
            it.state = Tile.STATE_UNAVAILABLE
            it.updateTile()
        }
    }
}

// The second tile: opens a terminal attached to the running environment
@TargetApi(24)
class TerminalTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (DeviceState.probe?.running == true) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            it.label = "Terminal"
            if (Build.VERSION.SDK_INT >= 29) it.subtitle = "Attach"
            it.updateTile()
        }
    }

    // NOTE: The Intent form is the only one before android 14, and the call
    //       below picks the PendingIntent form from 14 on.
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TERMINAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // NOTE: Android 14 refuses the Intent form for an app targeting it and
        //       takes a PendingIntent instead, which does not exist before 34.
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 3, intent, SessionService.immutable()))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

// Starts the environment at boot when no root manager runs a boot hook
//
// With a root manager, the hook lives in its service directory and this does
// nothing. Without one, this receiver runs the very same hook through su, so
// the stand-down sentinel and the wait for /sdcard behave exactly as they do
// under Magisk.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BootHook.appReceiverEnabled(context)) return
        val pending = goAsync()
        Thread {
            try {
                val hook = Payload.unpack(context).resolve("boot/debdroid.service.sh")
                // NOTE: Backgrounded inside the root shell, as the hook waits up
                //       to two and a half minutes for /sdcard, longer than a
                //       receiver may run.
                Root.run("(sh " + Shell.quote(hook.path) + " > /dev/null 2>&1 &)")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
