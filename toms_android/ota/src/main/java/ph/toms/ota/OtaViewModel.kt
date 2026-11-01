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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, SCANNING, UPLOADING, DONE, FAILED }

data class OtaUiState(
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
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw ImageRejected("Could not open the file.")
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
            } catch (e: OtaRefused) {
                say("REFUSED: ${e.message}")
                _state.update { it.copy(phase = Phase.FAILED, headline = "The machine refused the update. ${e.status.meaning}.") }
            } catch (e: CancellationException) {
                say("cancelled")
                throw e
            } catch (e: Exception) {
                say("failed: ${e.message}")
                _state.update { it.copy(phase = Phase.FAILED, headline = "Update stopped: ${e.message}. Nothing was installed; the machine keeps its current version.") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = Phase.IDLE, headline = "Cancelled. The machine keeps its current version.") }
    }
}
