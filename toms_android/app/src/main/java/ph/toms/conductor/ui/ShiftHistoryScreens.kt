package ph.toms.conductor.ui

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Date
import kotlinx.coroutines.launch
import ph.toms.conductor.domain.ShiftSummary
import ph.toms.conductor.domain.ShiftTime
import ph.toms.conductor.domain.formatCentavos
import ph.toms.conductor.ui.theme.BigButton
import ph.toms.conductor.ui.theme.Gap
import ph.toms.conductor.ui.theme.QuietButton
import ph.toms.conductor.ui.theme.Status
import ph.toms.conductor.ui.theme.StatusChip
import ph.toms.conductor.ui.theme.TomsColors

private fun peso(centavos: Long) = "₱" + formatCentavos(centavos)

private fun whenIt(millis: Long): String = DateFormat.format("d MMM yyyy, HH:mm", Date(millis)).toString()

/** Where the fare stands, from the frozen summary. A trip in none of its lists was collected. */
private fun settlementOf(summary: ShiftSummary?, tripId: String): String = when {
    summary == null -> "?"
    tripId in summary.writtenOffTripIds -> "written off"
    tripId in summary.outstandingTripIds -> "outstanding"
    tripId in summary.unknownTripIds -> "not accounted for"
    else -> "collected"
}

/** Hands the export to the system share sheet. The JSON goes as text, so every target accepts it. */
private fun shareJson(context: Context, export: ShiftExport) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TITLE, export.fileName)
        .putExtra(Intent.EXTRA_SUBJECT, export.fileName)
        .putExtra(Intent.EXTRA_TEXT, export.json)
    context.startActivity(Intent.createChooser(send, "Share shift record"))
}

