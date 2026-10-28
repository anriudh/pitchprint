package io.github.anriudh.pitchprint.record

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Where session folders live, and which one is unfinished. */
object Sessions {
    private const val KEY_ACTIVE = "active_session"

    // App-specific external storage, so `adb pull` works without root. Uninstalling deletes it.
    fun base(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun root(context: Context) = File(base(context), "sessions")

    fun newDir(context: Context): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())
        val suffix = String.format(Locale.ROOT, "%04x", Random.nextInt(0x10000))
        return File(root(context), "${stamp}_$suffix")
    }

    fun activeDir(context: Context): File? =
        prefs(context).getString(KEY_ACTIVE, null)?.let { File(root(context), it) }?.takeIf { it.isDirectory }

    // commit() rather than apply(): the pointer must be on disk before recording relies on it.
    fun setActive(context: Context, dir: File?) {
        val edit = prefs(context).edit()
        if (dir == null) edit.remove(KEY_ACTIVE) else edit.putString(KEY_ACTIVE, dir.name)
        edit.commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
}
