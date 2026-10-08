package ph.toms.conductor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import ph.toms.conductor.domain.BILL_PESOS
import ph.toms.conductor.domain.CalcMode
import ph.toms.conductor.domain.CalcOp
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.Change
import ph.toms.conductor.domain.ChangeResult
import ph.toms.conductor.domain.CollectList
import ph.toms.conductor.domain.GeneralCalc
import ph.toms.conductor.domain.Keypad
import ph.toms.conductor.domain.OpenTrip
import ph.toms.conductor.domain.Trip
import ph.toms.conductor.domain.formatCentavos
import ph.toms.conductor.domain.formatPesos
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.ui.theme.BigButton
import ph.toms.conductor.ui.theme.Gap
import ph.toms.conductor.ui.theme.QuietButton
import ph.toms.conductor.ui.theme.Status
import ph.toms.conductor.ui.theme.StatusChip
import ph.toms.conductor.ui.theme.TomsColors
import ph.toms.conductor.ui.theme.TouchTarget

@Composable
fun CollectScreen(
    state: ConductorState,
    onPaid: (Trip) -> Unit,
    onCalc: (Trip) -> Unit,
    onReturn: (Trip) -> Unit,
    onClearAlarm: (Trip) -> Unit,
) {
    val nameOf = { id: String? -> if (id == null) "Regular" else state.config.category(id)?.name ?: id }
    val owed = CollectList.owedCentavos(state.openTrips)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("To collect", style = MaterialTheme.typography.headlineMedium)
            Text(formatPesos(owed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), style = MaterialTheme.typography.headlineMedium)
        }
        state.notice?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = TomsColors.Warn) }
        if (state.openTrips.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Nobody to collect from", style = MaterialTheme.typography.titleLarge, color = TomsColors.InkSoft)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gap)) {
                items(state.openTrips, key = { it.trip.id }) { open ->
                    CollectRow(open, focused = open.trip.id == state.focusTripId, nameOf, onPaid, onCalc, onReturn, onClearAlarm)
                }
            }
        }
    }
}

@Composable
private fun CollectRow(
    open: OpenTrip,
    focused: Boolean,
    nameOf: (String?) -> String,
    onPaid: (Trip) -> Unit,
    onCalc: (Trip) -> Unit,
    onReturn: (Trip) -> Unit,
    onClearAlarm: (Trip) -> Unit,
) {
    val (words, status) = stateWords(open.state)
    Column(
        Modifier.fillMaxWidth()
            .background(TomsColors.Surface, RoundedCornerShape(12.dp))
            .border(if (focused) 4.dp else 0.dp, if (focused) TomsColors.Primary else TomsColors.Surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatPesos(open.trip.fareCentavos), style = MaterialTheme.typography.headlineMedium)
            StatusChip(words, status)
        }
        Text(partyLabel(open.trip, nameOf), style = MaterialTheme.typography.titleMedium, color = TomsColors.InkSoft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            when (open.state) {
                CardState.ASSIGNED_UNPAID -> {
                    BigButton("Paid", { onPaid(open.trip) }, Modifier.weight(1f))
                    QuietButton("Calc", { onCalc(open.trip) }, Modifier.weight(1f))
                }
                CardState.ASSIGNED_PAID -> BigButton("Card returned", { onReturn(open.trip) }, Modifier.weight(1f))
                CardState.RETURNED_UNPAID -> BigButton("Clear alarm", { onClearAlarm(open.trip) }, Modifier.weight(1f))
                else -> Unit
            }
        }
    }
}

@Composable
fun CalcScreen(
    state: ConductorState,
    onMode: (CalcMode) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            ModeButton("Change", state.calcMode == CalcMode.CHANGE) { onMode(CalcMode.CHANGE) }
            ModeButton("Calculator", state.calcMode == CalcMode.GENERAL) { onMode(CalcMode.GENERAL) }
        }
        when (state.calcMode) {
            CalcMode.CHANGE -> ChangePanel(dueCentavos = state.calcDueCentavos, handedness = state.handedness)
            CalcMode.GENERAL -> GeneralPanel()
        }
    }
}

@Composable
private fun RowScope.ModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).heightIn(min = TouchTarget)
            .background(if (selected) TomsColors.Primary else TomsColors.Surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) TomsColors.OnPrimary else TomsColors.Ink) }
}

