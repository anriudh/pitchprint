package io.github.anriudh.pitchprint.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import io.github.anriudh.pitchprint.record.Session
import io.github.anriudh.pitchprint.record.Stream
import io.github.anriudh.pitchprint.record.bit
import io.github.anriudh.pitchprint.record.col
import java.util.concurrent.Executor

/**
 * Logs everything the GNSS chip reports, from the GPS provider only (never the fused provider).
 * All callbacks run on the session's callback thread, which is also the only thread that reads
 * the counters below.
 */
@SuppressLint("MissingPermission")
class GnssSource(context: Context, private val session: Session, private val handler: Handler) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val executor = Executor { handler.post(it) }
    private var statusEpoch = 0L

    var fixes = 0L
        private set
    var lastFixNs = 0L
        private set
    var lastAccuracy: Float? = null
        private set
    var satsUsed = 0
        private set
    var satsInView = 0
        private set
    var rawEpochs = 0L
        private set

    fun start() {
        try {
            requestLocations()
            val callbacks = if (Build.VERSION.SDK_INT >= 30) {
                listOf(
                    lm.registerGnssStatusCallback(executor, statusCallback),
                    lm.registerGnssNavigationMessageCallback(executor, navCallback),
                    lm.addNmeaListener(executor, nmeaListener),
                )
            } else {
                registerLegacy()
            }
            session.event(
                "gnss_registered",
                "status" to callbacks[0],
                "nav" to callbacks[1],
                "nmea" to callbacks[2],
                "measurements" to registerMeasurements(),
            )
        } catch (e: SecurityException) {
            session.event("gnss_error", "message" to e.message)
        }
    }

    fun stop() {
        lm.removeUpdates(locationListener)
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssNavigationMessageCallback(navCallback)
        lm.removeNmeaListener(nmeaListener)
        lm.unregisterGnssMeasurementsCallback(measurementsCallback)
    }

    private fun requestLocations() {
        if (Build.VERSION.SDK_INT >= 31) {
            // Interval 0 asks for every fix the chip produces rather than a fixed rate.
            val request = LocationRequest.Builder(0)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(0)
                .build()
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, executor, locationListener)
        } else {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, handler.looper)
        }
    }

    private fun registerMeasurements(): Boolean = when {
        Build.VERSION.SDK_INT >= 31 -> {
            // Full tracking is the public way to ask the chip not to duty cycle.
            val builder = GnssMeasurementRequest.Builder().setFullTracking(true)
            if (Build.VERSION.SDK_INT >= 33) builder.setIntervalMillis(0)
            lm.registerGnssMeasurementsCallback(builder.build(), executor, measurementsCallback)
        }
        Build.VERSION.SDK_INT >= 30 -> lm.registerGnssMeasurementsCallback(executor, measurementsCallback)
        else -> @Suppress("DEPRECATION") lm.registerGnssMeasurementsCallback(measurementsCallback, handler)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(): List<Boolean> = listOf(
        lm.registerGnssStatusCallback(statusCallback, handler),
        lm.registerGnssNavigationMessageCallback(navCallback, handler),
        lm.addNmeaListener(nmeaListener, handler),
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = writeFix(location)

        override fun onProviderEnabled(provider: String) =
            session.event("provider_enabled", "provider" to provider)

        override fun onProviderDisabled(provider: String) =
            session.event("provider_disabled", "provider" to provider)

        // Still abstract on Android 10, so it has to exist.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun writeFix(loc: Location) {
        fixes++
        lastFixNs = loc.elapsedRealtimeNanos
        lastAccuracy = if (loc.hasAccuracy()) loc.accuracy else null
        val line = StringBuilder(200).append(loc.elapsedRealtimeNanos)
            .col(SystemClock.elapsedRealtimeNanos())
            .col(loc.time)
            .col(loc.latitude)
            .col(loc.longitude)
            .col(if (loc.hasAltitude()) loc.altitude else null)
            .col(if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters else null)
            .col(if (loc.hasAccuracy()) loc.accuracy else null)
            .col(if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else null)
            .col(if (loc.hasSpeed()) loc.speed else null)
            .col(if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null)
            .col(if (loc.hasBearing()) loc.bearing else null)
            .col(if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees else null)
            .col(if (loc.hasElapsedRealtimeUncertaintyNanos()) loc.elapsedRealtimeUncertaintyNanos else null)
            .col(bit(isMock(loc)))
        session.append(Stream.FIX, line.toString())
    }

    @Suppress("DEPRECATION")
    private fun isMock(loc: Location) = if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val t = SystemClock.elapsedRealtimeNanos()
            statusEpoch++
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                val baseband = if (Build.VERSION.SDK_INT >= 30 && status.hasBasebandCn0DbHz(i)) {
                    status.getBasebandCn0DbHz(i)
                } else {
                    null
                }
                val line = StringBuilder(100).append(t)
                    .col(statusEpoch)
                    .col(status.getConstellationType(i))
                    .col(status.getSvid(i))
                    .col(status.getCn0DbHz(i))
                    .col(baseband)
                    .col(status.getElevationDegrees(i))
                    .col(status.getAzimuthDegrees(i))
                    .col(bit(status.usedInFix(i)))
                    .col(bit(status.hasAlmanacData(i)))
                    .col(bit(status.hasEphemerisData(i)))
                    .col(if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null)
                session.append(Stream.STATUS, line.toString())
            }
            satsUsed = used
            satsInView = status.satelliteCount
        }

        override fun onStarted() = session.event("gnss_started")

        override fun onStopped() = session.event("gnss_stopped")

        override fun onFirstFix(ttffMillis: Int) = session.event("gnss_first_fix", "ttff_ms" to ttffMillis)
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) = writeRaw(event)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(status: Int) = session.event("gnss_measurement_status", "status" to status)
    }

    @Suppress("DEPRECATION")
    private fun writeRaw(event: GnssMeasurementsEvent) {
        rawEpochs++
        val rx = SystemClock.elapsedRealtimeNanos()
        val c = event.clock
        val chipsetNs = if (c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else null
        val clock = StringBuilder(160)
            .col(c.timeNanos)
            .col(if (c.hasLeapSecond()) c.leapSecond else null)
            .col(if (c.hasTimeUncertaintyNanos()) c.timeUncertaintyNanos else null)
            .col(if (c.hasFullBiasNanos()) c.fullBiasNanos else null)
            .col(if (c.hasBiasNanos()) c.biasNanos else null)
            .col(if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos else null)
            .col(if (c.hasDriftNanosPerSecond()) c.driftNanosPerSecond else null)
            .col(if (c.hasDriftUncertaintyNanosPerSecond()) c.driftUncertaintyNanosPerSecond else null)
            .col(c.hardwareClockDiscontinuityCount)
            .toString()
        val chipsetUnc = if (c.hasElapsedRealtimeUncertaintyNanos()) c.elapsedRealtimeUncertaintyNanos else null
        val fullTracking = if (Build.VERSION.SDK_INT >= 34) bit(event.isFullTracking) else null
        val api30 = Build.VERSION.SDK_INT >= 30
        for (m in event.measurements) {
            val line = StringBuilder(480).append(chipsetNs ?: rx)
                .col(rx)
                .append(clock)
                .col(m.svid)
                .col(m.timeOffsetNanos)
                .col(m.state)
                .col(m.receivedSvTimeNanos)
                .col(m.receivedSvTimeUncertaintyNanos)
                .col(m.cn0DbHz)
                .col(m.pseudorangeRateMetersPerSecond)
                .col(m.pseudorangeRateUncertaintyMetersPerSecond)
                .col(m.accumulatedDeltaRangeState)
                .col(m.accumulatedDeltaRangeMeters)
                .col(m.accumulatedDeltaRangeUncertaintyMeters)
                .col(if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz else null)
                .col(m.multipathIndicator)
                .col(if (m.hasSnrInDb()) m.snrInDb else null)
                .col(m.constellationType)
                .col(if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb else null)
                .col(if (api30 && m.hasBasebandCn0DbHz()) m.basebandCn0DbHz else null)
                .col(if (api30 && m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos else null)
                .col(if (api30 && m.hasFullInterSignalBiasUncertaintyNanos()) m.fullInterSignalBiasUncertaintyNanos else null)
                .col(if (api30 && m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos else null)
                .col(
                    if (api30 && m.hasSatelliteInterSignalBiasUncertaintyNanos()) {
                        m.satelliteInterSignalBiasUncertaintyNanos
                    } else {
                        null
                    },
                )
                .col(if (m.hasCodeType()) m.codeType else null)
                .col(chipsetNs)
                .col(chipsetUnc)
                .col(fullTracking)
            session.append(Stream.RAW, line.toString())
        }
    }

    private val navCallback = object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(msg: GnssNavigationMessage) {
            val line = StringBuilder(160).append(SystemClock.elapsedRealtimeNanos())
                .col(msg.svid)
                .col(msg.type)
                .col(msg.status)
                .col(msg.messageId)
                .col(msg.submessageId)
                .append(',').append(hex(msg.data))
            session.append(Stream.NAV, line.toString())
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(status: Int) = session.event("gnss_nav_status", "status" to status)
    }

    // Sentences contain commas, so the sentence field is quoted. NMEA never contains quotes.
    private val nmeaListener = OnNmeaMessageListener { message, timestamp ->
        val line = StringBuilder(message.length + 48).append(SystemClock.elapsedRealtimeNanos())
            .col(timestamp)
            .append(",\"").append(message.trim()).append('"')
        session.append(Stream.NMEA, line.toString())
    }

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xff
            out[2 * i] = HEX[b ushr 4]
            out[2 * i + 1] = HEX[b and 0x0f]
        }
        return String(out)
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
