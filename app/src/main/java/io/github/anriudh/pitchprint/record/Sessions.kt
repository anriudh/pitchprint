package io.github.anriudh.pitchprint.record

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class SessionSummary(
    val dir: File,
    val startWallMs: Long?,
    val endWallMs: Long?,
    val splits: Int,
    val markers: Int,
    val bytes: Long,
    val finished: Boolean,
)

/** Where session folders live, and which one is unfinished. */
object Sessions {
    private const val KEY_ACTIVE = "active_session"
    private const val KEY_LAST_SPLIT = "last_split"

    // App-specific external storage, so `adb pull` works without root. Uninstalling deletes it.
    fun base(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun root(context: Context) = File(base(context), "sessions")

    fun newDir(context: Context): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())
        val suffix = String.format(Locale.ROOT, "%04x", Random.nextInt(0x10000))
        return File(root(context), "${stamp}_$suffix")
    }

    /** Session folders, newest first (folder names start with the start time). */
    fun list(context: Context): List<File> =
        root(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()

    /** Reads only events.jsonl and file sizes, so it stays quick for long sessions. */
    fun summarize(dir: File): SessionSummary {
        var start: Long? = null
        var end: Long? = null
        var splits = 0
        var markers = 0
        var finished = false
        val events = File(dir, Stream.EVENTS.fileName)
        if (events.exists()) {
            events.forEachLine { line ->
                val e = try {
                    JSONObject(line)
                } catch (ex: JSONException) {
                    return@forEachLine
                }
                val wall = e.optLong("wall_ms").takeIf { it > 0 }
                if (start == null) start = wall
                if (wall != null) end = wall
                when (e.optString("type")) {
                    "split_start" -> splits++
                    "marker" -> markers++
                    "stop" -> finished = true
                    "run_start" -> finished = false
                }
            }
        }
        val bytes = dir.listFiles()?.sumOf { it.length() } ?: 0
        return SessionSummary(dir, start, end, splits, markers, bytes, finished)
    }

    fun activeDir(context: Context): File? =
        prefs(context).getString(KEY_ACTIVE, null)?.let { File(root(context), it) }?.takeIf { it.isDirectory }

    // commit() rather than apply(): the pointer must be on disk before recording relies on it.
    fun setActive(context: Context, dir: File?) {
        val edit = prefs(context).edit()
        if (dir == null) edit.remove(KEY_ACTIVE) else edit.putString(KEY_ACTIVE, dir.name)
        edit.commit()
    }

    /** Labels of the last split started, to prefill the next split sheet. */
    fun lastSplit(context: Context): SplitLabels? = try {
        prefs(context).getString(KEY_LAST_SPLIT, null)?.let { SplitLabels.fromJson(JSONObject(it)) }
    } catch (e: JSONException) {
        null
    }

    fun saveLastSplit(context: Context, labels: SplitLabels) {
        prefs(context).edit().putString(KEY_LAST_SPLIT, labels.toJson().toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
}
