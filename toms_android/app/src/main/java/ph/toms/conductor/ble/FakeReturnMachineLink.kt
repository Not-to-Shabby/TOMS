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
 * **Writes.** The phone's writes are built by the same [RmSignedWrites] the real link uses (signed when [trust] holds an active key
 * for the connected machine) and then judged by a model of the hardened firmware (`firmware/PROTOCOL.md` section 8, draft PR #35):
 * [machineKey] is the key the simulated machine holds ([machineGraceKey] the one kept for one step after a replace);
 * a wrong trailer is refused, a missing one is accepted for the plain writes, and `05` needs a valid trailer once the machine has a
 * key. [linkAuthenticated] false models a pairing that is not authenticated: the machine refuses `05`, `06`, `07` and `08`.
 * Every write and the machine's verdict on it are in [writes]. This is a reading of the spec, not a machine.
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

    /** The phone's trust store, used to pick the key the phone signs its writes with. Null: writes are unsigned. */
    var trust: RmMachineTrust? = null

    /** The key the simulated machine holds (null: it has none), and the key it still accepts for one step after a replace. */
    var machineKey: ByteArray? = null
    var machineGraceKey: ByteArray? = null

    /** Whether the link is authenticated (section 8.2). False: `05`, `06`, `07`, `08` are refused with status 0xFF. */
    var linkAuthenticated = true

    /** How the machine answers a key replace: normally from its rules; or these raw bytes (an older firmware's echo); or null = silence. */
    var machineKnowsReplace = true
    var replaceReplyRaw: ByteArray? = null
    var machineAnswersReplace = true

    /** Whether the machine would accept an unsigned `05` from a bonded central with an open session (the interim rule; the phone has no session). */
    var interimUnsignedAlarm = false

    data class RecordedWrite(val suffix: Int, val bytes: ByteArray, val accepted: Boolean, val reason: String)

    /** Every write the phone made to a signed characteristic, with what the machine decided. */
    val writes = mutableListOf<RecordedWrite>()

    private val _pairingNotice = MutableStateFlow<RmKeyStatus?>(null)
    override val pairingNotice: StateFlow<RmKeyStatus?> = _pairingNotice.asStateFlow()

    /** What the phone reads about pairing, set by a test through the same tracker the real link uses. */
    val pairing = RmPairingTracker { _pairingNotice.value = it }

    private fun connectedAddress(): String? = (_connectionState.value as? RmConnectionState.Connected)?.deviceAddress

    private fun writeKey(): ByteArray? = trust?.let { RmSignedWrites.writeKey(it, connectedAddress()) }

    /** The firmware's `verify_write`: a trailer that is there must be right; one that is missing is accepted for the plain writes. */
    private fun judgePlainWrite(suffix: Int, bytes: ByteArray, payloadLen: Int) {
        val key = machineKey
        if (key == null) { writes.add(RecordedWrite(suffix, bytes, true, "machine has no key")); return }
        if (bytes.size == payloadLen) { writes.add(RecordedWrite(suffix, bytes, true, "unsigned, accepted and counted")); return }
        val ok = checkTrailer(suffix, bytes, payloadLen)
        writes.add(RecordedWrite(suffix, bytes, ok, if (ok) "signature valid" else "bad signature, refused"))
    }

    /** Valid under the key in force (which ends the grace) or the grace key. A partial trailer is wrong, not absent. */
    private fun checkTrailer(suffix: Int, bytes: ByteArray, payloadLen: Int, endsGrace: Boolean = true): Boolean {
        if (bytes.size != payloadLen + RmSignature.TRAILER_LEN) return false
        val payload = bytes.copyOfRange(0, payloadLen)
        val trailer = bytes.copyOfRange(payloadLen, bytes.size)
        val cur = machineKey
        if (cur != null && RmSignature.verify(cur, suffix, payload, trailer)) {
            if (endsGrace) machineGraceKey = null
            return true
        }
        val grace = machineGraceKey
        return grace != null && RmSignature.verify(grace, suffix, payload, trailer)
    }

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
        val bytes = RmSignedWrites.paidReply(writeKey(), checkSeq, verdict)
        judgePlainWrite(RmSignature.SUFFIX_PAID_REPLY, bytes, 5)
    }

    override fun sendStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>) {
        sentStateUpdates.add(SentStateUpdate(tableVersion, snapshot, entries))
        val plain = RmProtocol.encodeStateUpdate(tableVersion, snapshot, entries)
        judgePlainWrite(RmSignature.SUFFIX_STATE_UPDATE, RmSignedWrites.stateUpdate(writeKey(), tableVersion, snapshot, entries), plain.size)
    }

    override fun sendTimeSync(nowMillis: Long) {
        sentTimeSyncs.add(SentTimeSync(nowMillis))
        judgePlainWrite(RmSignature.SUFFIX_TIME_SYNC, RmSignedWrites.timeSync(writeKey(), nowMillis), 6)
    }

    override fun sendAlarmClear() {
        alarmClearCalls++
        val bytes = RmSignedWrites.alarmClear(writeKey())
        val key = machineKey
        val accepted: Boolean
        val reason: String
        when {
            !linkAuthenticated -> { accepted = false; reason = "link not authenticated (0x85 0 0 0xFF)"; pairing.onLinkNotAuthenticated() }
            key == null -> { accepted = true; reason = "machine has no key" }
            bytes.size == 2 -> {
                accepted = interimUnsignedAlarm
                reason = if (accepted) "unsigned, interim rule" else "unsigned, refused once a key exists"
            }
            else -> {
                accepted = checkTrailer(RmSignature.SUFFIX_DIAG, bytes, 2)
                reason = if (accepted) "signature valid" else "bad signature, refused"
            }
        }
        writes.add(RecordedWrite(RmSignature.SUFFIX_DIAG, bytes, accepted, reason))
        if (accepted) alarmClearsAccepted++
    }

    /** How many `05` writes the simulated machine actually acted on (the alarm went quiet). */
    var alarmClearsAccepted = 0

    override fun sendReadAck(upToReadSeq: Long) {
        sentReadAcks.add(SentReadAck(upToReadSeq))
        judgePlainWrite(RmSignature.SUFFIX_ACK, RmSignedWrites.readAck(writeKey(), upToReadSeq), 5)
    }

    /** How many replace writes the phone made, and the bytes of the last one (to check its layout). */
    var replaceWrites = 0
    var lastReplaceWrite: ByteArray? = null

    override suspend fun sendKeyReplace(currentKey: ByteArray, newKey: ByteArray): Int? {
        replaceWrites++
        val bytes = RmProtocol.encodeKeyReplace(currentKey, newKey)
        lastReplaceWrite = bytes
        if (!machineKnowsReplace) {
            // An older firmware: no command 08. It echoes [0x88, byte 1, 0, 0] (or nacks 0x88 .. 0xFF); never a real status.
            writes.add(RecordedWrite(RmSignature.SUFFIX_DIAG, bytes, false, "command 08 not known"))
            return decodeRaw(replaceReplyRaw ?: byteArrayOf(0x88.toByte(), newKey[0], 0, 0))
        }
        val status = when {
            !linkAuthenticated -> RmProtocol.REPLACE_NOT_AUTHENTICATED
            machineKey == null -> RmProtocol.REPLACE_NO_KEY
            bytes.size != 41 || bytes[0].toInt() != RmProtocol.CMD_KEY_REPLACE -> RmProtocol.REPLACE_MALFORMED
            else -> {
                // The signature is checked on a copy: a replace is not a "use" of the key that ends a grace.
                val payload = bytes.copyOfRange(0, 33)
                val trailer = bytes.copyOfRange(33, 41)
                val cur = machineKey!!
                val underCurrent = RmSignature.verify(cur, RmSignature.SUFFIX_DIAG, payload, trailer)
                val underGrace = machineGraceKey?.let { RmSignature.verify(it, RmSignature.SUFFIX_DIAG, payload, trailer) } == true
                if (!underCurrent && !underGrace) {
                    RmProtocol.REPLACE_BAD_SIGNATURE
                } else {
                    if (underCurrent) machineGraceKey = cur // the key replaced becomes the grace key; a retry keeps the old grace key
                    machineKey = newKey.copyOf()
                    RmProtocol.REPLACE_OK
                }
            }
        }
        writes.add(RecordedWrite(RmSignature.SUFFIX_DIAG, bytes, status == RmProtocol.REPLACE_OK, "replace status $status"))
        if (!machineAnswersReplace) return null
        val reply = replaceReplyRaw
            ?: byteArrayOf(0x88.toByte(), status.toByte(), (if (machineGraceKey != null) 1 else 0).toByte(), 0)
        return decodeRaw(reply)
    }

    private fun decodeRaw(reply: ByteArray): Int? {
        val decoded = RmProtocol.decodeReplaceReply(reply) ?: return null
        if (decoded.status == RmProtocol.REPLACE_NOT_AUTHENTICATED) pairing.onLinkNotAuthenticated()
        return decoded.status
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
        pairing.onDisconnected()
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
        val key = signingKey ?: machineKey ?: return payload
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
