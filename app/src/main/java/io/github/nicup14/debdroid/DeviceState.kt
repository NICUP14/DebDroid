package io.github.nicup14.debdroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.nicup14.debdroid.core.InstallAction
import io.github.nicup14.debdroid.core.InstallPlan
import io.github.nicup14.debdroid.core.Probe
import io.github.nicup14.debdroid.root.DebDroid
import io.github.nicup14.debdroid.root.Root

// What the app last learnt about the device, shared by every screen
//
// It is refreshed by running the probe, never by guessing: a screen that
// showed "running" because a button was pressed, rather than because the
// device said so, would be the first thing to lie to someone.
object DeviceState {
    interface Listener {
        fun onDeviceState()
    }

    @Volatile var rootChecked = false
        private set
    @Volatile var root = false
        private set
    @Volatile var probe: Probe? = null
        private set
    @Volatile var managerVersion = ""
        private set
    @Volatile var freeData: Long? = null
        private set
    @Volatile var freeSdcard: Long? = null
        private set
    @Volatile var refreshing = false
        private set

    // Set by "Keep it for now" on the update screen, for this run of the app
    @Volatile var updateDeclined = false

    private val listeners = ArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<() -> Unit>()

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    val action: InstallAction
        get() {
            if (rootChecked && !root) return InstallAction.NO_ROOT
            val p = probe ?: return InstallAction.NONE
            return InstallPlan.decide(p, Pinned.VERSION)
        }

    // Probes again, off the main thread; then runs on the main thread
    fun refresh(context: Context, full: Boolean = false, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        synchronized(this) {
            if (then != null) pending += then
            if (refreshing) return
            refreshing = true
        }
        Thread({
            // NOTE: Root is asked for before the probe, not after.
            //       /data/local/debdroid is unreadable without it,
            //       and the grant prompt belongs before anything else happens.
            if (!rootChecked || !root) {
                root = Root.available()
                rootChecked = true
                if (root) managerVersion = Root.managerVersion()
            }
            if (root) {
                probe = try {
                    DebDroid.probe(app)
                } catch (e: Exception) {
                    probe
                }
                if (full || freeData == null) {
                    freeData = DebDroid.freeBytes(app, "/data")
                    freeSdcard = DebDroid.freeBytes(app, "/sdcard/")
                }
            }
            main.post {
                val callbacks: List<() -> Unit>
                synchronized(this) {
                    refreshing = false
                    callbacks = ArrayList(pending)
                    pending.clear()
                }
                for (l in ArrayList(listeners)) l.onDeviceState()
                for (c in callbacks) c()
            }
        }, "probe").start()
    }
}
