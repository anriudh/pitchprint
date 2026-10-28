package io.github.anriudh.pitchprint.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import io.github.anriudh.pitchprint.record.Session
import io.github.anriudh.pitchprint.record.Stream
import io.github.anriudh.pitchprint.record.col
import org.json.JSONObject

/** Logs accelerometer, gyroscope and magnetometer samples. Callbacks run on the callback thread. */
class MotionSource(
    context: Context,
    private val session: Session,
    private val handler: Handler,
) : SensorEventListener {
    private class Channel(val sensor: Sensor, val stream: Stream, val periodUs: Int) {
        var count = 0L
        var lastTs = 0L
        var lastRx = 0L
    }

    private val sm = context.getSystemService(SensorManager::class.java)

    // Keyed by type: some phones list a second, software gyroscope, and only the default is used.
    private val channels: Map<Int, Channel> = buildMap {
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            put(it.type, Channel(it, Stream.ACCEL, IMU_PERIOD_US))
        }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            put(it.type, Channel(it, Stream.GYRO, IMU_PERIOD_US))
        }
        // Magnetometers are slower; ask for the fastest rate it has, capped at the IMU rate.
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            put(it.type, Channel(it, Stream.MAG, maxOf(it.minDelay, IMU_PERIOD_US)))
        }
    }

    fun start() {
        for (ch in channels.values) {
            if (!sm.registerListener(this, ch.sensor, ch.periodUs, 0, handler)) {
                session.event("sensor_error", "stream" to ch.stream.fileName)
            }
        }
    }

    fun stop() = sm.unregisterListener(this)

    fun count(stream: Stream): Long = channels.values.firstOrNull { it.stream == stream }?.count ?: 0

    fun requested(): JSONObject = JSONObject().apply {
        for (ch in channels.values) {
            put(ch.stream.fileName, JSONObject().put("sensor", ch.sensor.name).put("period_us", ch.periodUs))
        }
    }

    /** Receipt time minus event timestamp for each sensor's latest event, in nanoseconds. */
    fun clockOffsets(): Map<String, Long> =
        channels.values.filter { it.count > 0 }.associate { it.stream.fileName to it.lastRx - it.lastTs }

    override fun onSensorChanged(e: SensorEvent) {
        val ch = channels[e.sensor.type] ?: return
        ch.count++
        ch.lastTs = e.timestamp
        ch.lastRx = SystemClock.elapsedRealtimeNanos()
        val v = e.values
        session.append(ch.stream, StringBuilder(64).append(e.timestamp).col(v[0]).col(v[1]).col(v[2]).toString())
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        val ch = channels[sensor.type] ?: return
        session.event("sensor_accuracy", "stream" to ch.stream.fileName, "accuracy" to accuracy)
    }

    private companion object {
        const val IMU_PERIOD_US = 5_000
    }
}
