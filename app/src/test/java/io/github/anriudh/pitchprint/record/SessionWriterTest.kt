package io.github.anriudh.pitchprint.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class SessionWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun truncateRemovesPartialLastLine() {
        val f = tmp.newFile().apply { writeText("a,1\nb,2\nc,") }
        assertEquals(2L, truncateToLastNewline(f))
        assertEquals("a,1\nb,2\n", f.readText())
    }

    @Test fun truncateLeavesCompleteFileAlone() {
        val f = tmp.newFile().apply { writeText("a,1\nb,2\n") }
        assertEquals(0L, truncateToLastNewline(f))
        assertEquals("a,1\nb,2\n", f.readText())
    }

    @Test fun truncateEmptiesFileWithoutNewline() {
        val f = tmp.newFile().apply { writeText("t_ns,x") }
        assertEquals(6L, truncateToLastNewline(f))
        assertEquals("", f.readText())
    }

    @Test fun truncateFindsNewlineAcrossBlocks() {
        val body = "x".repeat(10_000) + "\n"
        val f = tmp.newFile().apply { writeText(body + "y".repeat(5_000)) }
        assertEquals(5_000L, truncateToLastNewline(f))
        assertEquals(body, f.readText())
    }

    @Test fun writesHeaderOnceAndAppendsAfterResume() {
        val dir = tmp.newFolder("s")
        SessionWriter(dir).run {
            start()
            append(Stream.ACCEL, "1,0.1,0.2,9.8")
            close()
        }
        // Simulate a kill in the middle of a line.
        File(dir, "accel.csv").appendText("2,0.1,0.")
        val repaired = mutableListOf<Pair<Stream, Long>>()
        SessionWriter(dir, onRepair = { s, n -> repaired += s to n }).run {
            start()
            append(Stream.ACCEL, "3,0.3,0.4,9.7")
            close()
        }
        assertEquals("t_ns,x,y,z\n1,0.1,0.2,9.8\n3,0.3,0.4,9.7\n", File(dir, "accel.csv").readText())
        assertEquals(listOf(Stream.ACCEL to 8L), repaired)
    }

    @Test fun flushAndWaitMakesLinesVisibleBeforeClose() {
        val dir = tmp.newFolder("s")
        val w = SessionWriter(dir, flushMs = 60_000)
        w.start()
        w.append(Stream.EVENTS, """{"type":"marker"}""")
        assertTrue(w.flushAndWait(2_000))
        assertEquals("{\"type\":\"marker\"}\n", File(dir, "events.jsonl").readText())
        w.close()
    }

    @Test fun numbersIgnoreLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val line = StringBuilder().append(1L).col(13.128468912345678).col(0.25f).col(null).col(7).toString()
            assertEquals("1,13.128468912345678,0.25,,7", line)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
