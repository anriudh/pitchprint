package io.github.anriudh.pitchprint.record

import org.json.JSONException
import org.json.JSONObject

/** Labels from the split sheet, as written to events.jsonl. */
data class SplitLabels(
    val layout: String,
    val half: String?,
    val position: String,
    val attack: String,
    val carry: String,
    val note: String? = null,
) {
    fun fields(): Array<Pair<String, Any?>> = arrayOf(
        "layout" to layout,
        "half" to half,
        "position" to position,
        "attack" to attack,
        "carry" to carry,
        "note" to note,
    )

    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in fields()) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = SplitLabels(
            layout = o.getString("layout"),
            half = o.str("half"),
            position = o.getString("position"),
            attack = o.getString("attack"),
            carry = o.getString("carry"),
            note = o.str("note"),
        )
    }
}

data class Split(val n: Int, val labels: SplitLabels, val startWallMs: Long)

data class TestSegment(val test: String, val carry: String, val note: String?, val startWallMs: Long)

data class CornerHold(val point: String, val startWallMs: Long)

class Event(val type: String, vararg val fields: Pair<String, Any?>)

/** What is open in a session: the current split, pause, test segment and corner hold. */
data class SessionState(
    val splits: Int = 0,
    val split: Split? = null,
    val paused: Boolean = false,
    val test: TestSegment? = null,
    val corner: CornerHold? = null,
) {
    /** The state after one event line. Throws JSONException if a field it needs is missing. */
    fun apply(e: JSONObject): SessionState {
        val wall = e.optLong("wall_ms")
        return when (e.optString("type")) {
            "split_start" -> {
                val n = e.getInt("n")
                copy(splits = maxOf(splits, n), split = Split(n, SplitLabels.fromJson(e), wall))
            }
            "split_end" -> copy(split = null)
            "pause" -> copy(paused = true)
            "resume" -> copy(paused = false)
            "corner_start" -> copy(corner = CornerHold(e.getString("point"), wall))
            "corner_end", "corner_cancel" -> copy(corner = null)
            "test_start" -> copy(test = TestSegment(e.getString("test"), e.getString("carry"), e.str("note"), wall))
            "test_end" -> copy(test = null)
            "stop" -> SessionState(splits = splits)
            else -> this
        }
    }

    /** Events that close everything still open, innermost first. */
    fun closing(reason: String): List<Event> = buildList {
        corner?.let { add(Event("corner_cancel", "point" to it.point, "reason" to reason)) }
        test?.let { add(Event("test_end", "test" to it.test, "reason" to reason)) }
        if (paused) add(Event("resume", "reason" to reason))
        split?.let { add(Event("split_end", "n" to it.n, "reason" to reason)) }
    }
}

/** Rebuilds the state from the text of events.jsonl. Lines that cannot be read are skipped. */
fun replay(text: String): SessionState {
    var state = SessionState()
    // A last line without a newline may have been cut short by a crash; the writer drops it too.
    val complete = text.substring(0, text.lastIndexOf('\n') + 1)
    for (line in complete.lineSequence()) {
        if (line.isBlank()) continue
        state = try {
            state.apply(JSONObject(line))
        } catch (e: JSONException) {
            state
        }
    }
    return state
}

// optString turns a JSON null into the string "null".
private fun JSONObject.str(name: String): String? = if (isNull(name)) null else getString(name)
