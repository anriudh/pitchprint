package io.github.anriudh.pitchprint.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.anriudh.pitchprint.device.Check
import io.github.anriudh.pitchprint.device.Checks
import io.github.anriudh.pitchprint.device.Fix

@Composable
fun HomeScreen(
    interrupted: String?,
    checksVersion: Int,
    onNewSession: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onSessions: () -> Unit,
    onDeviceCheck: () -> Unit,
    onFix: (Fix) -> Unit,
) {
    val context = LocalContext.current
    // Battery saver and location can be switched from quick settings without leaving the app.
    var changes by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                changes++
            }
        }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val checks = remember(checksVersion, changes) { Checks.all(context) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Pitchprint", style = MaterialTheme.typography.headlineSmall)
            if (interrupted != null) {
                Interrupted(interrupted, onResume, onFinish)
            } else {
                Button(onClick = onNewSession, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("New session", style = MaterialTheme.typography.titleMedium)
                }
            }
            val problems = checks.count { !it.ok && !it.optional }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when (problems) {
                            0 -> "Phone settings ready"
                            1 -> "1 setting to fix before recording"
                            else -> "$problems settings to fix before recording"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    for (check in checks) CheckRow(check, onFix)
                }
            }
            OutlinedButton(onClick = onSessions, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Sessions") }
            OutlinedButton(onClick = onDeviceCheck, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Device check") }
        }
    }
}

@Composable
private fun CheckRow(check: Check, onFix: (Fix) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (mark, color) = when {
            check.ok -> "OK" to MaterialTheme.colorScheme.primary
            check.optional -> "Tip" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> "No" to MaterialTheme.colorScheme.error
        }
        Text(mark, Modifier.width(40.dp), color = color, style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f)) {
            Text(check.title, style = MaterialTheme.typography.bodyLarge)
            if (!check.ok) Text(check.detail, style = MaterialTheme.typography.bodySmall)
        }
        if (!check.ok) TextButton(onClick = { onFix(check.fix) }) { Text("Fix") }
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
