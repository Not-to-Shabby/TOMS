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
 * **Layouts.** The fake also plays the machine's side of firmware/PROTOCOL.md section 7: [machineSupportsLayout2] says whether
 * it answers diag `07 02` with `87 00 02 02`; [replyToLayoutSelect] is what the phone's write gets. A fake that is silent leaves
 * the phone on layout 1, byte for byte like an older machine. Frames it emits are encoded in the layout in force, so the
 * gate's framing and the decoders meet exactly the bytes the real machine would send.
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

    /** Shared with the real negotiation class, so the fake switches layouts by the same rule as the phone's link. */
    val negotiation = RmLayoutNegotiation()
    override val layout: StateFlow<RmProtocol.Layout> = negotiation.layout

    /** Whether the simulated machine knows layout 2. False models an older firmware: it never answers `07 02`. */
    var machineSupportsLayout2 = true

    /** Every diag write the phone made to choose a layout, in order (the bytes of `07 02`). */
    val layoutWrites = mutableListOf<List<Int>>()

    /**
     * The machine's reply to a layout select. The default is the firmware's own `87 00 02 02`. A test sets it to model
     * status 1 or 2 (version unchanged), or null for silence.
     */
    var replyToLayoutSelect: ((requested: Int, inForce: Int) -> ByteArray?)? = null

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

    /**
     * What the phone does after it connects: write `07 02` and wait for the machine's reply. The fake answers like the firmware
     * (it switches to layout 2 only for a machine that supports it) and feeds the answer to the same negotiation class the real
     * link uses. Returns the layout in force afterwards.
     */
    suspend fun negotiateLayout(): RmProtocol.Layout {
        val unanswered = negotiation
        return unanswered.negotiate { bytes ->
            layoutWrites.add(bytes.map { it.toInt() and 0xFF })
            val requested = bytes[1].toInt() and 0xFF
            if (machineSupportsLayout2) {
                val inForce = if (requested == RmProtocol.Layout.V2.version) RmProtocol.Layout.V2.version else RmProtocol.Layout.V1.version
                val reply = replyToLayoutSelect?.invoke(requested, inForce)
                    ?: byteArrayOf(RmProtocol.DIAG_WIRE_REPLY.toByte(), 0, inForce.toByte(), 2)
                negotiation.onDiagNotify(reply)
            } else {
                replyToLayoutSelect?.invoke(requested, 1)?.let { negotiation.onDiagNotify(it) }
            }
            true
        }
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
        // The machine falls back to layout 1 on a disconnect, and so does the phone.
        negotiation.reset()
        _connectionState.value = RmConnectionState.Disconnected
    }

    suspend fun emitPaidCheck(checkSeq: Long, uidHex: String) {
        val clean = uidHex.filter { it.isLetterOrDigit() }
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        _events.emit(RmEvent.PaidCheckReceived(checkSeq, bytes, uidHex, negotiation.layout.value))
    }

    /** A card_read as the machine sends it: signed when it has a key, and gated before it is believed. */
    suspend fun emitCardRead(read: RmProtocol.CardRead) {
        val suffix = RmSignature.SUFFIX_CARD_READ
        val inForce = negotiation.layout.value
        val bytes = signOrPlain(suffix, RmProtocol.encodeCardRead(read, inForce))
        val accepted = acceptThroughGate(suffix, bytes) ?: return
        val decoded = if (gate != null) RmProtocol.decodeCardReadStrict(accepted, inForce) else RmProtocol.decodeCardRead(accepted)
        decoded?.let { _events.emit(RmEvent.CardReadReceived(it)) }
    }

    suspend fun emitHeartbeat(heartbeat: RmProtocol.Heartbeat) {
        val suffix = RmSignature.SUFFIX_HEARTBEAT
        val inForce = negotiation.layout.value
        val bytes = signOrPlain(suffix, RmProtocol.encodeHeartbeat(heartbeat, inForce))
        val accepted = acceptThroughGate(suffix, bytes) ?: return
        val decoded = if (gate != null) RmProtocol.decodeHeartbeatStrict(accepted, inForce) else RmProtocol.decodeHeartbeat(accepted)
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
