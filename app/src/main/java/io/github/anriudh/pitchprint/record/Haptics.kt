package io.github.anriudh.pitchprint.record

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    /** A short buzz that can be felt through a pocket. */
    fun confirm(context: Context) = pulse(context, 120)

    // Alarm usage so the buzz still happens in silent mode and while the app is in the background.
    @Suppress("DEPRECATION")
    private fun pulse(context: Context, ms: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        val effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
