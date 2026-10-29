package io.github.anriudh.pitchprint.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anriudh.pitchprint.export.SessionExporter
import io.github.anriudh.pitchprint.record.Recorder
import io.github.anriudh.pitchprint.record.SessionSummary
import io.github.anriudh.pitchprint.record.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionSummary>?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { Sessions.list(context).map(Sessions::summarize) }
    }

    fun export(s: SessionSummary) {
        exporting = s.dir.name
        progress = 0f
        message = null
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching { SessionExporter.zip(context, s.dir) { progress = it } }
            }
            exporting = null
            result.onSuccess { zip ->
                val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
                message = String.format(Locale.ROOT, "%s: %.1f MB, zipped in %.1f s", zip.name, zip.length() / 1e6, seconds)
                SessionExporter.share(context, zip)
            }.onFailure { message = "Export failed: ${it.message}" }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Sessions", style = MaterialTheme.typography.headlineSmall)
            }
            message?.let { Text(it) }
            val list = sessions
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No sessions yet.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.dir.name }) { s ->
                        SessionRow(
                            s = s,
                            recording = Recorder.session?.dir == s.dir,
                            progress = if (exporting == s.dir.name) progress else null,
                            canExport = exporting == null,
                            onExport = { export(s) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: SessionSummary, recording: Boolean, progress: Float?, canExport: Boolean, onExport: () -> Unit) {
    val start = s.startWallMs
    val end = s.endWallMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (start != null) DATE.format(Date(start)) else s.dir.name,
                style = MaterialTheme.typography.titleMedium,
            )
            val duration = if (start != null && end != null) elapsed(end - start) else "?"
            val status = when {
                recording -> "recording"
                s.finished -> "ended cleanly"
                else -> "interrupted"
            }
            Text(
                String.format(
                    Locale.ROOT,
                    "%s, %d split%s, %d marker%s, %.1f MB, %s",
                    duration,
                    s.splits,
                    if (s.splits == 1) "" else "s",
                    s.markers,
                    if (s.markers == 1) "" else "s",
                    s.bytes / 1e6,
                    status,
                ),
            )
            Text(s.dir.name, style = MaterialTheme.typography.bodySmall)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedButton(onClick = onExport, enabled = canExport && !recording) { Text("Export") }
            }
        }
    }
}

// Locale.ROOT has no month names and prints "M10".
private val DATE = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK)
