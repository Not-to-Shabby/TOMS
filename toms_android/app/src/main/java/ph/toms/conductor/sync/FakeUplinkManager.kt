package ph.toms.conductor.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeUplinkManager(
    initialState: UplinkState = UplinkState.Online(isCellular = true),
) : UplinkManager {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<UplinkState> = _state.asStateFlow()

    private val _uplinkRestored = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val uplinkRestored: SharedFlow<Unit> = _uplinkRestored.asSharedFlow()

    var isMonitoring = false
        private set

    override fun startMonitoring() {
        isMonitoring = true
    }

    override fun stopMonitoring() {
        isMonitoring = false
    }

    fun setState(newState: UplinkState) {
        val previous = _state.value
        _state.value = newState
        if (previous !is UplinkState.Online && newState is UplinkState.Online) {
            _uplinkRestored.tryEmit(Unit)
        }
    }

    fun simulateRestored(isCellular: Boolean = true) {
        setState(UplinkState.Online(isCellular))
    }

    fun simulateOffline() {
        setState(UplinkState.Offline)
    }

    fun simulateConnecting() {
        setState(UplinkState.Connecting)
    }
}
