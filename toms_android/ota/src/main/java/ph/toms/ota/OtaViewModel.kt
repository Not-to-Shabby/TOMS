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
    val headline: String = "Pick the update file, then find the machine.",
    val log: List<String> = emptyList(),

    // Diagnostic Tools state
    val isDiagConnected: Boolean = false,
    val diagStatus: String = "Not connected",
    val lastCardUid: String? = null,
    val lastCardType: String? = null,
    val lastCardTime: String? = null,
    val cardTapCount: Int = 0,
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
                if (autoRun && n > 0) {
                    val best = _state.value.machines.maxBy { it.rssi }
                    say("auto: picked ${best.name} (${best.rssi} dBm)")
                    select(best)
                    start()
                    return@launch
                }
                _state.update {
                    it.copy(phase = Phase.IDLE, headline = if (n == 0) "No machine found. Is it powered and close by?" else "Choose a machine.")
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
        _state.update { it.copy(phase = Phase.UPLOADING, sent = 0, total = image.size.toLong(), headline = "Starting. Do not move away or switch the machine off.") }
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
                        _state.update { it.copy(sent = sent, total = total, headline = "Sending ${100 * sent / total}%") }
                    }
                    when (result) {
                        is OtaOutcome.Installed -> {
                            say("the machine accepted the image and is restarting")
                            _state.update {
                                it.copy(phase = Phase.DONE, sent = it.total,
                                    headline = "Sent and checked. The machine restarts into the new version on trial. " +
                                        "Wait about a minute, then reconnect: it reports the new version once it is satisfied, " +
                                        "or returns to the old one by itself.")
                            }
                        }
                        OtaOutcome.Unknown -> _state.update {
                            it.copy(phase = Phase.DONE,
                                headline = "All data was sent, but the link dropped at the end. Wait a minute, then reconnect and read the machine's version.")
                        }
                    }
                    return@launch
                } catch (e: OtaRefused) {
                    // The machine said no for a real reason; retrying will not change it.
                    say("REFUSED: ${e.message}")
                    _state.update { it.copy(phase = Phase.FAILED, headline = "The machine refused the update. ${e.status.meaning}.") }
                    return@launch
                } catch (e: CancellationException) {
                    say("cancelled")
                    throw e
                } catch (e: Exception) {
                    if (round == maxRounds) {
                        say("failed: ${e.message}")
                        _state.update { it.copy(phase = Phase.FAILED, headline = "Update stopped: ${e.message}. Nothing was installed; the machine keeps its current version.") }
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
                say("diag: connected, listening for NFC & Button events")

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
                            it.copy(
                                lastCardUid = card.uidHex,
                                lastCardType = atypeName,
                                lastCardTime = now,
                                cardTapCount = it.cardTapCount + 1
                            )
                        }
                    }
                }

                // Hardware Button Press & Diag Event Listener
                launch {
                    for (ev in transport.diagEvents) {
                        when (ev) {
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
                            is DiagEvent.Ack -> say("diag ack: cmd=0x%02X arg=0x%02X".format(ev.cmd, ev.arg))
                            is DiagEvent.Unknown -> Unit
                        }
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
        _state.update { it.copy(isDiagConnected = false, diagStatus = "Disconnected") }
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