/** Shift history: the ended shifts, newest first. Read-only except for recording a late payment. */
@Composable
fun ShiftHistoryScreen(vm: ShiftHistoryViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { vm.close() }

    val detail = state.detail
    if (detail != null) {
        BackHandler { vm.closeDetail() }
        ShiftDetailContent(
            detail = detail,
            message = state.message,
            recovering = state.recovering,
            onBack = vm::closeDetail,
            onShare = { scope.launch { vm.exportJson()?.let { shareJson(context, it) } } },
            onRecover = vm::beginRecovery,
            onCancelRecovery = vm::cancelRecovery,
            onConfirmRecovery = vm::confirmRecovery,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
        Text("Shift history", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The shifts that have ended, with the figures frozen when each one ended.",
            style = MaterialTheme.typography.bodyMedium,
            color = TomsColors.InkSoft,
        )
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TomsColors.Warn) }
        if (state.rows.isEmpty() && !state.loading) {
            Text("No ended shifts yet.", style = MaterialTheme.typography.titleMedium, color = TomsColors.InkSoft)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gap)) {
                items(state.rows, key = { it.shift.id }) { row ->
                    val summary = row.summary
                    Column(
                        Modifier.fillMaxWidth()
                            .background(TomsColors.Surface, RoundedCornerShape(12.dp))
                            .clickable { vm.openDetail(row.shift.id) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(row.shift.conductor, style = MaterialTheme.typography.titleMedium)
                            if (summary != null) {
                                Text(peso(summary.collectedCashCentavos), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Text(
                            "${whenIt(row.shift.startedAtMillis)}  ·  ${ShiftTime.formatDuration(row.shift.startedAtMillis, row.shift.endedAtMillis ?: row.shift.startedAtMillis)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TomsColors.InkSoft,
                        )
                        if (summary != null) {
                            val parts = buildList {
                                add("${summary.tripCount} trips")
                                if (summary.writtenOffCentavos > 0) add("written off ${peso(summary.writtenOffCentavos)}")
                                if (summary.outstandingCentavos > 0) add("outstanding ${peso(summary.outstandingCentavos)}")
                                if (summary.unknownCentavos > 0) add("not accounted for ${peso(summary.unknownCentavos)}")
                            }
                            Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = TomsColors.InkSoft)
                        }
                    }
                }
            }
        }
        QuietButton("Back", vm::close, Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}

/** One ended shift: its frozen figures, its trips, its written-off trips, and the export. */
@Composable
private fun ShiftDetailContent(
    detail: ShiftDetail,
    message: String?,
    recovering: RecoveryDraft?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onRecover: (ph.toms.conductor.data.WrittenOffTrip) -> Unit,
    onCancelRecovery: () -> Unit,
    onConfirmRecovery: (String) -> Unit,
) {
    val summary = detail.summary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
        Text("Ended shift", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${detail.shift.conductor}  ·  vehicle ${detail.shift.vehicleId}  ·  route ${detail.shift.routeId}",
            style = MaterialTheme.typography.bodyMedium,
            color = TomsColors.InkSoft,
        )
        Text(
            "${whenIt(detail.shift.startedAtMillis)} to ${whenIt(detail.shift.endedAtMillis ?: detail.shift.startedAtMillis)}" +
                (detail.shift.endedBy?.let { "  ·  ended by $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = TomsColors.InkSoft,
        )

        if (summary != null) {
            Line("Passengers", summary.passengers.toString())
            Line("Trips", summary.tripCount.toString())
            Line("Fares charged", peso(summary.faresChargedCentavos))
            Line("Collected", peso(summary.collectedCashCentavos))
            if (summary.writtenOffCentavos > 0) Line("Written off", peso(summary.writtenOffCentavos), TomsColors.Bad)
            if (summary.outstandingCentavos > 0) Line("Still to collect", peso(summary.outstandingCentavos), TomsColors.Bad)
            if (summary.unknownCentavos > 0) Line("Not accounted for", peso(summary.unknownCentavos), TomsColors.Bad)
        } else {
            Text(
                "This shift has no frozen summary; it predates it. Its trips are listed below.",
                style = MaterialTheme.typography.bodyMedium,
                color = TomsColors.Warn,
            )
        }

        if (detail.writtenOff.isNotEmpty()) {
            Text("Written off in this shift", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            val recovered = detail.recoveredTripIds
            detail.writtenOff.forEach { w ->
                Column(
                    Modifier.fillMaxWidth().background(TomsColors.Surface, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(peso(w.amountCentavos.toLong()), style = MaterialTheme.typography.titleMedium)
                        if (w.trip.id in recovered) StatusChip("Recovered", Status.Good) else StatusChip("Written off", Status.Bad)
                    }
                    Text(w.reason, style = MaterialTheme.typography.bodyMedium, color = TomsColors.InkSoft)
                    val paid = detail.recoveries.firstOrNull { it.tripId == w.trip.id }
                    if (paid != null) {
                        Text(
                            "Payment recorded ${whenIt(paid.atMillis)} by ${paid.actingConductor}: ${paid.reason}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TomsColors.Good,
                        )
                    } else {
                        QuietButton("Record payment", { onRecover(w) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        if (detail.trips.isNotEmpty()) {
            Text("Trips", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            detail.trips.forEach { t ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(whenIt(t.createdAtMillis), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(peso(t.fareCentavos.toLong()), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        settlementOf(summary, t.id),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (settlementOf(summary, t.id) == "collected") TomsColors.Good else TomsColors.Bad,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TomsColors.Warn) }

        if (summary != null) BigButton("Share as JSON", onShare, Modifier.padding(top = 8.dp))
        QuietButton("Back", onBack, Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }

    recovering?.let { draft ->
        ConfirmRecoveryDialog(
            draft = draft,
            onConfirm = onConfirmRecovery,
            onCancel = onCancelRecovery,
        )
    }
}

/** Asks for the reason before a late payment is recorded. The payment is attributed to [RecoveryDraft.actor]. */
@Composable
private fun ConfirmRecoveryDialog(draft: RecoveryDraft, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Record late payment?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The fare of this trip was written off. Record that the money was paid late. The trip " +
                        "and the frozen summary stay as they are; this is an audit record only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${peso(draft.target.amountCentavos.toLong())}  ·  recorded as ${draft.actor}",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (who paid, where the money is)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank(), onClick = { onConfirm(reason) }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Line(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = TomsColors.Ink) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TomsColors.InkSoft, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, textAlign = TextAlign.End)
    }
}
