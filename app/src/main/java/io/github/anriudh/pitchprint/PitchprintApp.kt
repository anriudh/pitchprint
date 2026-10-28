package io.github.anriudh.pitchprint

import android.app.Application
import io.github.anriudh.pitchprint.record.CrashLog
import io.github.anriudh.pitchprint.record.Notifications

class PitchprintApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
    }
}
