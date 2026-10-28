package io.github.anriudh.pitchprint.record

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Writes uncaught exceptions to crash.txt on the phone. Nothing is sent anywhere. */
object CrashLog {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(context, thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        try {
            val session = Recorder.session
            session?.event("crash", "thread" to thread.name, "error" to error.toString())
            session?.writer?.flushAndWait(1_000)
            val dir = session?.dir ?: File(Sessions.base(context), "crashes")
            dir.mkdirs()
            val text = "${System.currentTimeMillis()} ${thread.name}\n${Log.getStackTraceString(error)}\n"
            FileOutputStream(File(dir, "crash.txt"), true).use {
                it.write(text.toByteArray())
                it.fd.sync()
            }
        } catch (_: Throwable) {
            // The process is dying; there is nothing better to do.
        }
    }
}
