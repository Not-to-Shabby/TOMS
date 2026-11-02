package ph.toms.conductor.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface RmConnectionState {
    data object Disconnected : RmConnectionState
    data object Scanning : RmConnectionState
    data class Connecting(val deviceName: String, val deviceAddress: String) : RmConnectionState
    data class Connected(val deviceName: String, val deviceAddress: String) : RmConnectionState
}

sealed interface RmEvent {
    data class PaidCheckReceived(val checkSeq: Long, val uid: ByteArray, val uidHex: String) : RmEvent
    data class CardReadReceived(val read: RmProtocol.CardRead) : RmEvent
    data class HeartbeatReceived(val heartbeat: RmProtocol.Heartbeat) : RmEvent
    data class RmAckReceived(val ack: RmProtocol.RmAck) : RmEvent
}

/**
 * Interface abstracting the Bluetooth Low Energy link to the exit Return Machine.
 *
 * Implements only the necessary operational protocol:
 * - Paid check queries & replies
 * - State table updates (snapshot & deltas)
 * - Clock synchronization
 * - Heartbeat & telemetry monitoring
 */
interface ReturnMachineLink {
    val connectionState: StateFlow<RmConnectionState>
    val lastHeartbeat: StateFlow<RmProtocol.Heartbeat?>
    val events: SharedFlow<RmEvent>

    fun sendPaidReply(checkSeq: Long, verdict: Int)
    fun sendStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>)
    fun sendTimeSync(nowMillis: Long = System.currentTimeMillis())

    fun startScanning()
    fun stopScanning()
    fun disconnect()
}
