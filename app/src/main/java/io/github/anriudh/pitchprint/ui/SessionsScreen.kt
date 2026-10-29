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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
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
import io.github.anriudh.pitchprint.storage.Storage
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
    var confirmDelete by remember { mutableStateOf<SessionSummary?>(null) }
    val version by Storage.version.collectAsState()
    val zipping by Storage.zipping.collectAsState()
    val active = remember(version) { Sessions.activeDir(context)?.name }

    LaunchedEffect(version) {
        sessions = withContext(Dispatchers.IO) { Sessions.list(context).map(Sessions::summarize) }
    }

    fun export(s: SessionSummary) {
        exporting = s.name
        progress = 0f
        message = null
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching { Storage.exportFile(context, s.file) { progress = it } }
            }
            exporting = null
            result.onSuccess { zip ->
                val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
                message = String.format(Locale.ROOT, "%s: %.1f MB, ready in %.1f s", zip.name, zip.length() / 1e6, seconds)
                SessionExporter.share(context, zip)
            }.onFailure { message = "Export failed: ${it.message}" }
        }
    }

    fun delete(s: SessionSummary) {
        confirmDelete = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Storage.delete(context, s.file) }
            message = if (ok) "Deleted ${s.name}." else "Could not delete ${s.name}."
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${s.name}?") },
            text = {
                Text(
                    String.format(
                        Locale.ROOT,
                        "%.1f MB. This can't be undone. Export it first if you need it.",
                        s.bytes / 1e6,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { delete(s) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
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
                    items(list, key = { it.name }) { s ->
                        SessionRow(
                            s = s,
                            recording = Recorder.session?.dir?.name == s.name,
                            active = active == s.name,
                            zipping = zipping == s.name && exporting != s.name,
                            progress = if (exporting == s.name) progress else null,
                            canExport = exporting == null,
                            onExport = { export(s) },
                            onDelete = { confirmDelete = s },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    s: SessionSummary,
    recording: Boolean,
    active: Boolean,
    zipping: Boolean,
    progress: Float?,
    canExport: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val start = s.startWallMs
    val end = s.endWallMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (start != null) DATE.format(Date(start)) else s.name,
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
                    "%s, %d split%s, %d marker%s, %.1f MB%s, %s",
                    duration,
                    s.splits,
                    if (s.splits == 1) "" else "s",
                    s.markers,
                    if (s.markers == 1) "" else "s",
                    s.bytes / 1e6,
                    when {
                        s.zipped -> " zipped"
                        zipping -> ", zipping"
                        else -> ""
                    },
                    status,
                ),
            )
            Text(s.name, style = MaterialTheme.typography.bodySmall)
            if (active && !recording) {
                Text("Resume or finish it on the home screen before deleting it.", style = MaterialTheme.typography.bodySmall)
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onExport, enabled = canExport && !recording) { Text("Export") }
                    if (!recording && !active) {
                        TextButton(onClick = onDelete, enabled = canExport && !zipping) { Text("Delete") }
                    }
                }
            }
        }
    }
}

// Locale.ROOT has no month names and prints "M10".
private val DATE = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK)
