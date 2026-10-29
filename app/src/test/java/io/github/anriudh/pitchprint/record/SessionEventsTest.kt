package io.github.anriudh.pitchprint.record

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventsTest {
    private var wall = 1_000L

    private fun line(type: String, vararg fields: Pair<String, Any?>): String {
        wall += 1_000
        val o = JSONObject().put("t_ns", wall * 1_000_000).put("wall_ms", wall).put("type", type)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        return o.toString() + "\n"
    }

    private val labels = SplitLabels("full_half", "S", "MID", "W", "pocket")

    private fun splitStart(n: Int, l: SplitLabels = labels) = line("split_start", "n" to n, *l.fields())

    @Test fun openSplitSurvivesReplay() {
        val text = line("session_start") + line("run_start", "reason" to "new") + splitStart(1) +
            line("marker", "key" to "up", "screen_on" to false)
        val s = replay(text)
        assertEquals(1, s.splits)
        assertEquals(1, s.split?.n)
        assertEquals(labels, s.split?.labels)
        assertEquals(4_000L, s.split?.startWallMs)
    }

    @Test fun numberingContinuesAfterTwoSplits() {
        val text = splitStart(1) + line("split_end", "n" to 1, "reason" to "user") +
            splitStart(2) + line("split_end", "n" to 2, "reason" to "user")
        val s = replay(text)
        assertEquals(2, s.splits)
        assertNull(s.split)
        assertTrue(s.closing("stop").isEmpty())
    }

    @Test fun customSplitKeepsNoteAndNullHalf() {
        val custom = SplitLabels("custom", null, "ANY", "N", "hand", "north box to halfway")
        assertEquals(custom, replay(splitStart(1, custom)).split?.labels)
        assertEquals(custom, SplitLabels.fromJson(JSONObject(custom.toJson().toString())))
    }

    @Test fun pauseAndResume() {
        assertTrue(replay(line("pause")).paused)
        assertFalse(replay(line("pause") + line("resume", "reason" to "user")).paused)
    }

    @Test fun openTestSegment() {
        val s = replay(line("test_start", "test" to "line_walk", "carry" to "hand", "note" to null))
        assertEquals(TestSegment("line_walk", "hand", null, 2_000L), s.test)
        assertNull(replay(line("test_start", "test" to "shuttle", "carry" to "hand") + line("test_end")).test)
    }

    @Test fun cornerHoldOpensAndCloses() {
        assertEquals(CornerHold("SW", 2_000L), replay(line("corner_start", "point" to "SW", "hold_s" to 30)).corner)
        assertNull(replay(line("corner_start", "point" to "SW") + line("corner_end", "point" to "SW")).corner)
        assertNull(replay(line("corner_start", "point" to "HE") + line("corner_cancel", "point" to "HE")).corner)
    }

    @Test fun runStartKeepsState() {
        val s = replay(splitStart(1) + line("pause") + line("run_start", "reason" to "process_restart"))
        assertEquals(1, s.split?.n)
        assertTrue(s.paused)
    }

    @Test fun stopClearsEverything() {
        val text = splitStart(1) + line("pause") + line("test_start", "test" to "shuttle", "carry" to "hand") +
            line("corner_start", "point" to "NW") + line("stop", "reason" to "user")
        assertEquals(SessionState(splits = 1), replay(text))
    }

    @Test fun closingOrderIsInnermostFirst() {
        val text = splitStart(1) + line("pause") + line("test_start", "test" to "shuttle", "carry" to "hand") +
            line("corner_start", "point" to "NW")
        val events = replay(text).closing("stop")
        assertEquals(listOf("corner_cancel", "test_end", "resume", "split_end"), events.map { it.type })
        assertEquals(listOf("point" to "NW", "reason" to "stop"), events[0].fields.toList())
        assertEquals(listOf("n" to 1, "reason" to "stop"), events[3].fields.toList())
    }

    @Test fun skipsGarbageUnknownAndPartialLines() {
        val text = "not json\n[1,2]\n" + line("wat") + line("corner_start") + splitStart(1) +
            "{\"type\":\"pause\""
        val s = replay(text)
        assertEquals(1, s.split?.n)
        assertFalse(s.paused)
        assertNull(s.corner)
    }

    @Test fun emptyTextIsFreshState() {
        assertEquals(SessionState(), replay(""))
    }
}
