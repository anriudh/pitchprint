package io.github.anriudh.pitchprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.anriudh.pitchprint.record.Session
import io.github.anriudh.pitchprint.record.SplitLabels

// Values as written to events.jsonl, with what the sheets show for them. The compass hints use
// the turf's landmarks, since nobody on the pitch thinks in compass points.
internal val LAYOUTS = listOf(
    "full_half" to "Full half",
    "half_no_box" to "Half without box",
    "box_only" to "Box only",
    "box_to_box" to "Box to box",
    "custom" to "Custom",
)
private val HALF_LAYOUTS = setOf("full_half", "half_no_box", "box_only")
internal val HALVES = listOf("N" to "North half", "S" to "South half (TAPMI)")
internal val POSITIONS = listOf("GK" to "GK", "DEF" to "DEF", "MID" to "MID", "FWD" to "FWD", "ANY" to "Anywhere")
internal val GOALS = listOf(
    "W" to "West (Marena)",
    "E" to "East (yellow building)",
    "N" to "North (entry)",
    "S" to "South (TAPMI)",
)
internal val CARRY = listOf("hand" to "Hand", "pocket" to "Pocket")
internal val TESTS = listOf(
    "standing_still" to "Standing still",
    "line_walk" to "Line walk",
    "flying_sprint" to "Flying sprint",
    "shuttle" to "Shuttle",
    "scripted" to "Scripted events",
)

internal fun name(options: List<Pair<String, String>>, value: String?) =
    options.firstOrNull { it.first == value }?.second ?: value.orEmpty()

// Across layouts put the goals on the touchlines (east and west); box to box plays north-south.
private fun goalsFor(layout: String?) = when (layout) {
    in HALF_LAYOUTS -> listOf("W", "E")
    "box_to_box" -> listOf("N", "S")
    "custom" -> listOf("W", "E", "N", "S")
    else -> emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(initial: SplitLabels?, onStart: (SplitLabels) -> Unit, onDismiss: () -> Unit) {
    var layout by remember { mutableStateOf(initial?.layout) }
    var half by remember { mutableStateOf(initial?.half) }
    var position by remember { mutableStateOf(initial?.position) }
    var attack by remember { mutableStateOf(initial?.attack) }
    var carry by remember { mutableStateOf(initial?.carry) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }

    val needsHalf = layout in HALF_LAYOUTS
    val goals = goalsFor(layout)
    val ready = layout != null && (!needsHalf || half != null) && position != null &&
        attack in goals && carry != null

    Sheet(onDismiss) {
        Text("Split", style = MaterialTheme.typography.titleLarge)
        Choice("Layout", LAYOUTS, layout) { layout = it }
        if (needsHalf) Choice("Half", HALVES, half) { half = it }
        Choice("Position", POSITIONS, position) { position = it }
        if (goals.isNotEmpty()) Choice("Attacking goal", GOALS.filter { it.first in goals }, attack) { attack = it }
        Choice("Carry", CARRY, carry) { carry = it }
        if (layout == "custom") {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note, e.g. north box to halfway") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                onStart(
                    SplitLabels(
                        layout = layout!!,
                        half = if (needsHalf) half else null,
                        position = position!!,
                        attack = attack!!,
                        carry = carry!!,
                        note = if (layout == "custom") note.trim().ifEmpty { null } else null,
                    ),
                )
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Start split") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CornerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Text("Mark corner", style = MaterialTheme.typography.titleLarge)
        Text(
            "Stand on the point, tap it, then hold still for ${Session.CORNER_HOLD_S} s. " +
                "Three buzzes mean it's done. North is at the top.",
        )
        val rows = listOf(
            listOf("NW" to "North-west\nentry corner", "NE" to "North-east\nroad side"),
            listOf("HW" to "Halfway west\nMarena side", "HE" to "Halfway east\nyellow building side"),
            listOf("SW" to "South-west\nTAPMI, Marena side", "SE" to "South-east\nTAPMI, yellow building side"),
        )
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((point, label) in row) {
                    OutlinedButton(onClick = { onPick(point) }, modifier = Modifier.weight(1f).height(84.dp)) {
                        Text("$point\n$label", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestSheet(initialCarry: String?, onStart: (String, String, String?) -> Unit, onDismiss: () -> Unit) {
    var test by remember { mutableStateOf<String?>(null) }
    var carry by remember { mutableStateOf(initialCarry) }
    var note by remember { mutableStateOf("") }

    Sheet(onDismiss) {
        Text("Test segment", style = MaterialTheme.typography.titleLarge)
        Choice("Test", TESTS, test) { test = it }
        Choice("Carry", CARRY, carry) { carry = it }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note, e.g. SW corner, box side line jog") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onStart(test!!, carry!!, note.trim().ifEmpty { null }) },
            enabled = test != null && carry != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Start test") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
