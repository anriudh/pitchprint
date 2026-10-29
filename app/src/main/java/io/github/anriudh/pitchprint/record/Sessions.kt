package io.github.anriudh.pitchprint.record

import android.content.Context
import io.github.anriudh.pitchprint.storage.SessionArchive
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class SessionSummary(
    /** The session folder, or its zip once it has been archived. */
    val file: File,
    val startWallMs: Long?,
    val endWallMs: Long?,
    val splits: Int,
    val markers: Int,
    val bytes: Long,
    val finished: Boolean,
) {
    val name get() = Sessions.name(file)
    val zipped get() = file.isFile
}

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

    fun name(f: File) = f.name.removeSuffix(SessionArchive.EXT)

    /** Session folders that have not been zipped yet. */
    fun folders(context: Context): List<File> = root(context).listFiles()?.filter { it.isDirectory }.orEmpty()

    /**
     * Sessions newest first (names start with the start time), each a folder or a zip. While a
     * folder is being replaced by its zip both can exist for a moment; the zip wins.
     */
    fun list(context: Context): List<File> = list(root(context))

    fun list(root: File): List<File> =
        root.listFiles()
            ?.filter { it.isDirectory || (it.isFile && it.name.endsWith(SessionArchive.EXT)) }
            ?.sortedBy { it.isFile }
            ?.associateBy(::name)
            ?.values
            ?.sortedByDescending(::name)
            .orEmpty()

    /** Reads only events.jsonl and file sizes, so it stays quick for long sessions. */
    fun summarize(file: File): SessionSummary {
        if (file.isFile) {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("${name(file)}/${Stream.EVENTS.fileName}")
                val lines = entry?.let { zip.getInputStream(it).bufferedReader().readLines() }.orEmpty()
                return summarize(file, lines, file.length())
            }
        }
        val events = File(file, Stream.EVENTS.fileName)
        val lines = if (events.exists()) events.readLines() else emptyList()
        return summarize(file, lines, file.listFiles()?.sumOf { it.length() } ?: 0)
    }

    private fun summarize(file: File, lines: List<String>, bytes: Long): SessionSummary {
        var start: Long? = null
        var end: Long? = null
        var splits = 0
        var markers = 0
        var finished = false
        for (line in lines) {
            val e = try {
                JSONObject(line)
            } catch (ex: JSONException) {
                continue
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
        return SessionSummary(file, start, end, splits, markers, bytes, finished)
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
