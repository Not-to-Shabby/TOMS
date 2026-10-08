package ph.toms.conductor.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Date
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.ui.theme.BigButton
import ph.toms.conductor.ui.theme.Gap
import ph.toms.conductor.ui.theme.QuietButton
import ph.toms.conductor.ui.theme.Status
import ph.toms.conductor.ui.theme.StatusChip
import ph.toms.conductor.ui.theme.TomsColors
import ph.toms.conductor.ui.theme.TouchTarget

@Composable
fun MoreScreen(
    state: ConductorState,
    onHand: (Handedness) -> Unit,
    onServerUrl: (String) -> Unit = {},
    onDeviceToken: (String?) -> Unit = {},
    onUploadNow: () -> Unit = {},
) {
    var editUrl by remember { mutableStateOf<String?>(null) }
    var editToken by remember { mutableStateOf<String?>(null) }

    editUrl?.let { current ->
        var input by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editUrl = null },
            title = { Text("Server URL") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("e.g. http://127.0.0.1:3000") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onServerUrl(input); editUrl = null }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editUrl = null }) { Text("Cancel") }
            },
        )
    }

    editToken?.let { current ->
        var input by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editToken = null },
            title = { Text("Device Token") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste <device_id>.<secret> from dashboard enrollment.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Device token") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onDeviceToken(input.trim().ifEmpty { null }); editToken = null }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editToken = null }) { Text("Cancel") }
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Which hand do you use?", style = MaterialTheme.typography.titleMedium, color = TomsColors.InkSoft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            HandChoice("Left", state.handedness == Handedness.Left) { onHand(Handedness.Left) }
            HandChoice("Right", state.handedness == Handedness.Right) { onHand(Handedness.Right) }
        }
        Text(
            "Every button is full width, so both hands reach everything. This only swaps Undo and Clear and the keypad corners.",
            style = MaterialTheme.typography.bodyMedium,
            color = TomsColors.InkSoft,
        )

        Text("Server Sync", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        Fact("Device ID", state.deviceId.ifEmpty { "loading..." })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Server URL", style = MaterialTheme.typography.bodyLarge, color = TomsColors.InkSoft)
                Text(state.serverUrl.ifEmpty { "Not set" }, style = MaterialTheme.typography.bodyMedium)
            }
            QuietButton("Change", { editUrl = state.serverUrl })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enrollment", style = MaterialTheme.typography.bodyLarge, color = TomsColors.InkSoft)
                StatusChip(if (state.isEnrolled) "Enrolled" else "Not enrolled", if (state.isEnrolled) Status.Good else Status.Warn)
            }
            QuietButton("Set Token", { editToken = "" })
        }

        BigButton(
            text = if (state.isUploading) "Uploading..." else "Upload now (${state.pendingCount} pending)",
            onClick = onUploadNow,
            enabled = !state.isUploading && state.isEnrolled,
        )
        state.uploadMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Uploaded")) TomsColors.Good else TomsColors.Bad)
        }

        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        Text("For whoever is fixing the app, not for the conductor.", style = MaterialTheme.typography.bodyMedium, color = TomsColors.InkSoft)

        Fact("Waiting to upload", "${state.pendingCount} events")
        Fact("Fare version", state.config.fare.version.toString())
        Fact("Approved cards", state.config.approvedCards.size.toString())
        Fact("Trips open", state.openTrips.size.toString())
        val fix = state.fix
        Fact(
            "GPS",
            if (fix == null) "no fix yet" else "%.5f, %.5f  ±%s m".format(fix.lat, fix.lon, fix.accuracyMeters?.let { "%.0f".format(it) } ?: "?"),
        )
        Fact("Nearest stop", state.nearestStop?.let { "${it.stop.name} (%.0f m)".format(it.distanceMeters) } ?: "none within range")

        Text("Last card reads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (state.recentReads.isEmpty()) {
            Text("None yet", style = MaterialTheme.typography.bodyMedium, color = TomsColors.InkSoft)
        } else {
            state.recentReads.take(10).forEach { r ->
                Text(
                    "${r.uidHex}   ${DateFormat.format("HH:mm:ss", Date(r.readAtMillis))}   ${r.techList.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            "The fares, stops and card list are placeholders until the real ones are loaded from the server.",
            style = MaterialTheme.typography.bodyMedium,
            color = TomsColors.Warn,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun RowScope.HandChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).heightIn(min = TouchTarget)
            .background(if (selected) TomsColors.Primary else TomsColors.Surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) TomsColors.OnPrimary else TomsColors.Ink) }
}

@Composable
private fun Fact(label: String, value: String) {
    // The value takes the rest of the line and may wrap, so a long value (the device id) never touches the label.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TomsColors.InkSoft)
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
