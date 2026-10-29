package io.github.anriudh.pitchprint.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.anriudh.pitchprint.MainActivity
import io.github.anriudh.pitchprint.R

object Notifications {
    const val ID_RECORDING = 1
    const val EXTRA_RESUME = "resume"
    private const val ID_INTERRUPTED = 2
    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Recording problems", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    const val DEFAULT_TEXT = "Raw logging is running"

    fun recording(context: Context, startWallMs: Long, text: String = DEFAULT_TEXT): Notification {
        val builder = Notification.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Recording")
            .setContentText(text)
            .setWhen(startWallMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(openApp(context, resume = false))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun updateRecording(context: Context, startWallMs: Long, text: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(ID_RECORDING, recording(context, startWallMs, text))
    }

    fun showInterrupted(context: Context) {
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Recording interrupted")
            .setContentText("Tap to resume")
            .setAutoCancel(true)
            .setContentIntent(openApp(context, resume = true))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_INTERRUPTED, notification)
    }

    fun cancelInterrupted(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_INTERRUPTED)
    }

    private fun openApp(context: Context, resume: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_RESUME, resume)
        return PendingIntent.getActivity(
            context,
            if (resume) 1 else 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
