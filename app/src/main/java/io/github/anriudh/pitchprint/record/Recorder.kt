package io.github.anriudh.pitchprint.record

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the recording screen shows. Health numbers only; no metrics in this version. */
data class Live(
    val recording: Boolean = false,
    val session: String = "",
    val startWallMs: Long = 0,
    val fixes: Long = 0,
    val fixHz: Double = 0.0,
    val fixAgeS: Double? = null,
    val accuracyM: Float? = null,
    val satsUsed: Int = 0,
    val satsInView: Int = 0,
    val rawEpochs: Long = 0,
    val accelHz: Double = 0.0,
    val gyroHz: Double = 0.0,
    val magHz: Double = 0.0,
    val markers: Int = 0,
    val megabytes: Double = 0.0,
    val queue: Int = 0,
    val writerError: String? = null,
    val splits: Int = 0,
    val split: Split? = null,
    val paused: Boolean = false,
    val test: TestSegment? = null,
    val corner: CornerHold? = null,
)

/** The running session, if any, and how the UI talks to the recorder service. */
object Recorder {
    private val state = MutableStateFlow(Live())
    val live: StateFlow<Live> = state.asStateFlow()

    @Volatile var session: Session? = null
        private set

    internal fun attach(s: Session) {
        session = s
        state.value = Live(recording = true, session = s.dir.name, startWallMs = s.startWallMs)
    }

    internal fun detach(s: Session) {
        if (session !== s) return
        session = null
        state.value = Live()
    }

    internal fun publish(live: Live) {
        if (session != null) state.value = live
    }

    fun start(context: Context) {
        context.startForegroundService(intent(context, RecorderService.ACTION_START))
    }

    fun resume(context: Context) {
        context.startForegroundService(intent(context, RecorderService.ACTION_RESUME))
    }

    fun stop(context: Context) {
        context.startService(intent(context, RecorderService.ACTION_STOP))
    }

    private fun intent(context: Context, action: String) =
        Intent(context, RecorderService::class.java).setAction(action)
}
