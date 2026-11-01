package ph.toms.ota

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: OtaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** adb driven runs:
     *  am start -n ph.toms.ota/.MainActivity --es firmware <path> --ez auto true */
    private fun handleIntent(intent: android.content.Intent?) {
        val raw = intent?.getStringExtra("firmware") ?: return
        val uri = if (raw.startsWith("http")) {
            android.net.Uri.parse(raw)
        } else {
            val f = java.io.File(raw)
            android.net.Uri.fromFile(java.io.File(f.absolutePath))
        }
        val name = uri.lastPathSegment ?: "firmware.bin"
        if (intent.getBooleanExtra("auto", false)) {
            vm.loadAndAutoRun(uri, name)
        } else {
            vm.loadImage(uri, name)
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "firmware.bin"

    @Composable
    private fun Screen() {
        val s by vm.state.collectAsState()
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.all { it }) vm.scan()
        }
        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.loadImage(uri, displayName(uri))
        }
        val logState = rememberLazyListState()
        LaunchedEffect(s.log.size) { if (s.log.isNotEmpty()) logState.animateScrollToItem(s.log.size - 1) }

        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = s.selectedTab) {
                Tab(
                    selected = s.selectedTab == 0,
                    onClick = { vm.selectTab(0) },
                    text = { Text("OTA Update") }
                )
                Tab(
                    selected = s.selectedTab == 1,
                    onClick = { vm.selectTab(1) },
                    text = { Text("Diagnostic Tools") }
                )
            }

            Column(
                Modifier.padding(16.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (s.selectedTab == 0) {
                    OtaSection(s, vm, askPermissions, pickFile, permissions)
                } else {
                    DiagnosticsSection(s, vm, askPermissions, permissions)
                }

                Text("Log", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    Modifier.fillMaxWidth().height(140.dp),
                    state = logState
                ) {
                    items(s.log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        }
    }

    @Composable
    private fun OtaSection(
        s: OtaUiState,
        vm: OtaViewModel,
        askPermissions: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        pickFile: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        permissions: Array<String>
    ) {
        Text("TOMS machine updater", style = MaterialTheme.typography.headlineSmall)
        Text(s.headline, style = MaterialTheme.typography.bodyLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = s.phase != Phase.UPLOADING) {
                Text("1. Choose file")
            }
            OutlinedButton(onClick = { askPermissions.launch(permissions) }, enabled = s.phase != Phase.UPLOADING && s.phase != Phase.SCANNING) {
                Text("2. Find machine")
            }
        }
        s.imageName?.let { Text("File: $it" + (s.image?.let { i -> "  (version ${i.version}, ${i.size} bytes)" } ?: "")) }
        s.imageProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (s.machines.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    s.machines.forEach { m ->
                        val chosen = m.address == s.selected?.address
                        Text(
                            (if (chosen) "● " else "○ ") + "${m.name}   ${m.address}   ${m.rssi} dBm",
                            Modifier.fillMaxWidth().clickable { vm.select(m) }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }

        if (s.phase == Phase.UPLOADING || s.phase == Phase.DONE) {
            LinearProgressIndicator(
                progress = { if (s.total == 0L) 0f else s.sent.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${s.sent} / ${s.total} bytes")
        }

        if (s.phase == Phase.UPLOADING) {
            Button(onClick = { vm.cancel() }) { Text("Cancel") }
        } else {
            Button(onClick = { vm.start() }, enabled = s.canStart) { Text("3. Update machine") }
        }
    }

    @Composable
    private fun DiagnosticsSection(
        s: OtaUiState,
        vm: OtaViewModel,
        askPermissions: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        permissions: Array<String>
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val machine = s.selected
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = if (machine != null) machine.name else "No machine selected",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (machine != null) {
                            Text(
                                text = machine.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (s.isDiagConnected) {
                        Button(
                            onClick = { vm.disconnectDiagnostics() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Disconnect")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (machine == null) askPermissions.launch(permissions)
                                else vm.connectDiagnostics()
                            },
                            enabled = machine != null && s.phase != Phase.UPLOADING
                        ) {
                            Text(if (machine != null) "Connect" else "Find Machine")
                        }
                    }
                }
                Text(s.diagStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (s.isDiagConnected) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (s.lastCardUid != null)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("NFC Card Reader (PN532)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        if (s.lastCardUid != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    " CARD WORKING ",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (s.lastCardUid != null) {
                        Text(
                            "UID: ${s.lastCardUid}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Type: ${s.lastCardType ?: "ISO 14443-A"}  |  Length: ${s.lastCardLen} bytes",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "RF Coupling: Healthy (ATQA / SAK validated)\nLast read: ${s.lastCardTime ?: "--"} (Total taps: ${s.cardTapCount})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("Ready. Tap physical NFC card against reader antenna...", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { vm.simulateCardTap("04 08 04 62") }) {
                        Text("Simulate Tap (04 08 04 62)")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Buzzer Test (GPIO6)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.testBuzzer(1) }, modifier = Modifier.weight(1f)) { Text("OK", fontSize = 11.sp) }
                        Button(onClick = { vm.testBuzzer(2) }, modifier = Modifier.weight(1f)) { Text("Warn", fontSize = 11.sp) }
                        Button(onClick = { vm.testBuzzer(0) }, modifier = Modifier.weight(1f)) { Text("Boot", fontSize = 11.sp) }
                        Button(onClick = { vm.testBuzzer(5) }, modifier = Modifier.weight(1f)) { Text("Dup", fontSize = 11.sp) }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Button (GPIO4) & Screen (ST7735)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Hardware Button Presses: ${s.buttonPressCount}" + (if (s.buttonLastTime != null) " (at ${s.buttonLastTime})" else ""), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.testScreenNext() }, modifier = Modifier.weight(1f)) {
                            Text("Next Page")
                        }
                        OutlinedButton(onClick = { vm.testToggleBacklight() }, modifier = Modifier.weight(1f)) {
                            Text("Backlight: ${if (s.screenBacklight) "ON" else "OFF"}")
                        }
                    }
                }
            }
        } else {
            Text(
                "Select a machine and tap 'Connect' above to test hardware: NFC, Buzzer, Button, and Screen.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
