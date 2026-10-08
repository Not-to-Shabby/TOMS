package ph.toms.conductor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.Stop
import ph.toms.conductor.domain.Trip
import ph.toms.conductor.domain.formatPesos
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.ui.theme.BigButton
import ph.toms.conductor.ui.theme.Gap
import ph.toms.conductor.ui.theme.QuietButton
import ph.toms.conductor.ui.theme.Status
import ph.toms.conductor.ui.theme.StatusChip
import ph.toms.conductor.ui.theme.TomsColors
import ph.toms.conductor.ui.theme.TouchTarget

/** "2 Regular · 1 Student" */
fun partyLabel(trip: Trip, categoryName: (String?) -> String): String =
    trip.passengers.joinToString(" · ") { "${it.count} ${categoryName(it.categoryId)}" }

fun stateWords(state: CardState): Pair<String, Status> = when (state) {
    CardState.ASSIGNED_UNPAID -> "Unpaid" to Status.Warn
    CardState.ASSIGNED_PAID -> "Paid" to Status.Good
    CardState.RETURNED -> "Card returned" to Status.Neutral
    CardState.RETURNED_UNPAID -> "Returned UNPAID" to Status.Bad
    CardState.LOST -> "Card lost" to Status.Bad
    CardState.AVAILABLE -> "Free" to Status.Neutral
}

@Composable
fun BoardScreen(
    state: ConductorState,
    onFromTo: (from: Boolean, stopId: String) -> Unit,
    onAdd: (String?) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onPaid: (Trip) -> Unit,
    onCalc: (Trip) -> Unit,
    onReturn: (Trip) -> Unit,
    onClearAlarm: (Trip) -> Unit,
    onUseNearest: () -> Unit,
    onCalcPreview: () -> Unit,
) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = from, false = to, null = closed
    val config = state.config
    val nameOf = { id: String? -> if (id == null) "Regular" else config.category(id)?.name ?: id }
    val stopName = { id: String -> config.stop(id)?.name ?: "Choose" }

    picking?.let { fromPicker ->
        StopPicker(
            title = if (fromPicker) "Boarding at" else "Going to",
            stops = config.stops,
            selectedId = if (fromPicker) state.boardingStopId else state.destinationStopId,
            onPick = { onFromTo(fromPicker, it.id); picking = null },
            onCancel = { picking = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
        // Where: two big rows. "To" is the one that changes every trip.
        StopRow(label = "To", value = stopName(state.destinationStopId), onClick = { picking = false })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "From ${stopName(state.boardingStopId)}",
                style = MaterialTheme.typography.bodyMedium,
                color = TomsColors.InkSoft,
                modifier = Modifier.weight(1f).clickable { picking = true }.padding(vertical = 8.dp),
            )
            if (state.nearestStop != null && state.nearestStop.stop.id != state.boardingStopId) {
                QuietButton("Use ${state.nearestStop.stop.name}", onUseNearest, Modifier.heightIn(min = 48.dp))
            }
        }

        // The total, as big as the screen allows. It updates with every passenger button.
        val preview = state.preview
        Column(Modifier.fillMaxWidth().clickable(enabled = preview != null) { onCalcPreview() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (preview == null) "—" else formatPesos(preview.totalCentavos),
                style = MaterialTheme.typography.displayLarge,
                color = TomsColors.Ink,
            )
            Text(
                state.tally.toLines().sortedBy { it.categoryId ?: "" }.joinToString(" · ") { "${it.count} ${nameOf(it.categoryId)}" }.ifEmpty { "No passengers" },
                style = MaterialTheme.typography.titleMedium,
                color = TomsColors.InkSoft,
                textAlign = TextAlign.Center,
            )
        }

        PassengerButtons(state, nameOf, onAdd)

        // Undo and Clear swap sides with the hand setting, so the one used most sits under the thumb.
        val undo = @Composable { QuietButton("Undo", onUndo, Modifier.weight(1f)) }
        val clear = @Composable { QuietButton("Clear", onClear, Modifier.weight(1f)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            if (state.handedness == Handedness.Right) { clear(); undo() } else { undo(); clear() }
        }

        ResultArea(state, nameOf, onPaid, onCalc, onReturn, onClearAlarm, Modifier.weight(1f))
    }
}

