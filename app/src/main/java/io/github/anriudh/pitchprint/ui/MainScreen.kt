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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.anriudh.pitchprint.record.Live
import io.github.anriudh.pitchprint.record.Recorder
import io.github.anriudh.pitchprint.record.Session
import io.github.anriudh.pitchprint.record.Sessions
import io.github.anriudh.pitchprint.record.SplitLabels
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
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
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

private enum class SheetKind { SPLIT, CORNER, TEST }

@Composable
private fun ColumnScope.Recording(live: Live, onStop: () -> Unit) {
    val context = LocalContext.current
    val session = Recorder.session
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    val now = System.currentTimeMillis()

    Text(elapsed(now - live.startWallMs), style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
    Text(live.session, style = MaterialTheme.typography.bodySmall)

    if (live.paused) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
            Text("Paused, still logging", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
    }

    val split = live.split
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (split == null) {
                Text(if (live.splits == 0) "No split yet" else "Between splits (${live.splits} done)", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Split ${split.n}   ${elapsed(now - split.startWallMs)}", style = MaterialTheme.typography.titleMedium)
                Text(splitText(split.labels))
            }
        }
    }

    live.corner?.let { corner ->
        val left = (Session.CORNER_HOLD_S - (now - corner.startWallMs) / 1000).coerceAtLeast(0)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Corner ${corner.point}: hold still, $left s", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { session?.cancelCorner() }) { Text("Cancel") }
            }
        }
    }

    live.test?.let { test ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Test: ${name(TESTS, test.test)}   ${elapsed(now - test.startWallMs)}", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(name(CARRY, test.carry), test.note).joinToString(", "))
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val wide = Modifier.weight(1f).height(56.dp)
        when {
            split != null -> OutlinedButton(onClick = { session?.endSplit() }, modifier = wide) { Text("End split") }
            else -> Button(onClick = { sheet = SheetKind.SPLIT }, modifier = wide) {
                Text(if (live.splits == 0) "Start split" else "Start next split")
            }
        }
        if (live.paused) {
            Button(onClick = { session?.unpause() }, modifier = wide) { Text("Resume") }
        } else {
            OutlinedButton(onClick = { session?.pause() }, modifier = wide) { Text("Pause") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val wide = Modifier.weight(1f).height(56.dp)
        OutlinedButton(onClick = { sheet = SheetKind.CORNER }, enabled = live.corner == null, modifier = wide) {
            Text("Mark corner")
        }
        if (live.test != null) {
            OutlinedButton(onClick = { session?.endTest() }, modifier = wide) { Text("End test") }
        } else {
            OutlinedButton(onClick = { sheet = SheetKind.TEST }, modifier = wide) { Text("Test segment") }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Stat("GNSS", gnssText(live))
        Stat("Raw GNSS", "${live.rawEpochs} epochs")
        Stat("Sensors", "accel ${hz(live.accelHz)}   gyro ${hz(live.gyroHz)}   mag ${hz(live.magHz)}")
        Stat("Markers", live.markers.toString())
        Stat("Written", String.format(Locale.ROOT, "%.1f MB, queue %d", live.megabytes, live.queue))
    }
    live.writerError?.let { Text("Write problem: $it", color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(8.dp))
    HoldToStop(onStop, Modifier.align(Alignment.CenterHorizontally))

    when (sheet) {
        SheetKind.SPLIT -> SplitSheet(
            initial = Sessions.lastSplit(context),
            onStart = {
                session?.startSplit(it)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        SheetKind.CORNER -> CornerSheet(
            onPick = {
                session?.markCorner(it)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        SheetKind.TEST -> TestSheet(
            initialCarry = split?.labels?.carry ?: Sessions.lastSplit(context)?.carry,
            onStart = { test, carry, note ->
                session?.startTest(test, carry, note)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        null -> {}
    }
}

private fun splitText(l: SplitLabels): String {
    val layout = name(LAYOUTS, l.layout) + (l.half?.let { " $it" } ?: "")
    return listOfNotNull(layout, name(POSITIONS, l.position), "attacking ${name(GOALS, l.attack)}", name(CARRY, l.carry), l.note)
        .joinToString(", ")
}

@Composable
private fun Stat(label: String, value: String) {
    Row {
        Text(label, Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
