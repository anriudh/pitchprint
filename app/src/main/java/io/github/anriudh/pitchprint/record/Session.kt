package io.github.anriudh.pitchprint.record

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import io.github.anriudh.pitchprint.device.DeviceInfo
import io.github.anriudh.pitchprint.gnss.GnssSource
import io.github.anriudh.pitchprint.sensors.MotionSource
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * One recording session: a folder of append-only stream files plus the sources feeding them.
 * A session can span several runs (after a crash or reboot); each run writes a run_start event.
 */
class Session(private val context: Context, val dir: File) {
    val writer = SessionWriter(dir, onRepair = { stream, bytes ->
        event("repaired", "stream" to stream.fileName, "bytes" to bytes)
    })
    val startWallMs: Long = readStartWall() ?: System.currentTimeMillis()

    private val callbackThread = HandlerThread("pp-callbacks", Process.THREAD_PRIORITY_FOREGROUND)
    private lateinit var handler: Handler
    private lateinit var gnss: GnssSource
    private lateinit var motion: MotionSource
    private val markers = AtomicInteger()

    private var publishCounts = Counts()
    private var publishNs = 0L
    private var statusCounts = Counts()
    private var statusNs = 0L
    private var ticks = 0

    // Split, pause, test and corner state. Commands arrive from the UI thread and the corner
    // timer, so everything below is guarded by the lock, which also keeps events in order.
    private val lock = Any()
    private var state = SessionState()
    private var ready = false
    private var lastLive = Live(recording = true, session = dir.name, startWallMs = startWallMs)
    private var notificationText = ""

    private data class Counts(
        val fixes: Long = 0,
        val raw: Long = 0,
        val accel: Long = 0,
        val gyro: Long = 0,
        val mag: Long = 0,
    )

    fun start(isNew: Boolean, reason: String) {
        callbackThread.start()
        handler = Handler(callbackThread.looper)
        gnss = GnssSource(context, this, handler)
        motion = MotionSource(context, this, handler)
        // Read before the writer starts, so its repair of a cut-off last line cannot race the read.
        if (!isNew) state = readState(dir)
        writer.start()
        if (isNew) {
            writeMeta()
            event("session_start")
        }
        event("run_start", "reason" to reason, "boot_count" to bootCount())
        synchronized(lock) {
            // The 30 s hold was cut short, so that corner can't be used.
            state.corner?.let { command("corner_cancel", "point" to it.point, "reason" to "interrupted") }
            ready = true
            changed()
        }
        gnss.start()
        motion.start()
        publishNs = SystemClock.elapsedRealtimeNanos()
        statusNs = publishNs
        handler.post { writeStatus() }
        handler.postDelayed(ticker, 1_000)
    }

    fun stop(reason: String) {
        synchronized(lock) {
            ready = false
            handler.removeCallbacks(cornerDone)
            for (e in state.closing("stop")) command(e.type, *e.fields)
        }
        event("stop", "reason" to reason)
        halt()
    }

    /** Stops logging without marking the session finished, so it can be resumed later. */
    fun halt() {
        synchronized(lock) { ready = false }
        gnss.stop()
        motion.stop()
        handler.removeCallbacksAndMessages(null)
        callbackThread.quitSafely()
        callbackThread.join(2_000)
        writer.close()
    }

    fun event(type: String, vararg fields: Pair<String, Any?>) {
        writer.append(Stream.EVENTS, eventLine(type, *fields))
    }

    fun append(stream: Stream, line: String) {
        writer.append(stream, line)
    }

    /** One volume button press. Callers pass only new presses, never repeats or releases. */
    fun marker(key: String, screenOn: Boolean) {
        markers.incrementAndGet()
        event("marker", "key" to key, "screen_on" to screenOn)
    }

    fun startSplit(labels: SplitLabels) {
        synchronized(lock) {
            if (!ready || state.split != null) return
            command("split_start", "n" to state.splits + 1, *labels.fields())
        }
        Sessions.saveLastSplit(context, labels)
    }

