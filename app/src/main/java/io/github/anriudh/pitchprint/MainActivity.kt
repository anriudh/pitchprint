package io.github.anriudh.pitchprint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anriudh.pitchprint.record.Notifications
import io.github.anriudh.pitchprint.record.Recorder
import io.github.anriudh.pitchprint.record.Session
import io.github.anriudh.pitchprint.record.Sessions
import io.github.anriudh.pitchprint.ui.MainScreen

class MainActivity : ComponentActivity() {
    private var interrupted by mutableStateOf<String?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) Recorder.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleResume(intent)
        setContent {
            MaterialTheme {
                val live by Recorder.live.collectAsState()
                LaunchedEffect(live.recording) { refreshInterrupted() }
                MainScreen(
                    live = live,
                    interrupted = interrupted,
                    onNewSession = ::newSession,
                    onResume = { Recorder.resume(this) },
                    onFinish = ::finishInterrupted,
                    onStop = { Recorder.stop(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResume(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshInterrupted()
    }

    private fun handleResume(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.EXTRA_RESUME, false) == true && Recorder.session == null) {
            Recorder.resume(this)
        }
    }

    private fun newSession() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            Recorder.start(this)
        } else {
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun finishInterrupted() {
        val dir = Sessions.activeDir(this) ?: return
        Thread {
            Session.finishInterrupted(dir)
            Sessions.setActive(this, null)
            runOnUiThread { refreshInterrupted() }
        }.start()
    }

    private fun refreshInterrupted() {
        interrupted = if (Recorder.session == null) Sessions.activeDir(this)?.name else null
    }
}
