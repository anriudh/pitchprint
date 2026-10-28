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
        writer.start()
        if (isNew) {
            writeMeta()
            event("session_start")
        }
        event("run_start", "reason" to reason, "boot_count" to bootCount())
        gnss.start()
        motion.start()
        publishNs = SystemClock.elapsedRealtimeNanos()
        statusNs = publishNs
        handler.post { writeStatus() }
        handler.postDelayed(ticker, 1_000)
    }

    fun stop(reason: String) {
        event("stop", "reason" to reason)
        halt()
    }

    /** Stops logging without marking the session finished, so it can be resumed later. */
    fun halt() {
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
        Recorder.publish(
            Live(
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
            ),
        )
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
            val w = SessionWriter(dir)
            w.start()
            w.append(Stream.EVENTS, eventLine("stop", "reason" to "finished_after_interruption"))
            w.close()
        }
    }
}