    fun endSplit() {
        synchronized(lock) {
            val split = state.split
            if (!ready || split == null) return
            command("split_end", "n" to split.n, "reason" to "user")
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!ready || state.paused) return
            command("pause")
        }
    }

    fun unpause() {
        synchronized(lock) {
            if (!ready || !state.paused) return
            command("resume", "reason" to "user")
        }
    }

    fun markCorner(point: String) {
        synchronized(lock) {
            if (!ready || state.corner != null) return
            command("corner_start", "point" to point, "hold_s" to CORNER_HOLD_S)
            handler.postDelayed(cornerDone, CORNER_HOLD_S * 1_000L)
        }
    }

    fun cancelCorner() {
        synchronized(lock) {
            val corner = state.corner
            if (!ready || corner == null) return
            handler.removeCallbacks(cornerDone)
            command("corner_cancel", "point" to corner.point, "reason" to "user")
        }
    }

    private val cornerDone = Runnable {
        synchronized(lock) {
            val corner = state.corner
            if (!ready || corner == null) return@Runnable
            command("corner_end", "point" to corner.point)
        }
        Haptics.cornerDone(context)
    }

    fun startTest(test: String, carry: String, note: String?) {
        synchronized(lock) {
            if (!ready || state.test != null) return
            command("test_start", "test" to test, "carry" to carry, "note" to note)
        }
    }

    fun endTest() {
        synchronized(lock) {
            val test = state.test
            if (!ready || test == null) return
            command("test_end", "test" to test.test, "reason" to "user")
        }
    }

    // Caller holds the lock. The state moves on from the line as written, exactly as replay does.
    private fun command(type: String, vararg fields: Pair<String, Any?>) {
        val line = eventLine(type, *fields)
        writer.append(Stream.EVENTS, line)
        state = state.apply(JSONObject(line))
        if (ready) changed()
    }

    // Caller holds the lock. Pushes the new state to the screen and the notification right away.
    private fun changed() {
        lastLive = withState(lastLive)
        Recorder.publish(lastLive)
        val split = state.split
        val text = when {
            state.paused -> "Paused, still logging"
            split != null -> "Split ${split.n} running"
            state.splits > 0 -> "Between splits"
            else -> Notifications.DEFAULT_TEXT
        }
        if (text != notificationText) {
            notificationText = text
            Notifications.updateRecording(context, startWallMs, text)
        }
    }

    private fun withState(live: Live) = live.copy(
        splits = state.splits,
        split = state.split,
        paused = state.paused,
        test = state.test,
        corner = state.corner,
    )

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (++ticks % 60 == 0) writeStatus()
            handler.postDelayed(this, 1_000)
        }
    }

    private fun counts() = Counts(
        fixes = gnss.fixes,
        raw = gnss.rawEpochs,
        accel = motion.count(Stream.ACCEL),
        gyro = motion.count(Stream.GYRO),
        mag = motion.count(Stream.MAG),
    )

    private fun publish() {
        val now = SystemClock.elapsedRealtimeNanos()
        val c = counts()
        val p = publishCounts
        val dt = (now - publishNs) / 1e9
        val live = Live(
            recording = true,
            session = dir.name,
            startWallMs = startWallMs,
            fixes = c.fixes,
            fixHz = (c.fixes - p.fixes) / dt,
            fixAgeS = if (c.fixes > 0) (now - gnss.lastFixNs) / 1e9 else null,
            accuracyM = gnss.lastAccuracy,
            satsUsed = gnss.satsUsed,
            satsInView = gnss.satsInView,
            rawEpochs = c.raw,
            accelHz = (c.accel - p.accel) / dt,
            gyroHz = (c.gyro - p.gyro) / dt,
            magHz = (c.mag - p.mag) / dt,
            markers = markers.get(),
            megabytes = writer.bytesWritten.get() / 1e6,
            queue = writer.queueDepth,
            writerError = writer.lastError,
        )
        synchronized(lock) {
            lastLive = withState(live)
            Recorder.publish(lastLive)
        }
        publishCounts = c
        publishNs = now
    }

    // Once a minute: battery and power state, delivered rates and writer health, plus a clock
    // anchor so runs from different boots can be lined up.
    private fun writeStatus() {
        val now = SystemClock.elapsedRealtimeNanos()
        val c = counts()
        val p = statusCounts
        val dt = ((now - statusNs) / 1e9).coerceAtLeast(1e-3)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        }
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        event(
            "status",
            "battery_pct" to level,
            "plugged" to battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            "temp_c" to temp,
            "power_save" to context.getSystemService(PowerManager::class.java).isPowerSaveMode,
            "fix_hz" to (c.fixes - p.fixes) / dt,
            "raw_hz" to (c.raw - p.raw) / dt,
            "accel_hz" to (c.accel - p.accel) / dt,
            "gyro_hz" to (c.gyro - p.gyro) / dt,
            "mag_hz" to (c.mag - p.mag) / dt,
            "queue" to writer.queueDepth,
            "max_writer_stall_ms" to writer.takeMaxStallMs(),
            "bytes" to writer.bytesWritten.get(),
            "writer_error" to writer.lastError,
        )
        event("clock", "boot_count" to bootCount(), "sensor_offset_ns" to JSONObject(motion.clockOffsets()))
        statusCounts = c
        statusNs = now
    }

    private fun writeMeta() {
        val meta = JSONObject()
            .put("format", FORMAT_VERSION)
            .put("session", dir.name)
            .put("start_wall_ms", startWallMs)
            .put("start_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("boot_count", bootCount())
            .put("timezone", TimeZone.getDefault().id)
            .put("requested", motion.requested())
            .put("device", DeviceInfo.snapshot(context))
        FileOutputStream(File(dir, "meta.json")).use {
            it.write(meta.toString(2).toByteArray())
            it.fd.sync()
        }
    }

    private fun readStartWall(): Long? = try {
        JSONObject(File(dir, "meta.json").readText()).optLong("start_wall_ms").takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        const val FORMAT_VERSION = 1
        const val CORNER_HOLD_S = 30
        private const val FINISHED = "finished_after_interruption"

        fun eventLine(type: String, vararg fields: Pair<String, Any?>): String {
            val o = JSONObject()
                .put("t_ns", SystemClock.elapsedRealtimeNanos())
                .put("wall_ms", System.currentTimeMillis())
                .put("type", type)
            for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
            return o.toString()
        }

        /** Closes a session that was interrupted and will not be resumed. */
        fun finishInterrupted(dir: File) {
            val state = readState(dir)
            val w = SessionWriter(dir)
            w.start()
            for (e in state.closing(FINISHED)) w.append(Stream.EVENTS, eventLine(e.type, *e.fields))
            w.append(Stream.EVENTS, eventLine("stop", "reason" to FINISHED))
            w.close()
        }

        private fun readState(dir: File): SessionState {
            val events = File(dir, Stream.EVENTS.fileName)
            return if (events.exists()) replay(events.readText()) else SessionState()
        }
    }
}
