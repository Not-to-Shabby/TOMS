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
import ph.toms.conductor.data.db.ParkedEvent
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.sync.NO_SERVER_MESSAGE
import ph.toms.conductor.sync.OutboxFlusher

/** Everything about talking to the server: who this phone is, what is waiting to go, and how the last upload went. */
data class SyncState(
    val deviceId: String = "",
    val serverUrl: String = "",
    val isEnrolled: Boolean = false,
    val pendingCount: Int = 0,
    /** Events the server refused. Kept for whoever fixes the cause, and not retried. */
    val parkedCount: Int = 0,
    /** The refused events with their reasons, newest first (a short list, not all of them). */
    val parked: List<ParkedEvent> = emptyList(),
    val isUploading: Boolean = false,
    val uploadMessage: String? = null,
    /** Set when the token could not be stored safely. Null when all is well. */
    val tokenWarning: String? = null,
) {
    /** A release build starts with no server address. Until one is set nothing is uploaded or synced. */
    val needsServer: Boolean get() = serverUrl.isBlank()
}

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
            tokenWarning = deviceConfig.storageWarning.value,
        ),
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        viewModelScope.launch { trips.pendingCount().collect { n -> _state.update { it.copy(pendingCount = n) } } }
        viewModelScope.launch { trips.parkedCount().collect { n -> _state.update { it.copy(parkedCount = n) } } }
        viewModelScope.launch { trips.parkedEvents().collect { list -> _state.update { it.copy(parked = list) } } }
        viewModelScope.launch { deviceConfig.serverUrl.collect { u -> _state.update { it.copy(serverUrl = u) } } }
        viewModelScope.launch { deviceConfig.storageWarning.collect { w -> _state.update { it.copy(tokenWarning = w) } } }
        viewModelScope.launch {
            deviceConfig.deviceToken.collect { tok -> _state.update { it.copy(isEnrolled = !tok.isNullOrBlank()) } }
        }
    }

    /** Sends what is waiting. [onFlushed] runs when the upload attempt is over, for work that follows it, such as a card list refresh. */
    fun flushNow(onFlushed: () -> Unit = {}) {
        if (_state.value.isUploading) return
        // No server address: nothing is tried, so the waiting events are not counted as failed attempts either.
        if (_state.value.serverUrl.isBlank()) {
            _state.update { it.copy(uploadMessage = NO_SERVER_MESSAGE) }
            return
        }
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

    /**
     * Puts the refused events back in the queue and sends them. If the server refuses them again they are parked
     * again with the new reason, so retrying never loops: each press is one try.
     */
    fun retryRefused(onFlushed: () -> Unit = {}) {
        if (_state.value.isUploading) return
        if (_state.value.needsServer) {
            _state.update { it.copy(uploadMessage = NO_SERVER_MESSAGE) }
            return
        }
        viewModelScope.launch {
            trips.retryParked()
            flushNow(onFlushed)
        }
    }

    fun updateServerUrl(url: String) = deviceConfig.setServerUrl(url)
    fun updateDeviceToken(token: String?) = deviceConfig.setDeviceToken(token)
}
