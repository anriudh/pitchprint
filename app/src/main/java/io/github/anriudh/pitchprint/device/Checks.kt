package io.github.anriudh.pitchprint.device

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class Fix { LOCATION_PERMISSION, NOTIFICATIONS, LOCATION_SETTINGS, BATTERY_OPTIMISATION, APP_DETAILS, BATTERY_SAVER }

/** One line on the home screen. [detail] says what is wrong and is only shown when [ok] is false. */
data class Check(val title: String, val ok: Boolean, val detail: String, val fix: Fix, val optional: Boolean = false)

/** Phone settings that can stop a screen-off recording, checked before a session. */
object Checks {
    fun all(context: Context): List<Check> {
        val power = context.getSystemService(PowerManager::class.java)
        val location = context.getSystemService(LocationManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

        val notificationsOn = (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) &&
            notifications.areNotificationsEnabled()
        // With battery saver on, Android may switch GPS off or throttle it while the screen is off.
        val saverOk = !power.isPowerSaveMode || power.locationPowerSaveMode == PowerManager.LOCATION_MODE_NO_CHANGE
        return listOf(
            Check(
                "Precise location",
                granted(Manifest.permission.ACCESS_FINE_LOCATION),
                "Needed for GPS. Choose Precise and While using the app.",
                Fix.LOCATION_PERMISSION,
            ),
            Check(
                "Notifications",
                notificationsOn,
                "The recording notification keeps recording alive and offers resume after a crash.",
                Fix.NOTIFICATIONS,
            ),
            Check("Location on", location.isLocationEnabled, "Location is switched off.", Fix.LOCATION_SETTINGS),
            Check(
                "GPS provider",
                location.isProviderEnabled(LocationManager.GPS_PROVIDER),
                "The GPS provider is off.",
                Fix.LOCATION_SETTINGS,
            ),
            Check(
                "Battery optimisation off",
                power.isIgnoringBatteryOptimizations(context.packageName),
                "Otherwise the phone's battery manager can stop recording with the screen off.",
                Fix.BATTERY_OPTIMISATION,
            ),
            Check(
                "Background use allowed",
                !activity.isBackgroundRestricted,
                "Background use is restricted for this app, so recording can stop with the screen off.",
                Fix.APP_DETAILS,
            ),
            Check(
                if (power.isPowerSaveMode && saverOk) "Battery saver (on, GPS unaffected)" else "Battery saver",
                saverOk,
                "Battery saver limits GPS while the screen is off. Turn it off before recording.",
                Fix.BATTERY_SAVER,
            ),
            Check(
                "Location all the time",
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                "Recommended: lets Android restart recording by itself after a crash. " +
                    "Permissions > Location > Allow all the time.",
                Fix.APP_DETAILS,
                optional = true,
            ),
        )
    }

    /** The settings screen that fixes a check, or null when a permission request does it. */
    fun intent(context: Context, fix: Fix): Intent? {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (fix) {
            Fix.LOCATION_PERMISSION -> null
            Fix.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Fix.LOCATION_SETTINGS -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            Fix.BATTERY_OPTIMISATION -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Fix.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Fix.BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }
    }
}
