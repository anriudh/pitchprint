package io.github.anriudh.pitchprint.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Zips a session folder into the cache and hands it to the share sheet. */
object SessionExporter {
    private fun dir(context: Context) = File(context.cacheDir, "exports")

    /** Old zips are only needed while a share is in progress, so they go on the next app start. */
    fun clearOld(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Blocks while zipping. [onProgress] gets the fraction of bytes done, in whole-percent steps. */
    fun zip(context: Context, session: File, onProgress: (Float) -> Unit): File {
        val out = File(dir(context).apply { mkdirs() }, "pitchprint_${session.name}.zip")
        val part = File(out.path + ".part")
        val files = session.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        var lastPct = -1
        val buf = ByteArray(64 * 1024)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(part))).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry("${session.name}/${f.name}").apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
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
        }
        // Only a complete zip ever has the final name, so a failed export can't be shared.
        if (!part.renameTo(out)) error("could not rename ${part.name}")
        return out
    }

    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the read grant on to the chosen app through the clip data.
        send.clipData = ClipData.newRawUri(zip.name, uri)
        context.startActivity(Intent.createChooser(send, "Export ${zip.name}"))
    }
}
