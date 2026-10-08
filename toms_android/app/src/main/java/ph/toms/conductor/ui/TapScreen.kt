package ph.toms.conductor.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.awaitCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.StopMatcher
import ph.toms.conductor.nfc.CardRead
import ph.toms.conductor.nfc.NfcAvailability
import ph.toms.conductor.nfc.NfcReader

private fun pesos(centavos: Int) = "P%d.%02d".format(centavos / 100, centavos % 100)

@Composable
fun TapScreen(
    header: String,
    state: SessionUiState,
    nfcReader: NfcReader,
    onRead: (CardRead) -> Unit,
    onBoarding: (String) -> Unit,
    onDestination: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onPaid: (String) -> Unit,
    onReturn: (String) -> Unit,
    onResolve: (String) -> Unit,
    onRelease: (String) -> Unit,
    onClear: () -> Unit,
    onStartLocation: () -> Unit,
    onStopLocation: () -> Unit,
    onUseNearest: () -> Unit,
) {
    val activity = LocalContext.current as Activity
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartLocation()
    }
    val lifecycleForGps = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleForGps) {
        lifecycleForGps.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) onStartLocation() else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            try { awaitCancellation() } finally { onStopLocation() }
        }
    }
    val availability = remember { nfcReader.availability(activity) }
    val lifecycleOwner = LocalLifecycleOwner.current
    if (availability == NfcAvailability.Ready) {
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                nfcReader.reads(activity).collect(onRead)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(header, style = MaterialTheme.typography.titleSmall)
        when (availability) {
            NfcAvailability.Unsupported -> Text("This phone has no NFC.", color = MaterialTheme.colorScheme.error)
            NfcAvailability.Disabled -> Text("NFC is switched off.", color = MaterialTheme.colorScheme.error)
            NfcAvailability.Ready -> Text("Pick stops and passenger type, then tap a card.")
        }

        GpsLine(state, onUseNearest)
        ChipRow("Boarding", state.config.stops.map { it.id to it.name }, state.boardingStopId, onBoarding)
        ChipRow("Destination", state.config.stops.map { it.id to it.name }, state.destinationStopId, onDestination)
        ChipRow(
            "Passenger",
            listOf("" to "Regular") + state.config.discountCategories.filter { it.active }.map { it.id to it.name },
            state.categoryId ?: "",
        ) { onCategory(it.ifEmpty { null }) }

        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trips: ${state.trips.size}  Pending upload: ${state.pendingCount}")
            OutlinedButton(onClear) { Text("Clear reads") }
        }

        val latestTripIds = remember(state.trips) { state.trips.distinctBy { it.trip.nfcUid }.map { it.trip.id }.toSet() }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.trips, key = { it.trip.id }) { row ->
                val t = row.trip
                val isLatest = t.id in latestTripIds
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${pesos(t.fareCentavos)}  |  ${if (isLatest) row.cardState.name else "CLOSED"}", style = MaterialTheme.typography.titleMedium)
                        Text("UID ${t.nfcUid}")
                        Text("card ${t.cardUuid.takeLast(12)}  fare v${t.fareVersion}")
                        Text("${t.boardingStopId} -> ${t.declaredDestinationStopId}  ${t.discountCategoryId ?: "regular"}  discount ${pesos(t.discountCentavos)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (if (isLatest) row.cardState else CardState.AVAILABLE) {
                                CardState.ASSIGNED_UNPAID -> {
                                    OutlinedButton({ onPaid(t.id) }) { Text("Paid") }
                                    OutlinedButton({ onReturn(t.id) }) { Text("Return") }
                                }
                                CardState.ASSIGNED_PAID -> OutlinedButton({ onReturn(t.id) }) { Text("Return") }
                                CardState.RETURNED -> OutlinedButton({ onRelease(t.id) }) { Text("Release") }
                                CardState.RETURNED_UNPAID -> OutlinedButton({ onResolve(t.id) }) { Text("Clear alarm") }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GpsLine(state: SessionUiState, onUseNearest: () -> Unit) {
    val fix = state.fix
    val text = when {
        !state.gpsEnabled -> "GPS is off. Turn on location."
        fix == null -> "GPS: waiting for a fix..."
        else -> {
            val age = ((System.currentTimeMillis() - fix.fixAtMillis) / 1000).coerceAtLeast(0)
            val acc = fix.accuracyMeters?.let { "±%.0f m".format(it) } ?: "accuracy unknown"
            val near = state.nearestStop?.let { "near ${it.stop.name} (%.0f m)".format(it.distanceMeters) } ?: "no stop nearby"
            if (age * 1000 > StopMatcher.DEFAULT_MAX_AGE_MILLIS) {
                "GPS STALE: last fix %ds ago, waiting for a new one (not used for trips)".format(age)
            } else {
                "GPS %.5f, %.5f  %s  %ds old  |  %s".format(fix.lat, fix.lon, acc, age, near)
            }
        }
    }
    Column {
        Text(text, style = MaterialTheme.typography.bodySmall)
        if (state.nearestStop != null) OutlinedButton(onUseNearest) { Text("Board at nearest stop") }
    }
}

@Composable
private fun ChipRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, name) ->
                FilterChip(selected == id, { onSelect(id) }, label = { Text(name) })
            }
        }
    }
}
