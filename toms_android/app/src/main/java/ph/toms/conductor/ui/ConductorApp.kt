package ph.toms.conductor.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import ph.toms.conductor.domain.CalcMode
import ph.toms.conductor.domain.StopMatcher
import ph.toms.conductor.nfc.NfcAvailability
import ph.toms.conductor.nfc.NfcReader
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.ui.theme.Status
import ph.toms.conductor.ui.theme.StatusChip
import ph.toms.conductor.ui.theme.TomsColors
import ph.toms.conductor.ui.theme.TouchTarget

@Composable
fun ConductorApp(vm: ConductorViewModel, nfcReader: NfcReader, header: String, state: ConductorState) {
    val activity = LocalContext.current as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val availability = remember { nfcReader.availability(activity) }

    // Read cards only while the screen is in use: reader mode is switched off in the background.
    if (availability == NfcAvailability.Ready) {
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                nfcReader.reads(activity).collect(vm::onCardRead)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startLocation()
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.startLocation() else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            try { awaitCancellation() } finally { vm.stopLocation() }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        StatusStrip(header, availability, state)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when (state.tab) {
                Tab.Board -> BoardScreen(
                    state = state,
                    onFromTo = { from, id -> if (from) vm.selectBoarding(id) else vm.selectDestination(id) },
                    onAdd = vm::addPassenger,
                    onUndo = vm::undoPassenger,
                    onClear = vm::clearPassengers,
                    onPaid = { vm.markPaid(it.id) },
                    onCalc = vm::openCalcFor,
                    onReturn = { vm.returnCard(it.id) },
                    onClearAlarm = { vm.clearAlarm(it.id) },
                    onUseNearest = vm::useNearestAsBoarding,
                    onCalcPreview = vm::openCalcForBoard,
                )
                Tab.Collect -> CollectScreen(
                    state = state,
                    onPaid = { vm.markPaid(it.id) },
                    onCalc = vm::openCalcFor,
                    onReturn = { vm.returnCard(it.id) },
                    onClearAlarm = { vm.clearAlarm(it.id) },
                )
                Tab.Calc -> CalcScreen(state = state, onMode = vm::setCalcMode)
                Tab.More -> MoreScreen(state = state, onHand = vm::setHandedness)
            }
        }
        TabBar(state, vm::selectTab)
    }
}

/** One line, dots and words, so a glance is enough: which bus, is the card reader on, is GPS good, what is unsent. */
@Composable
private fun StatusStrip(header: String, nfc: NfcAvailability, state: ConductorState) {
    val gps: Pair<String, Status> = run {
        val fix = state.fix
        when {
            fix == null -> "GPS searching" to Status.Warn
            StopMatcher.isStale(fix, System.currentTimeMillis()) -> "GPS old" to Status.Warn
            else -> "GPS ok" to Status.Good
        }
    }
    val reader: Pair<String, Status> = when (nfc) {
        NfcAvailability.Ready -> "Reader ready" to Status.Good
        NfcAvailability.Disabled -> "NFC is off" to Status.Bad
        NfcAvailability.Unsupported -> "No NFC" to Status.Bad
    }
    Column(Modifier.fillMaxWidth().background(TomsColors.Surface).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(header, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(reader.first, reader.second)
            StatusChip(gps.first, gps.second)
            if (state.pendingCount > 0) StatusChip("${state.pendingCount} unsent", Status.Neutral)
        }
    }
}

@Composable
private fun TabBar(state: ConductorState, onSelect: (Tab) -> Unit) {
    val unpaid = state.openTrips.count { it.state != ph.toms.conductor.domain.CardState.ASSIGNED_PAID }
    // The tab order mirrors for a left-handed conductor, so the Board tab stays under the thumb.
    val tabs = Tab.entries.let { if (state.handedness == Handedness.Left) it.reversed() else it }
    Row(Modifier.fillMaxWidth().background(TomsColors.Surface)) {
        tabs.forEach { tab ->
            val selected = tab == state.tab
            val label = when (tab) {
                Tab.Board -> "Board"
                Tab.Collect -> if (unpaid > 0) "Collect ($unpaid)" else "Collect"
                Tab.Calc -> "Calc"
                Tab.More -> "More"
            }
            Box(
                Modifier.weight(1f).heightIn(min = TouchTarget)
                    .background(if (selected) TomsColors.Primary else TomsColors.Surface)
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) TomsColors.OnPrimary else TomsColors.Ink)
            }
        }
    }
}
