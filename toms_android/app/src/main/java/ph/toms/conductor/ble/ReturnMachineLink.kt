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

enum class ForgetPairingResult {
    /** Android removed the pairing; the link pairs afresh on the next connection. */
    REMOVED,

    /** Android did not remove it (the system API is unavailable or refused); it has to be forgotten in Bluetooth settings. */
    REFUSED,

    /** There is no old pairing left to remove (already gone, or no machine was ever connected). */
    NOT_NEEDED,
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
    /**
     * `05 00`, with the trailer once the machine has a key (firmware/PROTOCOL.md section 8.3): without it the machine refuses to
     * silence the alarm.
     */
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

    /**
     * Replaces the machine's signing key (diag `08`, section 8.4): [newKey], signed under [currentKey], the key the machine holds.
     * Returns the machine's status byte (`RmProtocol.REPLACE_*`), [RmProtocol.REPLACE_UNRECOGNISED] when a firmware that does not
     * know the command answered, or null when no answer came in time. Never logs either key.
     */
    suspend fun sendKeyReplace(currentKey: ByteArray, newKey: ByteArray): Int?

    /**
     * What the conductor should read about how this phone is paired with the machine (section 8.1), or null when nothing is wrong:
     * a refused or weak pairing, or a write the machine refused for lack of an authenticated link.
     */
    val pairingNotice: StateFlow<RmKeyStatus?>

    /**
     * True while the phone holds a pairing the machine has deleted (firmware/PROTOCOL.md section 8.7): connections keep ending before
     * they are usable although Android says the machine is bonded. The screen offers [forgetStalePairing] then.
     */
    val staleBond: StateFlow<Boolean>

    /**
     * Asks Android to remove the phone's old pairing with the machine, so the next connection pairs afresh. The method that does this
     * (`BluetoothDevice.removeBond`) is a system API, not part of the public SDK: it is tried, and when Android refuses it the result
     * is [ForgetPairingResult.REFUSED] and the notice tells the conductor to do it in Bluetooth settings. Never throws.
     */
    suspend fun forgetStalePairing(): ForgetPairingResult

    fun startScanning()
    fun stopScanning()
    fun disconnect()
}
