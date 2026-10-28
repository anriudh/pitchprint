package io.github.anriudh.pitchprint.record

import java.io.BufferedWriter
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Appends lines to the stream files of one session folder.
 *
 * [append] only puts the line on an in-memory queue, so GNSS and sensor callbacks never wait on
 * disk. The writer thread drains the queue and hands data to the OS every [flushMs]. The sync
 * thread fsyncs every [syncMs]; a slow fsync can delay the writer thread but never a caller.
 */
class SessionWriter(
    val dir: File,
    private val flushMs: Long = 500,
    private val syncMs: Long = 10_000,
    private val onRepair: (Stream, Long) -> Unit = { _, _ -> },
) {
    private class Line(val stream: Stream, val text: String)

    private val queue = LinkedBlockingQueue<Any>()
    private val writers = EnumMap<Stream, BufferedWriter>(Stream::class.java)
    private val descriptors = ConcurrentHashMap<Stream, FileDescriptor>()
    private val writerThread = Thread(::runWriter, "pp-writer")
    private val syncThread = Thread(::runSync, "pp-sync")
    private val maxStallNanos = AtomicLong()

    @Volatile private var stopping = false

    val bytesWritten = AtomicLong()

    @Volatile var lastError: String? = null
        private set

    val queueDepth: Int get() = queue.size

    fun start() {
        dir.mkdirs()
        writerThread.start()
        syncThread.start()
    }

    fun append(stream: Stream, text: String) {
        if (!stopping) queue.add(Line(stream, text))
    }

    /** Longest single flush to the OS since the last call, in milliseconds. */
    fun takeMaxStallMs(): Long = maxStallNanos.getAndSet(0) / 1_000_000

    /** Hands everything queued so far to the OS. Used when the app is about to die. */
    fun flushAndWait(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        queue.add(latch)
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Writes out everything queued, fsyncs and closes. Blocks until done. */
    fun close() {
        stopping = true
        syncThread.interrupt()
        syncThread.join()
        writerThread.join()
    }

    private fun runWriter() {
        repairExisting()
        var lastFlush = System.nanoTime()
        while (true) {
            var item = queue.poll(50, TimeUnit.MILLISECONDS)
            while (item != null) {
                handle(item)
                item = queue.poll()
            }
            val done = stopping && queue.isEmpty()
            val now = System.nanoTime()
            if (done || now - lastFlush >= flushMs * 1_000_000) {
                flushAll()
                lastFlush = now
            }
            if (done) break
        }
        for ((stream, w) in writers) {
            try {
                w.flush()
                descriptors[stream]?.sync()
                w.close()
            } catch (e: IOException) {
                lastError = "close ${stream.fileName}: ${e.message}"
            }
        }
    }

    // A crash can leave a half-written last line; cut it off before appending to the file again.
    private fun repairExisting() {
        for (stream in Stream.entries) {
            val file = File(dir, stream.fileName)
            if (!file.exists()) continue
            try {
                val removed = truncateToLastNewline(file)
                if (removed > 0) onRepair(stream, removed)
            } catch (e: IOException) {
                lastError = "repair ${stream.fileName}: ${e.message}"
            }
        }
    }

    private fun handle(item: Any) {
        when (item) {
            is Line -> try {
                val w = writers[item.stream] ?: open(item.stream)
                w.write(item.text)
                w.write('\n'.code)
                bytesWritten.addAndGet(item.text.length + 1L)
            } catch (e: IOException) {
                lastError = "write ${item.stream.fileName}: ${e.message}"
            }
            is CountDownLatch -> {
                flushAll()
                item.countDown()
            }
        }
    }

    private fun open(stream: Stream): BufferedWriter {
        val file = File(dir, stream.fileName)
        val fos = FileOutputStream(file, true)
        descriptors[stream] = fos.fd
        val w = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8), 64 * 1024)
        if (file.length() == 0L && stream.header != null) {
            w.write(stream.header)
            w.write('\n'.code)
        }
        writers[stream] = w
        return w
    }

    private fun flushAll() {
        val start = System.nanoTime()
        for ((stream, w) in writers) {
            try {
                w.flush()
            } catch (e: IOException) {
                lastError = "flush ${stream.fileName}: ${e.message}"
            }
        }
        val took = System.nanoTime() - start
        maxStallNanos.accumulateAndGet(took) { a, b -> maxOf(a, b) }
    }

    private fun runSync() {
        while (!stopping) {
            try {
                Thread.sleep(syncMs)
            } catch (e: InterruptedException) {
                return
            }
            for ((stream, fd) in descriptors) {
                try {
                    fd.sync()
                } catch (e: IOException) {
                    lastError = "sync ${stream.fileName}: ${e.message}"
                }
            }
        }
    }
}

/** Cuts the file back to just after its last newline. Returns the number of bytes removed. */
internal fun truncateToLastNewline(file: File): Long {
    RandomAccessFile(file, "rw").use { raf ->
        val length = raf.length()
        val buf = ByteArray(4096)
        var end = length
        while (end > 0) {
            val start = maxOf(0L, end - buf.size)
            val n = (end - start).toInt()
            raf.seek(start)
            raf.readFully(buf, 0, n)
            for (i in n - 1 downTo 0) {
                if (buf[i] == '\n'.code.toByte()) {
                    val keep = start + i + 1
                    raf.setLength(keep)
                    return length - keep
                }
            }
            end = start
        }
        raf.setLength(0)
        return length
    }
}
