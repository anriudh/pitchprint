package io.github.anriudh.pitchprint.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** The storage rules, kept free of Android so they run in JVM tests. */
object StoragePolicy {
    const val BUDGET_BYTES = 1_000_000_000L
    const val MIN_FREE_BYTES = 1_000_000_000L
    const val EXPORT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    const val CRASH_LOG_MAX_BYTES = 256 * 1024

    data class Usage(val appBytes: Long, val freeBytes: Long) {
        val overBudget get() = appBytes > BUDGET_BYTES
        val lowFree get() = freeBytes < MIN_FREE_BYTES
        val ok get() = !overBudget && !lowFree
    }

    fun describe(u: Usage) = "Storage: ${size(u.appBytes)} of ${size(BUDGET_BYTES)}, ${size(u.freeBytes)} free"

    fun problem(u: Usage) = listOfNotNull(
        if (u.overBudget) "Pitchprint holds more than ${size(BUDGET_BYTES)}. Export what you need, then delete old sessions." else null,
        if (u.lowFree) "The phone has less than ${size(MIN_FREE_BYTES)} free. A long session can need several hundred MB." else null,
    ).joinToString(" ")

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> {
            val gb = bytes / 1e9
            if (gb >= 10) String.format(Locale.ROOT, "%.0f GB", gb) else String.format(Locale.ROOT, "%.1f GB", gb)
        }
        else -> String.format(Locale.ROOT, "%d MB", Math.round(bytes / 1e6))
    }

    /**
     * Session folders that can be zipped, oldest first: everything except the active session
     * (which may still be resumed) and the one recording. Neither is ever written to again once
     * the active pointer has moved on.
     */
    fun zippable(folders: List<File>, active: String?, recording: String?): List<File> =
        folders.filter { it.name != active && it.name != recording }.sortedBy { it.name }

    /** Export zips to delete: unfinished ones, all but the newest, and the newest once it is a day old. */
    fun staleExports(files: List<File>, nowMs: Long): List<File> {
        val zips = files.filter { it.name.endsWith(SessionArchive.EXT) }.sortedByDescending { it.lastModified() }
        val newestTooOld = zips.take(1).filter { nowMs - it.lastModified() > EXPORT_MAX_AGE_MS }
        return files.filter { !it.name.endsWith(SessionArchive.EXT) } + zips.drop(1) + newestTooOld
    }

    /** Keeps the last [max] bytes of [f], starting at a line boundary. Returns whether it trimmed. */
    fun trimLog(f: File, max: Int = CRASH_LOG_MAX_BYTES): Boolean {
        if (!f.isFile || f.length() <= max) return false
        val tail = RandomAccessFile(f, "r").use { r ->
            r.seek(r.length() - max)
            ByteArray(max).also { r.readFully(it) }
        }
        val start = tail.indexOf('\n'.code.toByte()) + 1
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(tail.copyOfRange(start, tail.size))
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    fun sizeOf(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf(::sizeOf) ?: 0 else f.length()
}
