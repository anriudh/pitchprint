package io.github.anriudh.pitchprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anriudh.pitchprint.device.DeviceInfo
import org.json.JSONObject

/** The same snapshot that goes into every session's meta.json, laid out for reading. */
@Composable
fun DeviceCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val info = remember { DeviceInfo.snapshot(context) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Device check", style = MaterialTheme.typography.headlineSmall)
            }
            Section("Phone") {
                Line("Model", "${info.optString("manufacturer")} ${info.optString("model")}")
                Line("Android", "${info.optString("android")} (API ${info.optInt("sdk")}), patch ${info.optString("security_patch")}")
                Line("App", info.optString("app_version"))
            }
            Section("GNSS") {
                Line("Hardware", info.optString("gnss_hardware", "not reported"))
                Line("Year", info.optInt("gnss_year").toString())
                Line("Capabilities", info.optString("gnss_capabilities", "not reported"))
                Text(
                    "Developer options > \"Force full GNSS measurements\" does the same as the app's " +
                        "full tracking request, for every app, if you want a backup.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val motion = info.optJSONArray("motion_sensors")
            Section("Motion sensors") {
                if (motion == null || motion.length() == 0) Text("None found")
                for (i in 0 until (motion?.length() ?: 0)) Sensor(motion!!.getJSONObject(i))
            }
            val all = info.optJSONArray("all_sensors")
            Section("All sensors (${all?.length() ?: 0})") {
                for (i in 0 until (all?.length() ?: 0)) Text(all!!.getString(i), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Sensor(s: JSONObject) {
    val minDelay = s.optInt("min_delay_us")
    val maxRate = if (minDelay > 0) "%.1f Hz".format(java.util.Locale.ROOT, 1e6 / minDelay) else "on change"
    Line(
        "${s.optString("type")}: ${s.optString("name")}",
        "${s.optString("vendor")}, max $maxRate, resolution ${s.opt("resolution")}, " +
            "range ${s.opt("max_range")}, FIFO ${s.optInt("fifo_reserved")}/${s.optInt("fifo_max")}",
    )
}
