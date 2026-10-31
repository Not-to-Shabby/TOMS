package ph.toms.conductor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.sync.OutboxFlusher

/** Everything about talking to the server: who this phone is, what is waiting to go, and how the last upload went. */
data class SyncState(
    val deviceId: String = "",
    val serverUrl: String = "",
    val isEnrolled: Boolean = false,
    val pendingCount: Int = 0,
    /** Events the server refused. Kept for whoever fixes the cause, and not retried. */
    val parkedCount: Int = 0,
    val isUploading: Boolean = false,
    val uploadMessage: String? = null,
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val trips: TripRepository,
    private val deviceConfig: DeviceConfigStore,
    private val flusher: OutboxFlusher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SyncState(
            deviceId = deviceConfig.deviceId,
            serverUrl = deviceConfig.serverUrl.value,
            isEnrolled = !deviceConfig.deviceToken.value.isNullOrBlank(),
        ),
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        viewModelScope.launch { trips.pendingCount().collect { n -> _state.update { it.copy(pendingCount = n) } } }
        viewModelScope.launch { trips.parkedCount().collect { n -> _state.update { it.copy(parkedCount = n) } } }
        viewModelScope.launch { deviceConfig.serverUrl.collect { u -> _state.update { it.copy(serverUrl = u) } } }
        viewModelScope.launch {
            deviceConfig.deviceToken.collect { tok -> _state.update { it.copy(isEnrolled = !tok.isNullOrBlank()) } }
        }
    }

    /** Sends what is waiting. [onFlushed] runs when the upload attempt is over, for work that follows it, such as a card list refresh. */
    fun flushNow(onFlushed: () -> Unit = {}) {
        if (_state.value.isUploading) return
        _state.update { it.copy(isUploading = true, uploadMessage = null) }
        viewModelScope.launch {
            val report = flusher.flush()
            onFlushed()
            val msg = if (report.failure != null) {
                "Upload failed: ${report.failure}"
            } else if (report.parked > 0) {
                "Uploaded ${report.delivered} event(s). ${report.parked} refused by the server and set aside"
            } else {
                "Uploaded ${report.delivered} event(s)"
            }
            _state.update { it.copy(isUploading = false, uploadMessage = msg) }
        }
    }

    fun updateServerUrl(url: String) = deviceConfig.setServerUrl(url)
    fun updateDeviceToken(token: String?) = deviceConfig.setDeviceToken(token)
}
