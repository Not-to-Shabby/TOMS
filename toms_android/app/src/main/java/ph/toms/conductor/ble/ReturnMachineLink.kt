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
    /**
     * [layout] is the layout in force when the check arrived on the Bluetooth thread. The machine sends its layout reply before
     * any later notify, so this is exactly ordered; reading the layout later, from the view model's thread, could be a step
     * behind. On layout 2 the machine reports how the check ended in the card_read that follows, so the phone does not guess.
     */
    data class PaidCheckReceived(
        val checkSeq: Long,
        val uid: ByteArray,
        val uidHex: String,
        val layout: RmProtocol.Layout = RmProtocol.Layout.V1,
    ) : RmEvent
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
 * - Read acks (0x08 kind 2) and key provisioning (diag 06), per firmware/PROTOCOL.md section 6
 *
 * Signed notifies (card_read, heartbeat, rm_ack) are verified against the machine's key before they
 * reach [events]; what failed verification is dropped, logged without key or payload, and counted in
 * [droppedCount].
 */
interface ReturnMachineLink {
    val connectionState: StateFlow<RmConnectionState>
    val lastHeartbeat: StateFlow<RmProtocol.Heartbeat?>
    val events: SharedFlow<RmEvent>

    /** Notifies dropped by the verification pipeline since the app started (bad size, bad or missing trailer). */
    val droppedCount: StateFlow<Int>

    /**
     * The message layout in force on the current connection (firmware/PROTOCOL.md section 7). Layout 1 until the phone has
     * written diag `07 02` and the machine confirmed it, and again after every disconnect.
     */
    val layout: StateFlow<RmProtocol.Layout>

    fun sendPaidReply(checkSeq: Long, verdict: Int)
    fun sendStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>)
    fun sendTimeSync(nowMillis: Long = System.currentTimeMillis())
    fun sendAlarmClear()

    /**
     * Tells the machine every read up to [upToReadSeq] is durably stored on the phone
     * (`ack` kind 2). The caller must have committed the storage first.
     */
    fun sendReadAck(upToReadSeq: Long)

    /**
     * Sends the per-machine signing key over the diag characteristic (command 06, encrypted link) and
     * waits for the machine's status reply. Returns the status byte, or null when the machine did not
     * answer in time. Never logs the key.
     */
    suspend fun sendProvisionKey(key: ByteArray): Int?

    fun startScanning()
    fun stopScanning()
    fun disconnect()
}
