package io.github.anriudh.pitchprint.storage

import io.github.anriudh.pitchprint.record.Sessions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream
import kotlin.math.abs

class SessionArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val name = "2026-11-01_0912_ab12"

    private val events = listOf(
        """{"t_ns":1,"wall_ms":1761970000000,"type":"session_start"}""",
        """{"t_ns":2,"wall_ms":1761970000100,"type":"run_start","reason":"new"}""",
        """{"t_ns":3,"wall_ms":1761970001000,"type":"split_start","n":1}""",
        """{"t_ns":4,"wall_ms":1761970002000,"type":"marker","key":"up"}""",
        """{"t_ns":5,"wall_ms":1761970003000,"type":"marker","key":"down"}""",
        """{"t_ns":6,"wall_ms":1761970004000,"type":"stop","reason":"user"}""",
    ).joinToString("\n", postfix = "\n")

    /** A session folder plus a copy of it that the test keeps to compare against. */
    private fun session(root: File = tmp.newFolder("sessions")): Pair<File, File> {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "events.jsonl").writeText(events)
        File(dir, "accel.csv").writeText(
            buildString {
                append("t_ns,x,y,z\n")
                for (i in 0 until 50_000) append(5_000_000L * i).append(',').append(i % 97 * 0.0123).append(",9.81,-0.5\n")
            },
        )
        File(dir, "nav.csv").writeText("")
        File(dir, "crash.txt").writeText("1761970000500 main\njava.lang.IllegalStateException\n")
        dir.listFiles()!!.forEachIndexed { i, f -> f.setLastModified(1_761_970_000_000L + i * 7_000L) }
        val copy = tmp.newFolder("copy-${System.nanoTime()}")
        dir.copyRecursively(File(copy, name))
        for (f in dir.listFiles()!!) File(copy, "$name/${f.name}").setLastModified(f.lastModified())
        return dir to File(copy, name)
    }

    private fun entries(zip: File): Map<String, Pair<ByteArray, Long>> {
        val out = linkedMapOf<String, Pair<ByteArray, Long>>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes() to e.time
            }
        }
        return out
    }

    @Test fun archiveReplacesFolderWithIdenticalZip() {
        val (dir, copy) = session()
        var last = 0f
        val zip = SessionArchive.archive(dir, onProgress = { last = it })
        assertEquals(File(dir.parentFile, "$name.zip"), zip)
        assertFalse(dir.exists())
        assertFalse(File(zip.path + ".part").exists())
        assertEquals(1f, last)
        val got = entries(zip)
        val want = copy.listFiles()!!.sortedBy { it.name }
        assertEquals(want.map { "$name/${it.name}" }, got.keys.toList())
        for (f in want) {
            val (bytes, time) = got.getValue("$name/${f.name}")
            assertArrayEquals(f.name, f.readBytes(), bytes)
            // Zip timestamps have 2 s resolution.
            assertTrue(f.name, abs(time - f.lastModified()) <= 2_000)
        }
        assertTrue(zip.length() < StoragePolicy.sizeOf(copy) / 3)
    }

    @Test fun damagedZipLeavesFolderAlone() {
        val (dir, copy) = session()
        try {
            SessionArchive.archive(dir, afterWrite = { part ->
                // Flip a byte in the middle of the compressed data.
                RandomAccessFile(part, "rw").use { r ->
                    r.seek(r.length() / 2)
                    val b = r.read()
                    r.seek(r.length() / 2)
                    r.write(b xor 0xff)
                }
            })
            fail("damaged zip was accepted")
        } catch (e: IOException) {
            // expected
        }
        assertSameTree(copy, dir)
        assertEquals(listOf(name), dir.parentFile!!.list()!!.toList())
    }

    @Test fun cancelLeavesFolderAlone() {
        val (dir, copy) = session()
        var calls = 0
        try {
            SessionArchive.archive(dir, cancelled = { ++calls > 3 })
            fail("not cancelled")
        } catch (e: SessionArchive.Cancelled) {
            // expected
        }
        assertSameTree(copy, dir)
        assertEquals(listOf(name), dir.parentFile!!.list()!!.toList())
    }

    @Test fun cancelDuringCheckLeavesFolderAlone() {
        val (dir, copy) = session()
        var writing = true
        var checks = 0
        try {
            SessionArchive.archive(
                dir,
                cancelled = { !writing && ++checks > 2 },
                afterWrite = { writing = false },
            )
            fail("not cancelled")
        } catch (e: SessionArchive.Cancelled) {
            // expected
        }
        assertSameTree(copy, dir)
        assertEquals(listOf(name), dir.parentFile!!.list()!!.toList())
    }

    @Test fun recoverRemovesLeftoverPart() {
        val root = tmp.newFolder("sessions")
        val (dir, copy) = session(root)
        File(root, "$name.zip.part").writeText("half a zip")
        val did = SessionArchive.recover(root)
        assertEquals(1, did.size)
        assertEquals(listOf(name), root.list()!!.toList())
        assertSameTree(copy, dir)
    }

    @Test fun recoverFinishesInterruptedFolderDelete() {
        val root = tmp.newFolder("sessions")
        val (dir, copy) = session(root)
        val zip = SessionArchive.zipFor(dir)
        SessionArchive.write(dir, zip)
        // A crash part-way through deleting the folder after the rename.
        File(dir, "accel.csv").delete()
        SessionArchive.recover(root)
        assertFalse(dir.exists())
        assertEquals(copy.listFiles()!!.size, entries(zip).size)
    }

    @Test fun recoverKeepsBothWhenZipDisagrees() {
        val root = tmp.newFolder("sessions")
        val (dir, copy) = session(root)
        val zip = SessionArchive.zipFor(dir)
        SessionArchive.write(dir, zip)
        File(dir, "events.jsonl").appendText("""{"type":"later"}""" + "\n")
        File(copy, "events.jsonl").appendText("""{"type":"later"}""" + "\n")
        SessionArchive.recover(root)
        assertSameTree(copy, dir)
        assertFalse(zip.exists())
        assertTrue(File(root, "$name.zip.bad").isFile)
    }

    @Test fun summaryIsTheSameFromZip() {
        val (dir, _) = session()
        val before = Sessions.summarize(dir)
        val zip = SessionArchive.archive(dir)
        val after = Sessions.summarize(zip)
        assertEquals(before.copy(file = zip, bytes = 0), after.copy(bytes = 0))
        assertEquals(1, after.splits)
        assertEquals(2, after.markers)
        assertTrue(after.finished)
        assertEquals(zip.length(), after.bytes)
        assertEquals(name, after.name)
        assertTrue(after.zipped)
    }

    @Test fun listPrefersZipAndSortsNewestFirst() {
        val root = tmp.newFolder("sessions")
        File(root, "2026-11-01_0900_aaaa").mkdirs()
        File(root, "2026-11-01_1000_bbbb").mkdirs()
        File(root, "2026-11-01_1000_bbbb.zip").writeText("")
        File(root, "2026-10-31_0900_cccc.zip").writeText("")
        File(root, "2026-10-31_0800_dddd.zip.part").writeText("")
        File(root, "2026-10-31_0700_eeee.zip.bad").writeText("")
        val names = Sessions.list(root).map { it.name }
        assertEquals(listOf("2026-11-01_1000_bbbb.zip", "2026-11-01_0900_aaaa", "2026-10-31_0900_cccc.zip"), names)
    }

    private fun assertSameTree(want: File, got: File) {
        val w = want.listFiles()!!.sortedBy { it.name }
        val g = got.listFiles()!!.sortedBy { it.name }
        assertEquals(w.map { it.name }, g.map { it.name })
        for ((a, b) in w.zip(g)) assertArrayEquals(a.name, a.readBytes(), b.readBytes())
    }
}
