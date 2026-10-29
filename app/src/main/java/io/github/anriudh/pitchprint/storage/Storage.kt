package io.github.anriudh.pitchprint.storage

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.anriudh.pitchprint.export.SessionExporter
import io.github.anriudh.pitchprint.record.Recorder
import io.github.anriudh.pitchprint.record.Sessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the app's storage small: zips sessions that are done, clears old export zips and caps the
 * crash log. Everything runs on one thread, so zipping, export and delete never overlap.
 * Recordings are never deleted here; only the user deletes them.
 */
object Storage {
    private const val TAG = "pp-storage"

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "pp-storage") }
    private val queued = AtomicBoolean(false)

    private val zippingNow = MutableStateFlow<String?>(null)
    /** The session being zipped, if any. */
    val zipping: StateFlow<String?> = zippingNow.asStateFlow()

    private val changes = MutableStateFlow(0)
    /** Goes up whenever sessions on disk change, so the list can reload. */
    val version: StateFlow<Int> = changes.asStateFlow()

    // A recording takes priority: zipping stops within one buffer and picks up after the stop.
    private val recording = { Recorder.session != null }

    /** Runs a clean-up soon. Calls while one is already waiting are folded into it. */
    fun tidy(context: Context, why: String) {
        val app = context.applicationContext
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            try {
                tidyNow(app, why)
            } catch (e: Exception) {
                Log.w(TAG, "tidy ($why) failed", e)
            }
        }
    }

    private fun tidyNow(context: Context, why: String) {
        val t0 = SystemClock.elapsedRealtime()
        val did = mutableListOf<String>()
        did += SessionArchive.recover(Sessions.root(context))
        val active = Sessions.activeDir(context)?.name
        for (dir in StoragePolicy.zippable(Sessions.folders(context), active, Recorder.session?.dir?.name)) {
            if (recording()) {
                did += "stopped zipping: recording"
                break
            }
            // Read again for each folder: a session can start or be finished while this runs.
            if (dir.name == Sessions.activeDir(context)?.name) continue
            did += zip(dir) ?: break
        }
        did += clearExports(context, keepNewest = true)
        val crashes = File(Sessions.base(context), "crashes/crash.txt")
        if (StoragePolicy.trimLog(crashes)) did += "trimmed crash log"
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "tidy ($why) took $ms ms: ${did.ifEmpty { listOf("nothing to do") }.joinToString("; ")}")
        if (did.isNotEmpty()) changes.value++
    }

    /** Returns a line for the log, or null if zipping was cancelled. */
    private fun zip(dir: File, onProgress: (Float) -> Unit = {}): String? {
        val t0 = SystemClock.elapsedRealtime()
        val before = StoragePolicy.sizeOf(dir)
        zippingNow.value = dir.name
        try {
            val zip = SessionArchive.archive(dir, recording, onProgress)
            val ms = SystemClock.elapsedRealtime() - t0
            return "zipped ${dir.name}: $before -> ${zip.length()} bytes in $ms ms"
        } catch (e: SessionArchive.Cancelled) {
            Log.i(TAG, "zipping ${dir.name} cancelled after ${SystemClock.elapsedRealtime() - t0} ms")
            return null
        } finally {
            zippingNow.value = null
        }
    }

    private fun clearExports(context: Context, keepNewest: Boolean): List<String> {
        val files = SessionExporter.cacheDir(context).listFiles()?.toList().orEmpty()
        val stale = if (keepNewest) StoragePolicy.staleExports(files, System.currentTimeMillis()) else files
        return stale.filter { it.delete() }.map { "removed export ${it.name}" }
    }

    /**
     * Returns the zip to share for [session], zipping first if needed. Blocks, so call it off the
     * main thread. The unfinished session can't be archived, so it goes through the cache, where
     * only one export zip is ever kept.
     */
    fun exportFile(context: Context, session: File, onProgress: (Float) -> Unit): File = executor.submit<File> {
        val did = SessionArchive.recover(Sessions.root(context)) + clearExports(context, keepNewest = false)
        if (did.isNotEmpty()) Log.i(TAG, "before export: ${did.joinToString("; ")}")
        val zipped = SessionArchive.zipFor(session)
        when {
            session.isFile -> session
            zipped.isFile -> zipped
            session.name == Sessions.activeDir(context)?.name ->
                SessionExporter.zipToCache(context, session, recording, onProgress)
            else -> {
                zip(session, onProgress)?.let { Log.i(TAG, it) } ?: throw SessionArchive.Cancelled()
                changes.value++
                zipped
            }
        }
    }.get()

    /** Deletes a session, unless it is recording or is the one that can still be resumed. */
    fun delete(context: Context, session: File): Boolean = executor.submit<Boolean> {
        val name = Sessions.name(session)
        if (name == Recorder.session?.dir?.name || name == Sessions.activeDir(context)?.name) return@submit false
        val bytes = StoragePolicy.sizeOf(session)
        val ok = if (session.isDirectory) session.deleteRecursively() else session.delete()
        Log.i(TAG, "deleted $name ($bytes bytes): $ok")
        changes.value++
        ok
    }.get()

    fun usage(context: Context) = StoragePolicy.Usage(
        appBytes = StoragePolicy.sizeOf(Sessions.base(context)) + StoragePolicy.sizeOf(context.cacheDir),
        freeBytes = Sessions.base(context).usableSpace,
    )
}
