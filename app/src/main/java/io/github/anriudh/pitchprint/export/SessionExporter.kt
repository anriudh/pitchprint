package io.github.anriudh.pitchprint.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.anriudh.pitchprint.storage.SessionArchive
import java.io.File

/** Hands a session zip to the share sheet. Zipped sessions are shared as they are stored. */
object SessionExporter {
    fun cacheDir(context: Context) = File(context.cacheDir, "exports")

    /**
     * Zips a folder that can't be archived yet (the unfinished session) into the cache. Blocks
     * while zipping. Only a complete zip ever has the final name, so a failed export can't be shared.
     */
    fun zipToCache(context: Context, session: File, cancelled: () -> Boolean, onProgress: (Float) -> Unit): File {
        val out = File(cacheDir(context).apply { mkdirs() }, "${session.name}${SessionArchive.EXT}")
        val part = File(out.path + SessionArchive.PART)
        try {
            SessionArchive.write(session, part, cancelled, onProgress)
            if (!part.renameTo(out)) error("could not rename ${part.name}")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
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
