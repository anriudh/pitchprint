package io.github.anriudh.pitchprint.storage

import io.github.anriudh.pitchprint.storage.StoragePolicy.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mb = 1_000_000L
    private val hour = 3_600_000L

    @Test fun activeAndRecordingFoldersAreNeverZipped() {
        val dirs = listOf("2026-11-01_1000_c", "2026-10-31_0900_a", "2026-11-01_0900_b", "2026-11-01_1100_d").map { File(it) }
        val names = StoragePolicy.zippable(dirs, active = "2026-11-01_1000_c", recording = "2026-11-01_1100_d").map { it.name }
        assertEquals(listOf("2026-10-31_0900_a", "2026-11-01_0900_b"), names)
        assertEquals(4, StoragePolicy.zippable(dirs, null, null).size)
    }

    @Test fun exportCacheKeepsOnlyTheNewestZipForADay() {
        val dir = tmp.newFolder("exports")
        val now = 1_761_970_000_000L
        fun file(name: String, ageMs: Long) = File(dir, name).apply {
            writeText(name)
            setLastModified(now - ageMs)
        }
        val newest = file("c.zip", 1 * hour)
        val older = file("b.zip", 2 * hour)
        val oldest = file("a.zip", 30 * hour)
        val part = file("d.zip.part", 0)
        val stale = StoragePolicy.staleExports(dir.listFiles()!!.toList(), now).map { it.name }.sorted()
        assertEquals(listOf(oldest.name, older.name, part.name).sorted(), stale)
        assertFalse(newest.name in stale)

        val alone = listOf(file("e.zip", 23 * hour))
        assertEquals(emptyList<File>(), StoragePolicy.staleExports(alone, now))
        val aloneOld = listOf(file("f.zip", 25 * hour))
        assertEquals(aloneOld, StoragePolicy.staleExports(aloneOld, now))
        assertEquals(emptyList<File>(), StoragePolicy.staleExports(emptyList(), now))
    }

    @Test fun crashLogKeepsWholeLinesFromTheEnd() {
        val f = tmp.newFile("crash.txt")
        val lines = (1..20_000).map { "line $it ${"x".repeat(it % 40)}" }
        f.writeText(lines.joinToString("\n", postfix = "\n"))
        assertTrue(f.length() > 300_000)
        assertTrue(StoragePolicy.trimLog(f, 256 * 1024))
        val text = f.readText()
        assertTrue(f.length() <= 256 * 1024)
        assertTrue(text.endsWith(lines.last() + "\n"))
        val first = text.substringBefore('\n')
        assertTrue(first, first in lines)
        assertFalse(File(f.path + ".tmp").exists())
        // A second pass has nothing to do.
        assertFalse(StoragePolicy.trimLog(f, 256 * 1024))
    }

    @Test fun smallOrMissingCrashLogIsLeftAlone() {
        val f = tmp.newFile("crash.txt").apply { writeText("one crash\n") }
        assertFalse(StoragePolicy.trimLog(f))
        assertEquals("one crash\n", f.readText())
        assertFalse(StoragePolicy.trimLog(File(tmp.root, "none.txt")))
    }

    @Test fun budgetAndFreeSpaceThresholds() {
        val free = 24_000 * mb
        assertTrue(Usage(999 * mb, free).ok)
        assertTrue(Usage(1_000 * mb, free).ok)
        assertFalse(Usage(1_001 * mb, free).ok)
        assertTrue(Usage(1_001 * mb, free).overBudget)
        assertFalse(Usage(10 * mb, 999 * mb).ok)
        assertTrue(Usage(10 * mb, 999 * mb).lowFree)
        assertTrue(Usage(10 * mb, 1_000 * mb).ok)
        assertEquals("", StoragePolicy.problem(Usage(10 * mb, free)))
        assertTrue(StoragePolicy.problem(Usage(1_001 * mb, free)).startsWith("Pitchprint holds more than 1.0 GB"))
        assertTrue(StoragePolicy.problem(Usage(10 * mb, 999 * mb)).startsWith("The phone has less than 1.0 GB free"))
        val both = StoragePolicy.problem(Usage(1_001 * mb, 999 * mb))
        assertTrue(both, "holds more than" in both && "less than 1.0 GB free" in both)
    }

    @Test fun checkText() {
        assertEquals("Storage: 23 MB of 1.0 GB, 24 GB free", StoragePolicy.describe(Usage(22_600_000, 24_300 * mb)))
        assertEquals("Storage: 1.1 GB of 1.0 GB, 3.5 GB free", StoragePolicy.describe(Usage(1_100 * mb, 3_500 * mb)))
        assertEquals("0 MB", StoragePolicy.size(0))
    }

    @Test fun sizeOfCountsNestedFiles() {
        val dir = tmp.newFolder("base")
        File(dir, "a").writeBytes(ByteArray(1000))
        File(dir, "sub").mkdirs()
        File(dir, "sub/b").writeBytes(ByteArray(234))
        assertEquals(1234L, StoragePolicy.sizeOf(dir))
    }
}
