package ph.toms.conductor.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeReturnMachineLink : ReturnMachineLink {

    private val _connectionState = MutableStateFlow<RmConnectionState>(RmConnectionState.Disconnected)
    override val connectionState: StateFlow<RmConnectionState> = _connectionState.asStateFlow()

    private val _lastHeartbeat = MutableStateFlow<RmProtocol.Heartbeat?>(null)
    override val lastHeartbeat: StateFlow<RmProtocol.Heartbeat?> = _lastHeartbeat.asStateFlow()

    private val _events = MutableSharedFlow<RmEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RmEvent> = _events.asSharedFlow()

    data class SentPaidReply(val checkSeq: Long, val verdict: Int)
    data class SentStateUpdate(val tableVersion: Long, val snapshot: Boolean, val entries: List<RmProtocol.StateEntry>)
    data class SentTimeSync(val epochMs: Long)

    val sentPaidReplies = mutableListOf<SentPaidReply>()
    val sentStateUpdates = mutableListOf<SentStateUpdate>()
    val sentTimeSyncs = mutableListOf<SentTimeSync>()

    override fun sendPaidReply(checkSeq: Long, verdict: Int) {
        sentPaidReplies.add(SentPaidReply(checkSeq, verdict))
    }

    override fun sendStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>) {
        sentStateUpdates.add(SentStateUpdate(tableVersion, snapshot, entries))
    }

    override fun sendTimeSync(nowMillis: Long) {
        sentTimeSyncs.add(SentTimeSync(nowMillis))
    }

    override fun startScanning() {
        if (_connectionState.value is RmConnectionState.Disconnected) {
            _connectionState.value = RmConnectionState.Scanning
        }
    }

    override fun stopScanning() {
        if (_connectionState.value is RmConnectionState.Scanning) {
            _connectionState.value = RmConnectionState.Disconnected
        }
    }

    override fun disconnect() {
        _connectionState.value = RmConnectionState.Disconnected
    }

    // Test simulation helpers

    fun simulateConnected(name: String = "TOMS-RM-3C48", address: String = "AA:BB:CC:DD:EE:FF") {
        _connectionState.value = RmConnectionState.Connected(name, address)
    }

    fun simulateDisconnected() {
        _connectionState.value = RmConnectionState.Disconnected
    }

    suspend fun emitPaidCheck(checkSeq: Long, uidHex: String) {
        val clean = uidHex.filter { it.isLetterOrDigit() }
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        _events.emit(RmEvent.PaidCheckReceived(checkSeq, bytes, uidHex))
    }

    suspend fun emitHeartbeat(heartbeat: RmProtocol.Heartbeat) {
        _lastHeartbeat.value = heartbeat
        _events.emit(RmEvent.HeartbeatReceived(heartbeat))
    }
}
