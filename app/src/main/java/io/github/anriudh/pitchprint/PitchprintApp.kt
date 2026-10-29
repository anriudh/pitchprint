package io.github.anriudh.pitchprint

import android.app.Application
import io.github.anriudh.pitchprint.record.CrashLog
import io.github.anriudh.pitchprint.record.Notifications
import io.github.anriudh.pitchprint.storage.Storage

class PitchprintApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
        Storage.tidy(this, "app start")
    }
}
