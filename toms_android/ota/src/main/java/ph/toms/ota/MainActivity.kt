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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
        val machine = s.selected

        // ── Connection Card ──────────────────────────────────────────────
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (s.isDiagConnected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (s.isDiagConnected) "Connected" else "Hardware Diagnostics",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (s.isDiagConnected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            machine?.name ?: "No machine selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            if (s.isDiagConnected) vm.disconnectDiagnostics()
                            else if (machine != null) vm.connectDiagnostics()
                            else askPermissions.launch(permissions)
                        },
                        enabled = (machine != null || s.phase != Phase.SCANNING) && s.phase != Phase.UPLOADING,
                        colors = if (s.isDiagConnected)
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors()
                    ) {
                        Text(
                            if (s.isDiagConnected) "Disconnect"
                            else if (s.phase == Phase.SCANNING) "Scanning..."
                            else if (machine != null) "Connect"
                            else "Find Machine"
                        )
                    }
                }
                if (s.isDiagConnected || s.diagStatus.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(s.diagStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (s.phase == Phase.SCANNING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (!s.isDiagConnected && s.machines.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Select Machine to Connect:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    s.machines.forEach { m ->
                        val chosen = m.address == s.selected?.address
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.select(m) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (chosen) "● " else "○ ") + "${m.name}  (${m.rssi} dBm)",
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // ── NFC Card Reader: Big Animated Card ───────────────────────────
        val cardDetected = s.lastCardUid != null
        val nfcCardColor by animateColorAsState(
            targetValue = if (cardDetected) Color(0xFF1B5E20) else Color(0xFF37474F),
            animationSpec = tween(500), label = "nfcCard"
        )
        val pulseTransition = rememberInfiniteTransition(label = "nfcPulse")
        val pulseAlpha by pulseTransition.animateFloat(
            initialValue = 0.4f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseA"
        )

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = nfcCardColor),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (!cardDetected) {
                        Box(
                            Modifier.size(72.dp).scale(pulseAlpha)
                                .clip(CircleShape)
                                .border(3.dp, Color(0xFF80CBC4).copy(alpha = pulseAlpha), CircleShape)
                        )
                    }
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(if (cardDetected) Color(0xFF4CAF50) else Color(0xFF546E7A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (cardDetected) "✓" else "...",
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (cardDetected) "CARD DETECTED" else "TAP CARD ON ANTENNA",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = if (cardDetected) Color(0xFFA5D6A7) else Color(0xFFB0BEC5)
                )
                if (cardDetected) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.lastCardUid ?: "", fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "${s.lastCardType ?: "ISO 14443-A"} • ${s.lastCardLen} bytes • ${s.lastCardTime}",
                        fontSize = 12.sp, color = Color(0xFFC8E6C9)
                    )
                    Text("Total Taps: ${s.cardTapCount}", fontSize = 11.sp, color = Color(0xFFA5D6A7))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.simulateCardTap("04 08 04 62") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF80CBC4))
                ) { Text("Simulate Tap", fontSize = 12.sp) }
            }
        }

        // ── Buzzer Test: Large Color-Coded Buttons ───────────────────────
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Buzzer Test", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("GPIO6 Active Buzzer", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigBuzzerButton("OK", Color(0xFF2E7D32), Color.White, Modifier.weight(1f)) { vm.testBuzzer(1) }
                    BigBuzzerButton("WARN", Color(0xFFF57F17), Color.Black, Modifier.weight(1f)) { vm.testBuzzer(2) }
                    BigBuzzerButton("BOOT", Color(0xFF1565C0), Color.White, Modifier.weight(1f)) { vm.testBuzzer(0) }
                    BigBuzzerButton("DUP", Color(0xFF6A1B9A), Color.White, Modifier.weight(1f)) { vm.testBuzzer(5) }
                }
            }
        }

        // ── Button Counter & Screen: Animated Cards ──────────────────────
        val backlightColor by animateColorAsState(
            targetValue = if (s.screenBacklight) Color(0xFF1565C0) else Color(0xFF78909C),
            animationSpec = tween(300), label = "blColor"
        )
        val screenScale by animateFloatAsState(
            targetValue = if (s.screenBacklight) 1.0f else 0.92f,
            animationSpec = tween(300), label = "scrScale"
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.buttonPressCount}", fontSize = 36.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("BUTTON PRESSES", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.buttonLastTime != null) {
                        Text(s.buttonLastTime ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Card(Modifier.weight(1f).scale(screenScale), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = backlightColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth().clickable { vm.testToggleBacklight() },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (s.screenBacklight) "☀ ON" else "🌙 OFF",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Tap to toggle", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { vm.testScreenNext() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Text("Next Page", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun BigBuzzerButton(
        label: String, bgColor: Color, txtColor: Color,
        modifier: Modifier = Modifier, onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            modifier = modifier.height(48.dp)
        ) {
            Text(label, color = txtColor, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
        }
    }
}
