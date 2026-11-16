package ph.toms.conductor.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-memory link behind view model and intake tests. It can simulate everything the real link
 * does at the event level, including the machine-side behaviours this PR syncs with: signed notifies,
 * bad signatures, resent reads, stored reads flushed on reconnect (buffered_reads), a full machine
 * log, and unverified outcomes (a paid check the phone answered late or never).
 *
 * When [gate] is installed, the emitted card_read/heartbeat/rm_ack bytes go through the same
 * verification pipeline as the real link: signed with [signingKey] (or unsigned without one), a
 * corrupted trailer with [corruptNextSignature], and anything the gate refuses is dropped and
 * counted instead of emitted. Without a gate the parsed event goes straight out, which is what the
 * older view model tests expect.
 */
class FakeReturnMachineLink : ReturnMachineLink {

    private val _connectionState = MutableStateFlow<RmConnectionState>(RmConnectionState.Disconnected)
    override val connectionState: StateFlow<RmConnectionState> = _connectionState.asStateFlow()

    private val _lastHeartbeat = MutableStateFlow<RmProtocol.Heartbeat?>(null)
    override val lastHeartbeat: StateFlow<RmProtocol.Heartbeat?> = _lastHeartbeat.asStateFlow()

    private val _events = MutableSharedFlow<RmEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RmEvent> = _events.asSharedFlow()

    private val _droppedCount = MutableStateFlow(0)
    override val droppedCount: StateFlow<Int> = _droppedCount.asStateFlow()

    /** The verification pipeline for emitted bytes, built by the test over the same trust it wires elsewhere. */
    var gate: RmNotifyGate? = null

    /** When set, card_read/heartbeat/rm_ack are signed with this key; null sends them unsigned. */
    var signingKey: ByteArray? = null

    /** Flips one bit of the next emitted trailer, the way a corrupt frame arrives. */
    var corruptNextSignature = false

    /** The machine reports buffered_reads from its flash log until changed: > 0 means reads are stored reads. */
    var bufferedReads = 0

    fun simulateFullLog(pending: Int = 128) {
        bufferedReads = pending
    }

    data class SentPaidReply(val checkSeq: Long, val verdict: Int)
    data class SentStateUpdate(val tableVersion: Long, val snapshot: Boolean, val entries: List<RmProtocol.StateEntry>)
    data class SentTimeSync(val epochMs: Long)
    data class SentReadAck(val upToReadSeq: Long)

    val sentPaidReplies = mutableListOf<SentPaidReply>()
    val sentStateUpdates = mutableListOf<SentStateUpdate>()
    val sentTimeSyncs = mutableListOf<SentTimeSync>()
    val sentReadAcks = mutableListOf<SentReadAck>()
    val provisionedKeys = mutableListOf<ByteArray>()
    var alarmClearCalls = 0

    /** What sendProvisionKey answers with, or null to make the phone time out. */
    var nextProvisionStatus: Int? = RmProtocol.PROVISION_STORED

    override fun sendPaidReply(checkSeq: Long, verdict: Int) {
        sentPaidReplies.add(SentPaidReply(checkSeq, verdict))
    }

    override fun sendStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>) {
        sentStateUpdates.add(SentStateUpdate(tableVersion, snapshot, entries))
    }

    override fun sendTimeSync(nowMillis: Long) {
        sentTimeSyncs.add(SentTimeSync(nowMillis))
    }

    override fun sendAlarmClear() {
        alarmClearCalls++
    }

    override fun sendReadAck(upToReadSeq: Long) {
        sentReadAcks.add(SentReadAck(upToReadSeq))
    }

    override suspend fun sendProvisionKey(key: ByteArray): Int? {
        provisionedKeys.add(key.copyOf())
        return nextProvisionStatus
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

    // ---- test simulation: the machine side

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

    /** A card_read as the machine sends it: signed when it has a key, and gated before it is believed. */
    suspend fun emitCardRead(read: RmProtocol.CardRead) {
        val suffix = RmSignature.SUFFIX_CARD_READ
        val bytes = signOrPlain(suffix, RmProtocol.encodeCardRead(read))
        val accepted = acceptThroughGate(suffix, bytes) ?: return
        val decoded = if (gate != null) RmProtocol.decodeCardReadStrict(accepted) else RmProtocol.decodeCardRead(accepted)
        decoded?.let { _events.emit(RmEvent.CardReadReceived(it)) }
    }

    suspend fun emitHeartbeat(heartbeat: RmProtocol.Heartbeat) {
        val suffix = RmSignature.SUFFIX_HEARTBEAT
        val bytes = signOrPlain(suffix, RmProtocol.encodeHeartbeat(heartbeat))
        val accepted = acceptThroughGate(suffix, bytes) ?: return
        val decoded = if (gate != null) RmProtocol.decodeHeartbeatStrict(accepted) else RmProtocol.decodeHeartbeat(accepted)
        decoded?.let {
            _lastHeartbeat.value = it
            _events.emit(RmEvent.HeartbeatReceived(it))
        }
    }

    suspend fun emitRmAck(kind: Int, tableVersion: Long) {
        val suffix = RmSignature.SUFFIX_RM_ACK
        val bytes = signOrPlain(suffix, byteArrayOf(kind.toByte()) + ByteBufferFor(tableVersion))
        val accepted = acceptThroughGate(suffix, bytes) ?: return
        val decoded = if (gate != null) RmProtocol.decodeRmAckStrict(accepted) else RmProtocol.decodeRmAck(accepted)
        decoded?.let { _events.emit(RmEvent.RmAckReceived(it)) }
    }

    private fun signOrPlain(suffix: Int, payload: ByteArray): ByteArray {
        val key = signingKey ?: return payload
        val signed = RmSignature.sign(key, suffix, payload)
        if (!corruptNextSignature) return signed
        corruptNextSignature = false
        return signed.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
    }

    private suspend fun acceptThroughGate(suffix: Int, bytes: ByteArray): ByteArray? {
        val g = gate ?: return bytes
        return g.accept(suffix, bytes)
    }

    private fun ByteBufferFor(value: Long): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt((value and 0xFFFFFFFFL).toInt())
        return bb.array()
    }
}
