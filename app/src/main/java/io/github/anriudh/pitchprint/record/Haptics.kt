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
    fun confirm(context: Context) {
        vibrate(context, VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Three pulses at the end of a corner hold, so it can't be mistaken for a marker buzz. */
    fun cornerDone(context: Context) {
        vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1))
    }

    // Alarm usage so the buzz still happens in silent mode and while the app is in the background.
    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, effect: VibrationEffect) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