@Composable
private fun StopRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).background(TomsColors.Surface, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = TomsColors.InkSoft)
        Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text("▾", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PassengerButtons(state: ConductorState, nameOf: (String?) -> String, onAdd: (String?) -> Unit) {
    val types: List<String?> = listOf(null) + state.config.discountCategories.filter { it.active }.map { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
        types.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                pair.forEach { type ->
                    val count = state.tally.counts[type] ?: 0
                    val on = count > 0
                    Box(
                        Modifier.weight(1f).heightIn(min = TouchTarget)
                            .background(if (on) TomsColors.Primary else TomsColors.Paper, RoundedCornerShape(12.dp))
                            .border(2.dp, TomsColors.Primary, RoundedCornerShape(12.dp))
                            .clickable { onAdd(type) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (on) "${nameOf(type)}  $count" else nameOf(type),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) TomsColors.OnPrimary else TomsColors.Primary,
                        )
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ResultArea(
    state: ConductorState,
    nameOf: (String?) -> String,
    onPaid: (Trip) -> Unit,
    onCalc: (Trip) -> Unit,
    onReturn: (Trip) -> Unit,
    onClearAlarm: (Trip) -> Unit,
    modifier: Modifier,
) {
    when (val r = state.result) {
        null -> Box(
            modifier.fillMaxWidth().heightIn(min = 96.dp).border(3.dp, TomsColors.Line, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("TAP CARD", style = MaterialTheme.typography.headlineMedium, color = TomsColors.InkSoft) }

        is BoardResult.Refused -> Column(
            modifier.fillMaxWidth().background(TomsColors.BadBg, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusChip("Not started", Status.Bad)
            Text(r.message, style = MaterialTheme.typography.titleLarge, color = TomsColors.Bad, textAlign = TextAlign.Center)
        }

        is BoardResult.Started -> TripCard(r.trip, CardState.ASSIGNED_UNPAID, "Started", nameOf, modifier, onPaid, onCalc, onReturn, onClearAlarm)
        is BoardResult.Existing -> TripCard(r.trip, r.state, "Already on a trip", nameOf, modifier, onPaid, onCalc, onReturn, onClearAlarm)
    }
}

@Composable
private fun TripCard(
    trip: Trip,
    state: CardState,
    heading: String,
    nameOf: (String?) -> String,
    modifier: Modifier,
    onPaid: (Trip) -> Unit,
    onCalc: (Trip) -> Unit,
    onReturn: (Trip) -> Unit,
    onClearAlarm: (Trip) -> Unit,
) {
    val (words, status) = stateWords(state)
    val bg = when (status) {
        Status.Good -> TomsColors.GoodBg
        Status.Bad -> TomsColors.BadBg
        Status.Warn -> TomsColors.WarnBg
        Status.Neutral -> TomsColors.Surface
    }
    Column(modifier.fillMaxWidth().background(bg, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusChip(words, status)
            Text(heading, style = MaterialTheme.typography.bodyMedium, color = TomsColors.InkSoft)
        }
        Text("${formatPesos(trip.fareCentavos)}  ·  ${partyLabel(trip, nameOf)}", style = MaterialTheme.typography.titleLarge, color = TomsColors.Ink)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            when (state) {
                CardState.ASSIGNED_UNPAID -> {
                    BigButton("Paid", { onPaid(trip) }, Modifier.weight(1f))
                    QuietButton("Calc", { onCalc(trip) }, Modifier.weight(1f))
                }
                CardState.ASSIGNED_PAID -> BigButton("Return card", { onReturn(trip) }, Modifier.weight(1f))
                CardState.RETURNED_UNPAID -> BigButton("Clear alarm", { onClearAlarm(trip) }, Modifier.weight(1f))
                else -> Unit
            }
        }
    }
}

@Composable
fun StopPicker(title: String, stops: List<Stop>, selectedId: String, onPick: (Stop) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stops, key = { it.id }) { stop ->
                val chosen = stop.id == selectedId
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                        .background(if (chosen) TomsColors.Primary else TomsColors.Surface, RoundedCornerShape(12.dp))
                        .clickable { onPick(stop) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stop.name, style = MaterialTheme.typography.titleLarge, color = if (chosen) TomsColors.OnPrimary else TomsColors.Ink)
                }
            }
        }
        QuietButton("Cancel", onCancel, Modifier.fillMaxWidth())
    }
}
