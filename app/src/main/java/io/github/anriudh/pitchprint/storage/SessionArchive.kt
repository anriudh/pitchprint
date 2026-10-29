package io.github.anriudh.pitchprint.storage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zips a session folder in the export layout (every file inside a directory named after the
 * session), so a stored session and an exported one are the same file.
 */
object SessionArchive {
    const val EXT = ".zip"
    const val PART = ".part"
    const val BAD = ".bad"

    class Cancelled : IOException("cancelled")

    fun zipFor(dir: File) = File(dir.parentFile, dir.name + EXT)

    private fun files(dir: File) = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /** Writes [dir] into [out] and fsyncs it. [onProgress] gets the fraction of bytes done, in whole-percent steps. */
    fun write(dir: File, out: File, cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}) {
        val files = files(dir)
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        var lastPct = -1
        val buf = ByteArray(64 * 1024)
        val fos = FileOutputStream(out)
        ZipOutputStream(BufferedOutputStream(fos)).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry("${dir.name}/${f.name}").apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct / 100f)
                        }
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            fos.fd.sync()
        }
    }

    /**
     * Throws unless every file in [dir] is in [zip] byte for byte. ZipInputStream also checks each
     * entry's CRC and size as it reads. With [folderMayBeShort], the zip may hold files the folder
     * no longer has, which is what a crash part-way through deleting the folder leaves.
     */
    fun verify(dir: File, zip: File, cancelled: () -> Boolean = { false }, folderMayBeShort: Boolean = false) {
        val expected = files(dir).associateByTo(mutableMapOf()) { "${dir.name}/${it.name}" }
        val a = ByteArray(64 * 1024)
        val b = ByteArray(64 * 1024)
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val f = expected.remove(entry.name)
                if (f == null) {
                    if (!folderMayBeShort) throw IOException("unexpected entry ${entry.name}")
                    while (zin.read(a) >= 0) if (cancelled()) throw Cancelled()
                    continue
                }
                f.inputStream().use { src ->
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = zin.read(a)
                        if (n < 0) break
                        if (fill(src, b, n) != n || !same(a, b, n)) throw IOException("${entry.name} differs")
                    }
                    if (src.read() >= 0) throw IOException("${entry.name} is short in the zip")
                }
            }
        }
        if (expected.isNotEmpty()) throw IOException("${expected.keys.first()} missing from the zip")
    }

    /**
     * Replaces [dir] with a checked zip next to it and returns the zip. On any failure or cancel
     * the folder is left as it was and nothing else remains. [afterWrite] lets tests damage the zip.
     */
    fun archive(
        dir: File,
        cancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        afterWrite: (File) -> Unit = {},
    ): File {
        val out = zipFor(dir)
        val part = File(out.path + PART)
        try {
            write(dir, part, cancelled, onProgress)
            afterWrite(part)
            verify(dir, part, cancelled)
            if (cancelled()) throw Cancelled()
            if (!part.renameTo(out)) throw IOException("could not rename ${part.name}")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        // From here a crash leaves both the zip and the folder; recover() finishes the job.
        dir.deleteRecursively()
        return out
    }

    /**
     * Cleans up after a crash part-way through [archive]. Leftover parts go. A zip next to its
     * folder normally means the folder delete was cut short, so the folder goes once the zip is
     * checked against it. If the check fails, the zip is renamed to .bad and kept, never deleted.
     * Returns what it did, for the log.
     */
    fun recover(root: File): List<String> {
        val done = mutableListOf<String>()
        root.listFiles()?.filter { it.isFile && it.name.endsWith(PART) }?.forEach {
            if (it.delete()) done += "removed ${it.name}"
        }
        root.listFiles()?.filter { it.isFile && it.name.endsWith(EXT) }?.forEach { zip ->
            val dir = File(root, zip.name.removeSuffix(EXT))
            if (!dir.isDirectory) return@forEach
            val ok = try {
                verify(dir, zip, folderMayBeShort = true)
                true
            } catch (e: IOException) {
                false
            }
            if (ok) {
                dir.deleteRecursively()
                done += "removed folder ${dir.name}, zip checked"
            } else if (zip.renameTo(File(zip.path + BAD))) {
                done += "kept ${zip.name} as ${zip.name}$BAD, folder differs"
            }
        }
        return done
    }

    private fun fill(input: InputStream, buf: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = input.read(buf, got, len - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun same(a: ByteArray, b: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (a[i] != b[i]) return false
        return true
    }
}