@Composable
private fun ChangePanel(dueCentavos: Int, handedness: Handedness) {
    var keypad by remember(dueCentavos) { mutableStateOf<Keypad?>(null) }
    var customDue by remember(dueCentavos) { mutableStateOf(Keypad()) }
    var givenCentavos by remember(dueCentavos) { mutableStateOf<Int?>(null) }

    val due = if (dueCentavos > 0) dueCentavos else customDue.centavos()
    val result = givenCentavos?.let { Change.of(due, it) }

    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
        if (dueCentavos > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fare", style = MaterialTheme.typography.titleLarge, color = TomsColors.InkSoft)
                Text(formatPesos(dueCentavos), style = MaterialTheme.typography.headlineMedium)
            }
        } else {
            Text("Fare to charge", style = MaterialTheme.typography.titleMedium, color = TomsColors.InkSoft)
            Text("₱" + customDue.text, style = MaterialTheme.typography.headlineMedium)
            Keys(onDigit = { customDue = customDue.digit(it) }, onPoint = { customDue = customDue.point() }, onBack = { customDue = customDue.backspace() }, handedness = handedness)
        }

        if (due > 0) {
            Text("Given", style = MaterialTheme.typography.titleMedium, color = TomsColors.InkSoft)
            val bills = BILL_PESOS.filter { it * 100 >= due }
            bills.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    row.forEach { bill ->
                        val picked = givenCentavos == bill * 100
                        Box(
                            Modifier.weight(1f).heightIn(min = TouchTarget)
                                .background(if (picked) TomsColors.Primary else TomsColors.Paper, RoundedCornerShape(12.dp))
                                .border(2.dp, TomsColors.Primary, RoundedCornerShape(12.dp))
                                .clickable { givenCentavos = bill * 100; keypad = null },
                            contentAlignment = Alignment.Center,
                        ) { Text("₱$bill", style = MaterialTheme.typography.labelLarge, color = if (picked) TomsColors.OnPrimary else TomsColors.Primary) }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            val typed = keypad
            QuietButton(
                if (typed == null) "Other amount" else "₱" + typed.text,
                { if (typed == null) keypad = Keypad() },
                Modifier.fillMaxWidth(),
            )
            if (typed != null) {
                Keys(
                    onDigit = { keypad = typed.digit(it); givenCentavos = keypad!!.centavos() },
                    onPoint = { keypad = typed.point() },
                    onBack = { keypad = typed.backspace(); givenCentavos = keypad!!.centavos() },
                    handedness = handedness,
                )
            }
        }

        ChangeAnswer(result)
    }
}

@Composable
private fun ChangeAnswer(result: ChangeResult?) {
    val (bg, line, big) = when (result) {
        null -> Triple(TomsColors.Surface, "Pick what the passenger gave", "")
        ChangeResult.Exact -> Triple(TomsColors.GoodBg, "Exact. No change.", "")
        is ChangeResult.Give -> Triple(TomsColors.GoodBg, "Give change", formatPesos(result.centavos))
        is ChangeResult.Short -> Triple(TomsColors.BadBg, "Still short", formatPesos(result.centavos))
    }
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(line, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (big.isNotEmpty()) Text(big, style = MaterialTheme.typography.displayLarge)
    }
}

@Composable
private fun GeneralPanel() {
    var calc by remember { mutableStateOf(GeneralCalc()) }
    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
        Text(
            calc.display(),
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.fillMaxWidth().background(TomsColors.Surface, RoundedCornerShape(12.dp)).padding(16.dp),
            textAlign = TextAlign.End,
        )
        val rows = listOf(
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "−"),
            listOf("C", "0", ".", "+"),
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                row.forEach { key ->
                    QuietButton(key, {
                        calc = when (key) {
                            "÷" -> calc.operate(CalcOp.DIVIDE)
                            "×" -> calc.operate(CalcOp.MULTIPLY)
                            "−" -> calc.operate(CalcOp.SUBTRACT)
                            "+" -> calc.operate(CalcOp.ADD)
                            "C" -> calc.clear()
                            "." -> calc.point()
                            else -> calc.digit(key.toInt())
                        }
                    }, Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            QuietButton("⌫", { calc = calc.backspace() }, Modifier.weight(1f))
            BigButton("=", { calc = calc.equals() }, Modifier.weight(3f))
        }
    }
}

/** A 3-wide digit pad. The bottom row mirrors for a left-handed conductor so "." and backspace stay under the thumb. */
@Composable
private fun Keys(onDigit: (Int) -> Unit, onPoint: () -> Unit, onBack: () -> Unit, handedness: Handedness) {
    val digits = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
        digits.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                row.forEach { d -> QuietButton(d.toString(), { onDigit(d) }, Modifier.weight(1f)) }
            }
        }
        val point = @Composable { QuietButton(".", onPoint, Modifier.weight(1f)) }
        val zero = @Composable { QuietButton("0", { onDigit(0) }, Modifier.weight(1f)) }
        val back = @Composable { QuietButton("⌫", onBack, Modifier.weight(1f)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            if (handedness == Handedness.Right) { point(); zero(); back() } else { back(); zero(); point() }
        }
    }
}
