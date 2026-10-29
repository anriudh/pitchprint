package io.github.anriudh.pitchprint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.anriudh.pitchprint.device.Checks
import io.github.anriudh.pitchprint.device.Fix
import io.github.anriudh.pitchprint.record.Haptics
import io.github.anriudh.pitchprint.record.Notifications
import io.github.anriudh.pitchprint.record.Recorder
import io.github.anriudh.pitchprint.record.Session
import io.github.anriudh.pitchprint.record.Sessions
import io.github.anriudh.pitchprint.ui.DeviceCheckScreen
import io.github.anriudh.pitchprint.ui.HomeScreen
import io.github.anriudh.pitchprint.ui.RecordingScreen
import io.github.anriudh.pitchprint.ui.SessionsScreen

private enum class Screen { HOME, SESSIONS, DEVICE }

class MainActivity : ComponentActivity() {
    private var interrupted by mutableStateOf<String?>(null)
    private var checksVersion by mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checksVersion++
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) Recorder.start(this)
    }

    private val fixPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        checksVersion++
        // Once Android stops asking, a request returns denied straight away; settings is the only way left.
        val blocked = result.filterValues { !it }.keys.filterNot { shouldShowRequestPermissionRationale(it) }
        if (blocked.isNotEmpty()) Checks.intent(this, Fix.APP_DETAILS)?.let(::startActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleResume(intent)
        setContent {
            MaterialTheme {
                val live by Recorder.live.collectAsState()
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                LaunchedEffect(live.recording) { refreshInterrupted() }
                BackHandler(enabled = !live.recording && screen != Screen.HOME) { screen = Screen.HOME }
                when {
                    live.recording -> RecordingScreen(live, onStop = { Recorder.stop(this) })
                    screen == Screen.SESSIONS -> SessionsScreen(onBack = { screen = Screen.HOME })
                    screen == Screen.DEVICE -> DeviceCheckScreen(onBack = { screen = Screen.HOME })
                    else -> HomeScreen(
                        interrupted = interrupted,
                        checksVersion = checksVersion,
                        onNewSession = ::newSession,
                        onResume = { Recorder.resume(this) },
                        onFinish = ::finishInterrupted,
                        onSessions = { screen = Screen.SESSIONS },
                        onDeviceCheck = { screen = Screen.DEVICE },
                        onFix = ::fix,
                    )
                }
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
        checksVersion++
    }

    private fun fix(fix: Fix) {
        val notificationsPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        when {
            fix == Fix.LOCATION_PERMISSION -> fixPermissions.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            fix == Fix.NOTIFICATIONS && notificationsPermission ->
                fixPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else -> Checks.intent(this, fix)?.let(::startActivity)
        }
    }

    // With the app in front, volume keys reach the activity first; with the screen off or another
    // app in front, the recorder's media session gets them instead.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = Recorder.session
        if (s == null || !isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        val key = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"
        s.event("key", "source" to "activity", "key" to key, "action" to "down", "repeat" to event.repeatCount)
        // Holding a key repeats it; only the first down is a press.
        if (event.repeatCount == 0) {
            s.marker(key, screenOn = true)
            Haptics.confirm(this)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val s = Recorder.session
        if (s == null || !isVolumeKey(keyCode)) return super.onKeyUp(keyCode, event)
        val key = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"
        s.event("key", "source" to "activity", "key" to key, "action" to "up")
        return true
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

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
