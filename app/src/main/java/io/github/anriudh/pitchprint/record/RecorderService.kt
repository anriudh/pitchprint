package io.github.anriudh.pitchprint.record

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File

/** Foreground service that keeps a session recording with the screen off. */
class RecorderService : Service() {
    private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var markers: VolumeMarkers? = null
    private val main = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            session?.event(if (intent.action == Intent.ACTION_SCREEN_ON) "screen_on" else "screen_off")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            ACTION_START -> begin(Sessions.newDir(this), isNew = true, reason = "new")
            else -> {
                // A null intent means Android restarted the service after the process died.
                val dir = Sessions.activeDir(this)
                if (dir == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                begin(dir, isNew = false, reason = if (intent == null) "process_restart" else "user_resume")
            }
        }
        return if (ok) START_STICKY else START_NOT_STICKY
    }

    private fun begin(dir: File, isNew: Boolean, reason: String): Boolean {
        if (session != null) return true
        val s = Session(applicationContext, dir)
        try {
            startForeground(
                Notifications.ID_RECORDING,
                Notifications.recording(this, s.startWallMs),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: RuntimeException) {
            // Android can refuse a location service started from the background, for example when
            // it restarts us after a crash. The user can bring it back with one tap.
            Notifications.showInterrupted(this)
            stopSelf()
            return false
        }
        Notifications.cancelInterrupted(this)
        Sessions.setActive(this, dir)
        session = s
        Recorder.attach(s)
        s.start(isNew, reason)
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pitchprint:recording")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        markers = VolumeMarkers(this) { direction, newPress ->
            s.event("key", "source" to "media", "dir" to direction)
            if (newPress) {
                s.marker(if (direction > 0) "up" else "down", power.isInteractive)
                Haptics.confirm(this)
            }
        }.also { it.start() }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        return true
    }

    private fun stopRecording() {
        val s = session
        if (s == null) {
            stopSelf()
            return
        }
        session = null
        stopMarkers()
        unregisterReceiver(screenReceiver)
        // Closing waits for the writer to drain and fsync, so keep it off the main thread.
        Thread({
            s.stop("user")
            Sessions.setActive(applicationContext, null)
            Recorder.detach(s)
            main.post {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "pp-stop").start()
    }

    override fun onDestroy() {
        // Destroyed without a stop request: keep the session resumable.
        session?.let { s ->
            session = null
            stopMarkers()
            unregisterReceiver(screenReceiver)
            s.event("service_destroyed")
            s.halt()
            Recorder.detach(s)
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopMarkers() {
        markers?.stop()
        markers = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "io.github.anriudh.pitchprint.action.START"
        const val ACTION_RESUME = "io.github.anriudh.pitchprint.action.RESUME"
        const val ACTION_STOP = "io.github.anriudh.pitchprint.action.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
