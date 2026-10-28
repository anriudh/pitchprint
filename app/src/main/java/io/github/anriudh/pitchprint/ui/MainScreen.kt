package io.github.anriudh.pitchprint.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.anriudh.pitchprint.record.Live
import kotlinx.coroutines.launch
import java.util.Locale

private const val HOLD_TO_STOP_MS = 2_000

@Composable
fun MainScreen(
    live: Live,
    interrupted: String?,
    onNewSession: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Pitchprint", style = MaterialTheme.typography.headlineSmall)
            when {
                live.recording -> Recording(live, onStop)
                interrupted != null -> Interrupted(interrupted, onResume, onFinish)
                else -> Button(onClick = onNewSession, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("New session", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Interrupted(name: String, onResume: () -> Unit, onFinish: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Session $name stopped without being finished.")
            Row {
                Button(onClick = onResume) { Text("Resume") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onFinish) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun ColumnScope.Recording(live: Live, onStop: () -> Unit) {
    Text(
        elapsed(System.currentTimeMillis() - live.startWallMs),
        style = MaterialTheme.typography.displayLarge,
        fontFamily = FontFamily.Monospace,
    )
    Text(live.session, style = MaterialTheme.typography.bodySmall)
    Stat("GNSS", gnssText(live))
    Stat("Raw GNSS", "${live.rawEpochs} epochs")
    Stat("Sensors", "accel ${hz(live.accelHz)}   gyro ${hz(live.gyroHz)}   mag ${hz(live.magHz)}")
    Stat("Markers", live.markers.toString())
    Stat("Written", String.format(Locale.ROOT, "%.1f MB, queue %d", live.megabytes, live.queue))
    live.writerError?.let { Text("Write problem: $it", color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.weight(1f))
    HoldToStop(onStop, Modifier.align(Alignment.CenterHorizontally))
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HoldToStop(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val stop by rememberUpdatedState(onStop)
    Box(
        modifier
            .size(150.dp)
            .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        progress.animateTo(1f, tween(HOLD_TO_STOP_MS, easing = LinearEasing))
                        stop()
                    }
                    tryAwaitRelease()
                    if (hold.isActive) {
                        hold.cancel()
                        scope.launch { progress.snapTo(0f) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 10.dp,
            color = MaterialTheme.colorScheme.error,
        )
        Text("Hold\nto stop", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
    }
}

private fun gnssText(live: Live): String {
    val sats = "${live.satsUsed}/${live.satsInView} sats"
    if (live.fixes == 0L) return "no fix yet, $sats"
    val acc = live.accuracyM?.let { String.format(Locale.ROOT, "%.1f m", it) } ?: "no accuracy"
    return String.format(Locale.ROOT, "%.1f Hz, %s, %s, last fix %.1f s ago", live.fixHz, acc, sats, live.fixAgeS ?: 0.0)
}

private fun hz(v: Double) = String.format(Locale.ROOT, "%.0f Hz", v)

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}
