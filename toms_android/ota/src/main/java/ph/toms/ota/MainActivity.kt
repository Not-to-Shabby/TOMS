package ph.toms.ota

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FilePresent
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

// ── Cupertino Clean Palette ──────────────────────────────────────────────────
private val DarkBg = Color(0xFF0D1117)
private val CardBg = Color(0xFF161B22)
private val CardSubtle = Color(0xFF21262D)
private val CardBorder = Color(0xFF30363D)

private val CupertinoBlue = Color(0xFF0A84FF)
private val CupertinoGreen = Color(0xFF30D158)
private val CupertinoOrange = Color(0xFFFF9F0A)
private val CupertinoRed = Color(0xFFFF453A)
private val CupertinoPurple = Color(0xFFBF5AF2)
private val CupertinoCyan = Color(0xFF64D2FF)
private val CupertinoSlate = Color(0xFF8E8E93)

class MainActivity : ComponentActivity() {
    private val vm: OtaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = DarkBg,
                    surface = CardBg,
                    surfaceVariant = CardSubtle,
                    primary = CupertinoBlue,
                    onPrimary = Color.White,
                    secondary = CupertinoGreen,
                    error = CupertinoRed
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBg
                ) {
                    Screen()
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val raw = intent?.getStringExtra("firmware") ?: return
        val uri = if (raw.startsWith("http")) {
            Uri.parse(raw)
        } else {
            val f = java.io.File(raw)
            Uri.fromFile(java.io.File(f.absolutePath))
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
        val askPermissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { r ->
            if (r.values.all { it }) vm.scan()
        }
        val pickFile = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) vm.loadImage(uri, displayName(uri))
        }

        Column(Modifier.fillMaxSize()) {
            // Header Title & Connection Status at Top
            TopHeaderBar(s)

            // Content Area between Header and Bottom Navigation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (s.selectedTab == 0) {
                    OtaSection(s, vm, askPermissions, pickFile, permissions)
                } else {
                    DiagnosticsSection(s, vm, askPermissions, permissions)
                }
            }

            // Bottom Navigation, Find Machine & Log Dock Bar
            BottomControlBar(s, vm, askPermissions, permissions)
        }
    }

    // ── Persistent Header: Title & Live Connection Status at the Top ─────────
    @Composable
    private fun TopHeaderBar(s: OtaUiState) {
        val machine = s.selected
        val isConnected = s.isDiagConnected

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val pulse = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by pulse.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "dotA"
                    )

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isConnected -> CupertinoGreen
                                    s.phase == Phase.SCANNING -> CupertinoOrange.copy(alpha = pulseAlpha)
                                    machine != null -> CupertinoBlue
                                    else -> CupertinoSlate.copy(alpha = 0.6f)
                                }
                            )
                    )

                    Column {
                        Text(
                            text = machine?.name ?: "TOMS Return Machine",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Text(
                            text = when {
                                isConnected -> "Connected (${machine?.rssi ?: 0} dBm)"
                                s.phase == Phase.SCANNING -> "Scanning for devices..."
                                machine != null -> "Selected • ${machine.address}"
                                else -> "No machine connected"
                            },
                            fontSize = 11.sp,
                            color = when {
                                isConnected -> CupertinoGreen
                                s.phase == Phase.SCANNING -> CupertinoOrange
                                else -> CupertinoSlate
                            }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (machine != null && isConnected) {
                        val battText = if (s.batteryPct in 0..100 && s.batteryMv > 0) {
                            "🔋 ${s.batteryPct}%"
                        } else {
                            "⚡ USB 5V"
                        }
                        MetadataBadge(
                            label = battText,
                            color = CupertinoGreen
                        )
                    }

                    if (machine != null) {
                        MetadataBadge(
                            label = "${machine.rssi} dBm",
                            color = if (isConnected) CupertinoGreen else CupertinoBlue
                        )
                    }
                }
            }
        }
    }

    // ── Bottom Controls: Find Machine, Log Dock & Navigation Tabs ────────────
    @Composable
    private fun BottomControlBar(
        s: OtaUiState,
        vm: OtaViewModel,
        askPermissions: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        permissions: Array<String>
    ) {
        val machine = s.selected
        val isConnected = s.isDiagConnected

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkBg)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Find Machine / Connection Action Button in the Bottom Zone
            if (isConnected) {
                Button(
                    onClick = { vm.disconnectDiagnostics() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoRed.copy(alpha = 0.15f),
                        contentColor = CupertinoRed
                    ),
                    border = BorderStroke(1.dp, CupertinoRed.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Disconnect from ${machine?.name ?: "Machine"}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            } else if (machine != null && s.selectedTab == 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { askPermissions.launch(permissions) },
                        enabled = s.phase != Phase.UPLOADING && s.phase != Phase.SCANNING,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CupertinoBlue),
                        border = BorderStroke(1.dp, CardBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (s.phase == Phase.SCANNING) Icons.AutoMirrored.Rounded.BluetoothSearching else Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (s.phase == Phase.SCANNING) "Scanning..." else "Find Machine",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = { vm.connectDiagnostics() },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CupertinoBlue,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BluetoothConnected,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Connect",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Button(
                    onClick = { askPermissions.launch(permissions) },
                    enabled = s.phase != Phase.UPLOADING && s.phase != Phase.SCANNING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardSubtle,
                        contentColor = CupertinoBlue
                    ),
                    border = BorderStroke(1.dp, CardBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (s.phase == Phase.SCANNING) Icons.AutoMirrored.Rounded.BluetoothSearching else Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (s.phase == Phase.SCANNING) "Scanning for Machines..." else "Find Machine",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            // Collapsible Log Pill directly above tabs
            LogDockAndSheet(s, vm)

            // Cupertino Segmented Control Tab Row at the bottom
            CupertinoSegmentedTabs(
                selectedTab = s.selectedTab,
                onSelect = { vm.selectTab(it) }
            )
        }
    }

    // ── Cupertino Segmented Tab Control ──────────────────────────────────────
    @Composable
    private fun CupertinoSegmentedTabs(
        selectedTab: Int,
        onSelect: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardSubtle)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val tabs = listOf("OTA Update", "Diagnostic Tools")
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) CardBg else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (index == 0) Icons.Rounded.SystemUpdate else Icons.Rounded.Sensors,
                            contentDescription = null,
                            tint = if (isSelected) CupertinoBlue else CupertinoSlate,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = title,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = if (isSelected) Color.White else CupertinoSlate
                        )
                    }
                }
            }
        }
    }

    // ── Diagnostic Tools Tab (Cupertino Clean) ───────────────────────────────
    @Composable
    private fun DiagnosticsSection(
        s: OtaUiState,
        vm: OtaViewModel,
        askPermissions: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        permissions: Array<String>
    ) {
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val cardDetected = s.lastCardUid != null

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            val minHeight = maxHeight
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = minHeight),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
                ) {
                    Spacer(Modifier.height(6.dp))

                    // 1. Apple Wallet-Style NFC Pass Card
            val cardGradient = if (cardDetected) {
            Brush.verticalGradient(listOf(Color(0xFF143823), Color(0xFF0D1E15)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFF1C222B), Color(0xFF161B22)))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, if (cardDetected) CupertinoGreen.copy(alpha = 0.5f) else CardBorder)
        ) {
            Box(
                Modifier
                    .background(cardGradient)
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Top Pass Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Nfc,
                                contentDescription = null,
                                tint = if (cardDetected) CupertinoGreen else CupertinoSlate,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "NFC PASS READER",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.sp,
                                    color = if (cardDetected) CupertinoGreen else CupertinoSlate
                                )
                                if (s.isDiagConnected) {
                                    Text(
                                        text = if (s.isNfcOnline) "PN532 READY" else "PN532 ${s.nfcHealthStatus}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (s.isNfcOnline) CupertinoGreen else CupertinoOrange
                                    )
                                }
                            }
                        }

                        // Card Counter Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardSubtle)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Inserts: ${s.cardTapCount}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (cardDetected) Color.White else CupertinoSlate
                            )
                        }
                    }

                    // Main Status / UID Display
                    if (cardDetected) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "CARD DETECTED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoGreen
                            )
                            // UID Chip with Copy button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardSubtle)
                                    .clickable {
                                        s.lastCardUid?.let {
                                            clipboard.setText(AnnotatedString(it))
                                            Toast.makeText(context, "UID Copied", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = s.lastCardUid ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy UID",
                                    tint = CupertinoSlate,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Metadata Badges
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MetadataBadge(label = s.lastCardType ?: "ISO 14443-A", color = CupertinoGreen)
                                MetadataBadge(label = "${s.lastCardLen} bytes", color = CupertinoBlue)
                                Text(
                                    text = s.lastCardTime ?: "",
                                    fontSize = 11.sp,
                                    color = CupertinoSlate
                                )
                            }
                        }
                    } else {
                        // Empty / Waiting State with animated ripple
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val pulse = rememberInfiniteTransition(label = "nfcIdle")
                            val haloScale by pulse.animateFloat(
                                initialValue = 0.9f,
                                targetValue = 1.15f,
                                animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                                label = "scale"
                            )

                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .scale(haloScale)
                                    .clip(CircleShape)
                                    .background(CardSubtle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sensors,
                                    contentDescription = null,
                                    tint = CupertinoSlate,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Column {
                                val nfcTitle = when {
                                    !s.isDiagConnected -> "Connect to inspect NFC"
                                    s.isNfcOnline -> "NFC Antenna Ready (Online)"
                                    else -> "PN532 Reader Offline"
                                }
                                val nfcSubtitle = when {
                                    !s.isDiagConnected -> "Insert transit card into return slot"
                                    s.isNfcOnline -> "PN532 active on I2C 0x24 • Ready for card insertion"
                                    else -> "I2C error: Check jumper wires (SDA=4, SCL=5)"
                                }
                                Text(
                                    text = nfcTitle,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = if (s.isDiagConnected && !s.isNfcOnline) CupertinoOrange else Color.White
                                )
                                Text(
                                    text = nfcSubtitle,
                                    fontSize = 11.sp,
                                    color = CupertinoSlate
                                )
                            }
                        }
                    }

                    // Simulation & Test Action Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { vm.testNfcHardware() },
                            modifier = Modifier.weight(1.2f).height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardSubtle,
                                contentColor = CupertinoCyan
                            ),
                            border = BorderStroke(1.dp, CardBorder),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Sensors, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ping PN532", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { vm.simulateCardTap("04 08 04 62") },
                            modifier = Modifier.weight(1f).height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, CardBorder)
                        ) {
                            Text("Sim Type 2", fontSize = 11.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = { vm.simulateCardTap("04 62 BB B6 9E 5D 80") },
                            modifier = Modifier.weight(1f).height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, CardBorder)
                        ) {
                            Text("Sim 7-byte", fontSize = 11.sp, color = Color.White)
                        }
                    }

                    // Recent Card Scan History (Collapsible)
                    if (s.cardHistory.isNotEmpty()) {
                        var showHistory by remember { mutableStateOf(false) }
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showHistory = !showHistory }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = CupertinoSlate,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Recent Scans (${s.cardHistory.size})",
                                        fontSize = 11.sp,
                                        color = CupertinoSlate
                                    )
                                }
                                Icon(
                                    imageVector = if (showHistory) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = CupertinoSlate,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            AnimatedVisibility(visible = showHistory) {
                                Column(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    s.cardHistory.forEach { h ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(CardSubtle.copy(alpha = 0.6f))
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = h.uid,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "${h.type} • ${h.time}",
                                                fontSize = 10.sp,
                                                color = CupertinoSlate
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Hardware Buzzer Soundboard (All 6 Tones in 3x2 Grid)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BUZZER SOUNDBOARD",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = CupertinoSlate
                        )
                        Text(
                            text = "GPIO6 Hardware Piezo Tests",
                            fontSize = 11.sp,
                            color = CupertinoSlate
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = CupertinoCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 3x2 Grid of soft pill buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CupertinoBuzzerPill("BOOT", "Tone 0", CupertinoCyan, Modifier.weight(1f)) { vm.testBuzzer(0) }
                        CupertinoBuzzerPill("OK", "Tone 1", CupertinoGreen, Modifier.weight(1f)) { vm.testBuzzer(1) }
                        CupertinoBuzzerPill("WARN", "Tone 2", CupertinoOrange, Modifier.weight(1f)) { vm.testBuzzer(2) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CupertinoBuzzerPill("UNKN", "Tone 3", CupertinoSlate, Modifier.weight(1f)) { vm.testBuzzer(3) }
                        CupertinoBuzzerPill("UNVF", "Tone 4", CupertinoRed, Modifier.weight(1f)) { vm.testBuzzer(4) }
                        CupertinoBuzzerPill("DUP", "Tone 5", CupertinoPurple, Modifier.weight(1f)) { vm.testBuzzer(5) }
                    }
                }
            }
        }

        // 3. Sensors Row: Hardware Button Counter & LCD Screen Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Button Counter Card
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "BUTTON",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CupertinoSlate
                        )
                        Icon(
                            imageVector = Icons.Rounded.TouchApp,
                            contentDescription = null,
                            tint = CupertinoBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Text(
                        text = "${s.buttonPressCount}",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.buttonLastTime ?: "No press yet",
                            fontSize = 10.sp,
                            color = CupertinoSlate
                        )
                        if (s.buttonPressCount > 0) {
                            Text(
                                text = "Reset",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = CupertinoBlue,
                                modifier = Modifier.clickable { vm.resetButtonCounter() }
                            )
                        }
                    }
                }
            }

            // LCD Screen & Backlight Card
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DISPLAY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CupertinoSlate
                        )
                        Icon(
                            imageVector = Icons.Rounded.LightMode,
                            contentDescription = null,
                            tint = if (s.screenBacklight) CupertinoOrange else CupertinoSlate,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Dedicated Backlight Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (s.screenBacklight) "Backlight On" else "Backlight Off",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                        Switch(
                            checked = s.screenBacklight,
                            onCheckedChange = { vm.testToggleBacklight() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = CupertinoOrange,
                                uncheckedThumbColor = CupertinoSlate,
                                uncheckedTrackColor = CardSubtle
                            ),
                            modifier = Modifier.scale(0.8f)
                        )
                    }

                    // Distinct Next Page Button
                    Button(
                        onClick = { vm.testScreenNext() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardSubtle,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Next Page", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Discovered Machines List in Diagnostics tab (if scanned while disconnected)
        if (!s.isDiagConnected && s.machines.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "DISCOVERED MACHINES",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = CupertinoSlate
                    )
                    s.machines.forEach { m ->
                        val isSelected = m.address == s.selected?.address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) CupertinoBlue.copy(alpha = 0.12f) else CardSubtle)
                                .clickable { vm.select(m) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Bluetooth,
                                    contentDescription = null,
                                    tint = if (isSelected) CupertinoBlue else CupertinoSlate,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = m.name,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                    Text(text = m.address, fontSize = 11.sp, color = CupertinoSlate)
                                }
                            }
                            Text(
                                text = "${m.rssi} dBm",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (m.rssi > -70) CupertinoGreen else CupertinoOrange
                            )
                        }
                    }
                }
            }
        }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    // ── OTA Update Tab (Cupertino Clean) ─────────────────────────────────────
    @Composable
    private fun OtaSection(
        s: OtaUiState,
        vm: OtaViewModel,
        askPermissions: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        pickFile: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        permissions: Array<String>
    ) {
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            val minHeight = maxHeight
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = minHeight),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
                ) {
                    Spacer(Modifier.height(6.dp))

                    // 1. 3-Phase Stepper Tracker
        val currentStep = when {
            s.image == null -> 1
            s.selected == null -> 2
            else -> 3
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepIndicator(step = 1, label = "Image", isActive = currentStep == 1, isDone = s.image != null)
                StepLine(isDone = s.image != null)
                StepIndicator(step = 2, label = "Machine", isActive = currentStep == 2, isDone = s.selected != null)
                StepLine(isDone = s.selected != null)
                StepIndicator(step = 3, label = "Update", isActive = currentStep == 3, isDone = s.phase == Phase.DONE)
            }
        }

        // 2. Firmware Inspection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "FIRMWARE BINARY",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = CupertinoSlate
                        )
                        Text(
                            text = s.imageName ?: "No file loaded",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        enabled = s.phase != Phase.UPLOADING,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardSubtle,
                            contentColor = CupertinoBlue
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilePresent,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (s.image != null) "Change" else "Choose File", fontSize = 12.sp)
                    }
                }

                s.imageProblem?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CupertinoRed.copy(alpha = 0.15f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            tint = CupertinoRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(text = err, color = CupertinoRed, fontSize = 12.sp)
                    }
                }

                s.image?.let { img ->
                    // Image details badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetadataBadge(label = "Version: ${img.version}", color = CupertinoBlue)
                        MetadataBadge(label = "${img.size / 1024} KB", color = CupertinoGreen)
                        MetadataBadge(label = "MCUboot Signed", color = CupertinoPurple)
                    }

                    // SHA-256 fingerprint with copy
                    val hashHex = img.sha256.joinToString("") { "%02x".format(it) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardSubtle)
                            .clickable {
                                clipboard.setText(AnnotatedString(hashHex))
                                Toast.makeText(context, "SHA-256 Copied", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("SHA-256 Fingerprint", fontSize = 10.sp, color = CupertinoSlate)
                            Text(
                                text = "${hashHex.take(12)}...${hashHex.takeLast(12)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy Hash",
                            tint = CupertinoSlate,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // 3. Machine Scanner List (if not uploading)
        if (s.phase != Phase.UPLOADING && s.machines.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "DISCOVERED MACHINES",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = CupertinoSlate
                    )

                    s.machines.forEach { m ->
                        val isSelected = m.address == s.selected?.address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) CupertinoBlue.copy(alpha = 0.12f) else CardSubtle)
                                .clickable { vm.select(m) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Bluetooth,
                                    contentDescription = null,
                                    tint = if (isSelected) CupertinoBlue else CupertinoSlate,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = m.name,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                    Text(text = m.address, fontSize = 11.sp, color = CupertinoSlate)
                                }
                            }
                            Text(
                                text = "${m.rssi} dBm",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (m.rssi > -70) CupertinoGreen else CupertinoOrange
                            )
                        }
                    }
                }
            }
        }

        // 4. Live Progress & Telemetry Dashboard
        if (s.phase == Phase.UPLOADING || s.phase == Phase.DONE) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, if (s.phase == Phase.DONE) CupertinoGreen else CupertinoBlue)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val pct = if (s.total == 0L) 0 else ((100 * s.sent) / s.total).toInt()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (s.phase == Phase.DONE) "UPDATE COMPLETE" else "FLASHING FIRMWARE",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = if (s.phase == Phase.DONE) CupertinoGreen else CupertinoBlue
                        )
                        Text(
                            text = "$pct%",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Progress track
                    LinearProgressIndicator(
                        progress = { if (s.total == 0L) 0f else s.sent.toFloat() / s.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (s.phase == Phase.DONE) CupertinoGreen else CupertinoBlue,
                        trackColor = CardSubtle
                    )

                    // Real-time telemetry indicators (Speed & ETA)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = CupertinoSlate,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.1f", s.transferSpeedKBps)} KB/s",
                                fontSize = 12.sp,
                                color = CupertinoSlate
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = null,
                                tint = CupertinoSlate,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (s.phase == Phase.DONE) "Finished" else "ETA: ${s.etaSeconds}s",
                                fontSize = 12.sp,
                                color = CupertinoSlate
                            )
                        }

                        Text(
                            text = "${s.sent / 1024} KB / ${s.total / 1024} KB",
                            fontSize = 12.sp,
                            color = CupertinoSlate
                        )
                    }

                    if (s.phase == Phase.DONE) {
                        Text(
                            text = "Machine accepted image and is restarting into the new version on trial.",
                            fontSize = 12.sp,
                            color = CupertinoGreen
                        )
                    }
                }
            }
        }

        // 5. Main Action Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        ) {
            if (s.phase == Phase.UPLOADING) {
                Button(
                    onClick = { vm.cancel() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CupertinoRed)
                ) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel Update", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = { vm.start() },
                    enabled = s.canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoBlue,
                        disabledContainerColor = CardSubtle
                    )
                ) {
                    Icon(imageVector = Icons.Rounded.SystemUpdate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Flash Firmware to Machine", fontWeight = FontWeight.SemiBold)
                }
            }
        }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    // ── Collapsible Bottom Sheet Log Console ─────────────────────────────────
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LogDockAndSheet(
        s: OtaUiState,
        vm: OtaViewModel,
        modifier: Modifier = Modifier
    ) {
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        var showSheet by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val scope = rememberCoroutineScope()

        // 1. Persistent 1-Line Log Pill at bottom
        Box(
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                    .clickable { showSheet = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = CupertinoSlate,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = s.log.lastOrNull() ?: "Ready",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardSubtle)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${s.log.size}",
                            fontSize = 10.sp,
                            color = CupertinoSlate,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Expand Log",
                        tint = CupertinoSlate,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 2. Expanded Modal Bottom Sheet
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
                containerColor = CardBg,
                dragHandle = {
                    Box(
                        Modifier
                            .padding(top = 10.dp, bottom = 4.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(CupertinoSlate.copy(alpha = 0.4f))
                    )
                }
            ) {
                val logListState = rememberLazyListState()
                LaunchedEffect(s.log.size) {
                    if (s.log.isNotEmpty()) logListState.animateScrollToItem(s.log.size - 1)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Header with actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = CupertinoBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "System & BLE Event Log",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = {
                                val fullLog = s.log.joinToString("\n")
                                clipboard.setText(AnnotatedString(fullLog))
                                Toast.makeText(context, "All logs copied", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy all",
                                    tint = CupertinoSlate,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = { vm.clearLogs() }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Clear",
                                    tint = CupertinoSlate,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Scrollable log console
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkBg)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (s.log.isEmpty()) {
                            item {
                                Text(
                                    text = "No log events yet.",
                                    fontSize = 12.sp,
                                    color = CupertinoSlate,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            items(s.log) { line ->
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = when {
                                        line.contains("REFUSED") || line.contains("failed") || line.contains("error") -> CupertinoRed
                                        line.contains("TAP:") || line.contains("accepted") || line.contains("ready") -> CupertinoGreen
                                        line.contains("WARN") || line.contains("trouble") -> CupertinoOrange
                                        else -> Color.White.copy(alpha = 0.85f)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // ── Supporting UI Components ─────────────────────────────────────────────
    @Composable
    private fun CupertinoBuzzerPill(
        label: String,
        sub: String,
        color: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = color.copy(alpha = 0.15f),
                contentColor = color
            ),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
            border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
            modifier = modifier.height(52.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = sub,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.85f),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }

    @Composable
    private fun MetadataBadge(label: String, color: Color) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }

    @Composable
    private fun StepIndicator(step: Int, label: String, isActive: Boolean, isDone: Boolean) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isDone -> CupertinoGreen
                            isActive -> CupertinoBlue
                            else -> CardSubtle
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isDone) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Text(
                        text = "$step",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color.White else CupertinoSlate
                    )
                }
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isActive || isDone) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive || isDone) Color.White else CupertinoSlate
            )
        }
    }

    @Composable
    private fun StepLine(isDone: Boolean) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(2.dp)
                .background(if (isDone) CupertinoGreen else CardSubtle)
        )
    }
}
