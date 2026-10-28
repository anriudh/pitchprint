package io.github.anriudh.pitchprint.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import io.github.anriudh.pitchprint.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

object DeviceInfo {
    fun snapshot(context: Context): JSONObject {
        val lm = context.getSystemService(LocationManager::class.java)
        val sm = context.getSystemService(SensorManager::class.java)
        val o = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("security_patch", Build.VERSION.SECURITY_PATCH)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_version_code", BuildConfig.VERSION_CODE)
            .put("gnss_hardware", lm.gnssHardwareModelName)
            .put("gnss_year", lm.gnssYearOfHardware)
        if (Build.VERSION.SDK_INT >= 30) o.put("gnss_capabilities", lm.gnssCapabilities.toString())
        val motion = JSONArray()
        for (type in intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD)) {
            sm.getDefaultSensor(type)?.let { motion.put(describe(it)) }
        }
        o.put("motion_sensors", motion)
        o.put("all_sensors", JSONArray(sm.getSensorList(Sensor.TYPE_ALL).map { "${it.name} | ${it.vendor} | ${it.stringType}" }))
        return o
    }

    fun describe(s: Sensor): JSONObject = JSONObject()
        .put("name", s.name)
        .put("vendor", s.vendor)
        .put("version", s.version)
        .put("type", s.stringType)
        .put("resolution", s.resolution)
        .put("max_range", s.maximumRange)
        .put("min_delay_us", s.minDelay)
        .put("max_delay_us", s.maxDelay)
        .put("fifo_max", s.fifoMaxEventCount)
        .put("fifo_reserved", s.fifoReservedEventCount)
        .put("power_ma", s.power)
        .put("wake_up", s.isWakeUpSensor)
}
