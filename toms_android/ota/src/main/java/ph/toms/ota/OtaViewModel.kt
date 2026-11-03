package ph.toms.ota

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, SCANNING, UPLOADING, DONE, FAILED }

data class CardTapRecord(
    val uid: String,
    val len: Int,
    val type: String,
    val time: String,
    val tapIndex: Int
)

data class OtaUiState(
    val selectedTab: Int = 0, // 0 = OTA Update, 1 = Diagnostic Tools
    val phase: Phase = Phase.IDLE,
    val machines: List<FoundMachine> = emptyList(),
    val selected: FoundMachine? = null,
    val imageName: String? = null,
    val image: OtaImage? = null,
    val imageProblem: String? = null,
    val sent: Long = 0,
    val total: Long = 0,
    val transferSpeedKBps: Float = 0f,
    val etaSeconds: Int = 0,
    val elapsedSeconds: Int = 0,
    val headline: String = "Pick the update file, then find the machine.",
    val log: List<String> = emptyList(),
    val isLogSheetOpen: Boolean = false,

    // Diagnostic Tools state
    val isDiagConnected: Boolean = false,
    val diagStatus: String = "Not connected",
    val isNfcOnline: Boolean = false,
    val nfcHealthStatus: String = "CHECKING...",
    val nfcDetails: String = "",
    val machineUptimeS: Long = 0,
    val batteryPct: Int = 0xFF,
    val batteryMv: Int = 0,
    val simulatedVerdict: Int = 1, // 0 = PAID/OK, 1 = UNPAID, 2 = UNKNOWN, 3 = ALREADY_RETURNED
    val lastCardUid: String? = null,
    val lastCardLen: Int = 0,
    val lastCardType: String? = null,
    val lastCardTime: String? = null,
    val cardTapCount: Int = 0,
    val cardHistory: List<CardTapRecord> = emptyList(),
    val buttonPressCount: Int = 0,
    val buttonLastTime: String? = null,
    val screenBacklight: Boolean = true,
) {
    val canStart: Boolean get() = image != null && selected != null && phase != Phase.UPLOADING && phase != Phase.SCANNING
}

class OtaViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(OtaUiState())
    val state: StateFlow<OtaUiState> = _state
    private var job: Job? = null
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun say(line: String) {
        val stamped = "${clock.format(Date())}  $line"
        _state.update { it.copy(log = (it.log + stamped).takeLast(300)) }
    }

    fun loadImage(uri: Uri, name: String) {
        viewModelScope.launch {
            try {
                val bytes = readBytes(uri, name)
                val image = ImageParser.parse(bytes)
                say("file $name: ${bytes.size} bytes, firmware version ${image.version}")
                _state.update {
                    it.copy(image = image, imageName = name, imageProblem = null,
                        headline = "File ready: version ${image.version}. Now find the machine.")
                }
            } catch (e: ImageRejected) {
                say("file $name refused: ${e.message}")
                _state.update { it.copy(image = null, imageName = name, imageProblem = e.message) }
            } catch (e: Exception) {
                say("could not read $name: ${e.message}")
                _state.update { it.copy(image = null, imageName = name, imageProblem = "Could not read the file: ${e.message}") }
            }
        }
    }

    /** Direct path first; if scoped storage blocks it, look the file up by
     *  name in the Download collection (adb pushed files land there). */
    private fun readBytes(uri: Uri, name: String): ByteArray {
        if (uri.scheme == "http" || uri.scheme == "https") {
            // adb reverse tcp:8080 tcp:8080 serves the file from the PC.
            val conn = java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            try {
                conn.inputStream.use { return it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
        val resolver = getApplication<Application>().contentResolver
        try {
            return resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw ImageRejected("Could not open the file.")
        } catch (e: Exception) {
            say("direct read failed (${e.message}); trying MediaStore")
        }
        val collections = mutableListOf(Uri.parse("content://media/external/downloads"))
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            collections.add(0, android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }
        for (collection in collections) {
            try {
                val cursor = resolver.query(collection,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID),
                    "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name), null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val media = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                        return resolver.openInputStream(media)?.use { it.readBytes() }
                            ?: throw ImageRejected("empty")
                    }
                }
            } catch (_: Exception) { /* try the next collection */ }
        }
        throw ImageRejected("file not found in MediaStore either")
    }

    /** Set when started from adb with --ez auto true: scan, pick the strongest
     *  machine and update with no taps. */
    private var autoRun = false

    /** Load [uri], then scan and update automatically (adb driven runs). */
    fun loadAndAutoRun(uri: Uri, name: String) {
        autoRun = true
        loadImage(uri, name)
        // loadImage loads async; chain the scan when the file is accepted.
        viewModelScope.launch {
            var waited = 0
            while (_state.value.image == null && _state.value.imageProblem == null && waited < 5000) {
                kotlinx.coroutines.delay(100); waited += 100
            }
            if (_state.value.image != null) scan()
        }
    }

    fun scan() {
        job?.cancel()
        _state.update { it.copy(phase = Phase.SCANNING, machines = emptyList(), selected = null, headline = "Looking for machines (10 s)...") }
        say("scanning")
        job = viewModelScope.launch {
            try {
                BleTransport.scan(getApplication(), 10_000) { m ->
                    _state.update { s ->
                        if (s.machines.any { it.address == m.address }) s
                        else s.copy(machines = s.machines + m)
                    }
                }
                val n = _state.value.machines.size
                say("scan finished: $n machine(s)")
                val autoPick = _state.value.selected
                    ?: _state.value.machines.firstOrNull { it.name.contains("TOMS", ignoreCase = true) }
                    ?: _state.value.machines.firstOrNull()

                if (autoRun && n > 0) {
                    val best = _state.value.machines.maxBy { it.rssi }
                    say("auto: picked ${best.name} (${best.rssi} dBm)")
                    select(best)
                    start()
                    return@launch
                }
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        selected = autoPick,
                        headline = if (n == 0) "No machine found. Is it powered and close by?"
                                   else if (autoPick != null) "Selected ${autoPick.name}."
                                   else "Choose a machine."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("scan failed: ${e.message}")
                _state.update { it.copy(phase = Phase.FAILED, headline = "Scan failed: ${e.message}") }
            }
        }
    }

    fun select(m: FoundMachine) {
        _state.update { it.copy(selected = m, headline = "Selected ${m.name}.") }
    }

    fun start() {
        val s = _state.value
        val image = s.image ?: return
        val machine = s.selected ?: return
        job?.cancel()
        val uploadStartMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                phase = Phase.UPLOADING,
                sent = 0,
                total = image.size.toLong(),
                transferSpeedKBps = 0f,
                etaSeconds = 0,
                elapsedSeconds = 0,
                headline = "Starting. Do not move away or switch the machine off."
            )
        }
        say("upload to ${machine.name} (${machine.address})")
        job = viewModelScope.launch {
            val maxRounds = 3
            for (round in 1..maxRounds) {
                val uploader = OtaUploader(
                    connect = { BleTransport.connect(getApplication(), machine.device, ::say) },
                    log = ::say,
                )
                try {
                    val result = uploader.upload(image) { sent, total ->
                        val now = System.currentTimeMillis()
                        val elapsedMs = (now - uploadStartMs).coerceAtLeast(1)
                        val speed = (sent.toFloat() / (elapsedMs / 1000f)) / 1024f
                        val remainingBytes = (total - sent).coerceAtLeast(0)
                        val eta = if (speed > 0.5f) (remainingBytes / (speed * 1024f)).toInt() else 0
                        _state.update {
                            it.copy(
                                sent = sent,
                                total = total,
                                transferSpeedKBps = speed,
                                etaSeconds = eta,
                                elapsedSeconds = (elapsedMs / 1000).toInt(),
                                headline = "Sending ${100 * sent / total}% (${String.format(Locale.US, "%.1f", speed)} KB/s)"
                            )
                        }
                    }
                    when (result) {
                        is OtaOutcome.Installed -> {
                            say("the machine accepted the image and is restarting")
                            _state.update {
                                it.copy(
                                    phase = Phase.DONE,
                                    sent = it.total,
                                    transferSpeedKBps = 0f,
                                    etaSeconds = 0,
                                    headline = "Update installed successfully. The machine is restarting into the new version."
                                )
                            }
                        }
                        OtaOutcome.Unknown -> _state.update {
                            it.copy(
                                phase = Phase.DONE,
                                headline = "All data was sent, link dropped at restart. Reconnect to verify version."
                            )
                        }
                    }
                    return@launch
                } catch (e: OtaRefused) {
                    // The machine said no for a real reason; retrying will not change it.
                    say("REFUSED: ${e.message}")
                    _state.update { it.copy(phase = Phase.FAILED, headline = "The machine refused the update: ${e.status.meaning}.") }
                    return@launch
                } catch (e: CancellationException) {
                    say("cancelled")
                    throw e
                } catch (e: Exception) {
                    if (round == maxRounds) {
                        say("failed: ${e.message}")
                        _state.update { it.copy(phase = Phase.FAILED, headline = "Update stopped: ${e.message}. Machine keeps current version.") }
                        return@launch
                    }
                    say("round $round stopped (${e.message}); retrying ${round + 1} of $maxRounds")
                    _state.update { it.copy(headline = "Link trouble - retry ${round + 1} of $maxRounds...") }
                    delay(3000)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = Phase.IDLE, headline = "Cancelled. The machine keeps its current version.") }
    }

    private var diagTransport: BleTransport? = null
    private var diagJob: Job? = null

    fun selectTab(index: Int) {
        _state.update { it.copy(selectedTab = index) }
    }

    fun connectDiagnostics() {
        val machine = _state.value.selected
        if (machine == null) {
            say("diag: please select a machine first")
            return
        }
        diagJob?.cancel()
        _state.update { it.copy(diagStatus = "Connecting to ${machine.name}...") }
        say("diag: connecting to ${machine.name} (${machine.address})")
        diagJob = viewModelScope.launch {
            try {
                diagTransport?.close()
                val transport = BleTransport.connect(getApplication(), machine.device, ::say)
                diagTransport = transport
                _state.update { it.copy(isDiagConnected = true, diagStatus = "Connected to ${machine.name}") }
                say("diag: connected, unlocking diagnostic session")

                // Send special diagnostic security unlock code to Return Machine
                val unlockOk = transport.writeDiagUnlock()
                if (unlockOk) {
                    say("diag: special security unlock key sent (TOMS!)")
                } else {
                    say("diag: warning: could not send unlock key")
                }

                // NFC Card Read Listener
                launch {
                    for (card in transport.cardReadEvents) {
                        val atypeName = when (card.atype) {
                            1 -> "Type 2 (NTAG)"
                            2 -> "Type 4 (DESFire)"
                            3 -> "MIFARE Classic"
                            else -> "ISO 14443-A"
                        }
                        val now = clock.format(Date())
                        say("NFC TAP: ${card.uidHex} ($atypeName)")
                        _state.update {
                            val newRecord = CardTapRecord(
                                uid = card.uidHex,
                                len = card.uidLen,
                                type = atypeName,
                                time = now,
                                tapIndex = it.cardTapCount + 1
                            )
                            it.copy(
                                lastCardUid = card.uidHex,
                                lastCardLen = card.uidLen,
                                lastCardType = atypeName,
                                lastCardTime = now,
                                cardTapCount = it.cardTapCount + 1,
                                cardHistory = (listOf(newRecord) + it.cardHistory).take(5)
                            )
                        }
                    }
                }

                // Hardware Button Press & Diag Event Listener
                launch {
                    for (ev in transport.diagEvents) {
                        when (ev) {
                            is DiagEvent.SessionUnlocked -> {
                                say(if (ev.success) "DIAGNOSTIC SESSION: UNLOCKED & AUTHORIZED" else "DIAGNOSTIC SESSION: KEY REFUSED")
                            }
                            is DiagEvent.ButtonPress -> {
                                val now = clock.format(Date())
                                say("BUTTON PRESSED: count=${ev.count}")
                                _state.update {
                                    it.copy(
                                        buttonPressCount = ev.count,
                                        buttonLastTime = now
                                    )
                                }
                            }
                            is DiagEvent.NfcHealth -> {
                                say("NFC HEALTH: online=${ev.isOnline} (${ev.versionStr})")
                                _state.update {
                                    it.copy(
                                        isNfcOnline = ev.isOnline,
                                        nfcHealthStatus = if (ev.isOnline) "READY" else "ERROR",
                                        nfcDetails = if (ev.isOnline) "PN532 ${ev.versionStr} Online" else "PN532 Communication Error"
                                    )
                                }
                            }
                            is DiagEvent.Ack -> say("diag ack: cmd=0x%02X arg=0x%02X".format(ev.cmd, ev.arg))
                            is DiagEvent.Unknown -> Unit
                        }
                    }
                }

                // Heartbeat & NFC Sensor Health Listener
                launch {
                    for (hb in transport.heartbeatEvents) {
                        val nfcOk = hb.nfcStatus == 0
                        val statusText = if (nfcOk) "READY" else "ERROR (code ${hb.nfcStatus})"
                        say("HEARTBEAT: NFC $statusText, uptime ${hb.uptimeS}s, batt ${hb.batteryMv}mV")
                        _state.update {
                            it.copy(
                                isNfcOnline = nfcOk,
                                nfcHealthStatus = statusText,
                                machineUptimeS = hb.uptimeS,
                                batteryPct = hb.batteryPct,
                                batteryMv = hb.batteryMv
                            )
                        }
                    }
                }

                // Paid Check & Fare Verdict Responder
                launch {
                    for (chk in transport.paidCheckEvents) {
                        val verdict = _state.value.simulatedVerdict
                        val vNames = listOf("PAID (OK)", "UNPAID", "UNKNOWN", "ALREADY_RETURNED")
                        val vName = vNames.getOrNull(verdict) ?: "Verdict $verdict"
                        say("PAID_CHECK: card ${chk.uidHex} (seq ${chk.checkSeq}) -> sending $vName")
                        val ok = transport.writePaidReply(chk.checkSeq, verdict)
                        if (!ok) say("PAID_REPLY: failed to send verdict")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("diag connect failed: ${e.message}")
                _state.update { it.copy(isDiagConnected = false, diagStatus = "Failed: ${e.message}") }
            }
        }
    }

    fun disconnectDiagnostics() {
        diagJob?.cancel()
        diagTransport?.close()
        diagTransport = null
        _state.update { it.copy(isDiagConnected = false, diagStatus = "Disconnected", isNfcOnline = false, nfcHealthStatus = "CHECKING...") }
        say("diag: disconnected")
    }

    fun testBuzzer(tone: Int = 1) {
        viewModelScope.launch {
            val names = listOf("Boot", "OK", "Warning", "Unknown", "Unverified", "Duplicate")
            val name = names.getOrNull(tone) ?: "Tone $tone"
            say("diag: test buzzer ($name)")
            val ok = diagTransport?.writeDiag(0x01, tone) ?: false
            if (!ok) say("diag: buzzer command failed (not connected?)")
        }
    }

    fun testScreenNext() {
        viewModelScope.launch {
            say("diag: trigger screen next page")
            val ok = diagTransport?.writeDiag(0x02) ?: false
            if (!ok) say("diag: screen command failed (not connected?)")
        }
    }

    fun testToggleBacklight() {
        viewModelScope.launch {
            say("diag: toggle LCD backlight")
            val ok = diagTransport?.writeDiag(0x03) ?: false
            if (ok) {
                _state.update { it.copy(screenBacklight = !it.screenBacklight) }
            } else {
                say("diag: backlight command failed (not connected?)")
            }
        }
    }

    fun testNfcHardware() {
        viewModelScope.launch {
            say("diag: testing PN532 NFC hardware...")
            val ok = diagTransport?.writeDiag(0x04) ?: false
            if (!ok) say("diag: NFC command failed (not connected?)")
        }
    }

    fun setSimulatedVerdict(verdict: Int) {
        val vNames = listOf("PAID (OK)", "UNPAID", "UNKNOWN", "ALREADY_RETURNED")
        val vName = vNames.getOrNull(verdict) ?: "Verdict $verdict"
        say("diag: set card response verdict to $vName")
        _state.update { it.copy(simulatedVerdict = verdict) }
    }

    fun clearLogs() {
        _state.update { it.copy(log = emptyList()) }
    }

    fun toggleLogSheet(open: Boolean? = null) {
        _state.update { it.copy(isLogSheetOpen = open ?: !it.isLogSheetOpen) }
    }

    fun resetButtonCounter() {
        _state.update { it.copy(buttonPressCount = 0, buttonLastTime = null) }
    }

    fun simulateCardTap(uidHex: String = "04 08 04 62") {
        viewModelScope.launch {
            try {
                val clean = uidHex.replace(" ", "").trim()
                val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                say("diag: simulate card tap $clean")
                val ok = diagTransport?.writeSimCard(bytes) ?: false
                if (!ok) say("diag: sim_card write failed (not connected?)")
            } catch (e: Exception) {
                say("diag: invalid UID hex: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        diagTransport?.close()
    }
}
