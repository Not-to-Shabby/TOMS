package ph.toms.conductor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ph.toms.conductor.domain.ShiftSummary
import ph.toms.conductor.domain.ShiftTime
import ph.toms.conductor.domain.TomsConfig
import ph.toms.conductor.domain.formatCentavos
import ph.toms.conductor.ui.theme.BigButton
import ph.toms.conductor.ui.theme.Gap
import ph.toms.conductor.ui.theme.QuietButton
import ph.toms.conductor.ui.theme.TomsColors

private fun peso(centavos: Long) = "₱" + formatCentavos(centavos)

private fun typeName(config: TomsConfig, categoryId: String?) =
    if (categoryId == null) "Regular" else config.category(categoryId)?.name ?: categoryId

/**
 * What the conductor sees when asked to end the shift. Unpaid trips are asked about once:
 * go back and collect, or record them as uncollected. Nothing is changed until a button is pressed.
 */
@Composable
fun EndShiftDialog(
    prompt: EndShiftPrompt,
    forPreviousShift: Boolean,
    busy: Boolean,
    onGoBack: () -> Unit,
    onWriteOff: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    val hasUnpaid = prompt.unpaidCount > 0
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = {
            Text(
                when {
                    forPreviousShift -> "Earlier shift still open"
                    hasUnpaid -> "End shift?"
                    else -> "End shift"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (forPreviousShift) {
                    Text("${prompt.shiftConductor}'s shift has to be ended before a new one can start.", style = MaterialTheme.typography.bodyLarge)
                }
                if (hasUnpaid) {
                    Text(
                        "${prompt.unpaidCount} ${if (prompt.unpaidCount == 1) "trip" else "trips"} not paid: ${peso(prompt.unpaidCentavos)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = TomsColors.Bad,
                    )
                } else {
                    Text("Every trip is paid.", style = MaterialTheme.typography.bodyLarge)
                }
                if (prompt.paidOutCount > 0) {
                    Text(
                        "${prompt.paidOutCount} paid ${if (prompt.paidOutCount == 1) "card is" else "cards are"} still out. They stay open for the next shift.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TomsColors.InkSoft,
                    )
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                if (hasUnpaid) {
                    BigButton("Go back and collect", onGoBack, enabled = !busy)
                    QuietButton("Record ${peso(prompt.unpaidCentavos)} uncollected", onWriteOff, Modifier.fillMaxWidth(), enabled = !busy)
                } else {
                    BigButton("End shift", onEnd, enabled = !busy)
                    QuietButton("Cancel", onCancel, Modifier.fillMaxWidth(), enabled = !busy)
                }
            }
        },
    )
}

/** Shown once after a shift is ended: the figures the conductor hands over. */
@Composable
fun ShiftEndedDialog(summary: ShiftSummary, config: TomsConfig, continues: Boolean, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text("Shift ended") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Collected", style = MaterialTheme.typography.bodyLarge, color = TomsColors.InkSoft)
                Text(peso(summary.collectedCashCentavos), style = MaterialTheme.typography.displaySmall)
                if (summary.writtenOffCentavos > 0) {
                    Text("Written off ${peso(summary.writtenOffCentavos)}", style = MaterialTheme.typography.titleMedium, color = TomsColors.Bad)
                }
                Text(
                    "${summary.passengers} ${if (summary.passengers == 1) "passenger" else "passengers"} on ${summary.tripCount} ${if (summary.tripCount == 1) "trip" else "trips"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (continues) {
                    Text("Your new shift starts next.", style = MaterialTheme.typography.bodyMedium, color = TomsColors.InkSoft)
                }
            }
        },
        confirmButton = { BigButton("OK", onOk, Modifier.fillMaxWidth()) },
    )
}

/** The running shift: cash first, then what it is made of. One screen, no tabs, scrolls if it has to. */
@Composable
fun ShiftSummaryScreen(
    summary: ShiftSummary,
    config: TomsConfig,
    conductor: String,
    startedAtMillis: Long,
    nowMillis: Long,
    onEndShift: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Text("Shift summary", style = MaterialTheme.typography.headlineMedium)
        Text(
            "$conductor  ·  ${ShiftTime.formatDuration(startedAtMillis, nowMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            color = TomsColors.InkSoft,
        )

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Collected this shift", style = MaterialTheme.typography.titleMedium, color = TomsColors.InkSoft)
            Text(peso(summary.collectedCashCentavos), style = MaterialTheme.typography.displayLarge)
        }

        Line("Passengers", summary.passengers.toString())
        Line("Trips", summary.tripCount.toString())
        Line("Fares charged", peso(summary.faresChargedCentavos))

        if (summary.outstandingCentavos > 0) Line("Still to collect", peso(summary.outstandingCentavos), TomsColors.Bad)
        if (summary.writtenOffCentavos > 0) Line("Written off", peso(summary.writtenOffCentavos), TomsColors.Bad)
        if (summary.unknownCentavos > 0) Line("Not accounted for", peso(summary.unknownCentavos), TomsColors.Bad)
        if (summary.discountCentavos > 0) Line("Discounts given", peso(summary.discountCentavos))
        if (summary.overrideCount > 0) {
            Line("Fares changed by hand", "${summary.overrideCount}  (${if (summary.overrideNetCentavos < 0) "-" else "+"}${peso(kotlin.math.abs(summary.overrideNetCentavos))})")
        }

        if (summary.collectedFromCarriedInCentavos > 0) {
            Line("Of this, from the earlier shift", peso(summary.collectedFromCarriedInCentavos))
        }
        if (summary.carriedIn.stillUnpaidCount > 0) {
            Text(
                "Earlier shift, still unpaid: ${summary.carriedIn.stillUnpaidCount} ${if (summary.carriedIn.stillUnpaidCount == 1) "trip" else "trips"}, ${peso(summary.carriedIn.stillUnpaidCentavos)}. Not counted above.",
                style = MaterialTheme.typography.bodyMedium,
                color = TomsColors.InkSoft,
            )
        }

        if (summary.byType.isNotEmpty()) {
            Text("By passenger type", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            summary.byType.forEach { t ->
                Line("${typeName(config, t.categoryId)}  ×${t.passengers}", peso(t.fareCentavos))
            }
        }

        BigButton("End shift", onEndShift, Modifier.padding(top = 8.dp))
        QuietButton("Back", onBack, Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}

@Composable
private fun Line(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = TomsColors.Ink) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TomsColors.InkSoft, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, textAlign = TextAlign.End)
    }
}
