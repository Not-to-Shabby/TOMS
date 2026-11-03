package ph.toms.conductor.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UplinkState {
    data object Offline : UplinkState
    data object Connecting : UplinkState
    data class Online(val isCellular: Boolean) : UplinkState

    val isOnline: Boolean get() = this is Online
}

/**
 * Monitors device cellular/Wi-Fi connectivity with anti-flapping hysteresis.
 * When exiting a dead zone, waits for connection stability before transitioning to [UplinkState.Online]
 * and emitting an uplink restored event to automatically drain the offline outbox.
 */
interface UplinkManager {
    val state: StateFlow<UplinkState>

    /** Emits Unit whenever a stable online connection is established after being offline/connecting. */
    val uplinkRestored: SharedFlow<Unit>

    fun startMonitoring()
    fun stopMonitoring()
}

/**
 * Pure state coordinator with debounce hysteresis.
 * Extracted so state transitions, flapping cancellation, and auto-drain triggers can be unit tested
 * without depending on Android system framework classes.
 */
class UplinkCoordinator(
    private val scope: CoroutineScope,
    private val hysteresisMillis: Long = DEFAULT_HYSTERESIS_MILLIS,
) {
    private val _state = MutableStateFlow<UplinkState>(UplinkState.Offline)
    val state: StateFlow<UplinkState> = _state.asStateFlow()

    private val _uplinkRestored = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val uplinkRestored: SharedFlow<Unit> = _uplinkRestored.asSharedFlow()

    private var debounceJob: Job? = null
    private var pendingCellular: Boolean = false

    fun onNetworkAvailable(isCellular: Boolean, isValidated: Boolean) {
        if (!isValidated) {
            // Network connected at link layer but not yet validated for Internet access
            onLinkConnected(isCellular)
            return
        }
        onNetworkValidated(isCellular)
    }

    fun onLinkConnected(isCellular: Boolean) {
        pendingCellular = isCellular
        if (_state.value is UplinkState.Offline) {
            _state.value = UplinkState.Connecting
        }
    }

    fun onNetworkValidated(isCellular: Boolean) {
        pendingCellular = isCellular
        when (val current = _state.value) {
            is UplinkState.Online -> {
                // Already online: update cellular flag if network transport changed (e.g. WiFi -> 4G)
                if (current.isCellular != isCellular) {
                    _state.value = UplinkState.Online(isCellular)
                }
            }
            is UplinkState.Connecting -> {
                // Start or maintain hysteresis timer
                if (debounceJob?.isActive != true) {
                    scheduleOnlineTransition(isCellular)
                }
            }
            is UplinkState.Offline -> {
                _state.value = UplinkState.Connecting
                scheduleOnlineTransition(isCellular)
            }
        }
    }

    private fun scheduleOnlineTransition(isCellular: Boolean) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(hysteresisMillis)
            _state.value = UplinkState.Online(pendingCellular)
            _uplinkRestored.tryEmit(Unit)
        }
    }

    fun onNetworkLost() {
        debounceJob?.cancel()
        debounceJob = null
        _state.value = UplinkState.Offline
    }

    companion object {
        const val DEFAULT_HYSTERESIS_MILLIS = 3500L
    }
}
