package ph.toms.conductor.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import ph.toms.conductor.domain.CardState

/**
 * Byte-level encoders and decoders for the Return Machine (RM) BLE protocol.
 * Conforms strictly to firmware/PROTOCOL.md and firmware/lib/toms_core/include/toms/codec.h.
 *
 * Implements only the necessary operational protocol:
 * - device_info (0x09)
 * - time_sync (0x06)
 * - card_read (0x01)
 * - paid_check (0x03) & paid_reply (0x04)
 * - state_update (0x02) & rm_ack (0x0A)
 * - heartbeat (0x05)
 * - ack (0x08) kind 2, the read ack (section 6 of the firmware PROTOCOL.md)
 * - diag (0x0E) command 0x06, key provisioning, and its 0x86 reply
 *
 * card_read, heartbeat and rm_ack notifies carry an 8-byte signature trailer once the machine has a key;
 * [RmSignature] checks it and [frame] splits it off.
 */
object RmProtocol {

    private const val BASE_HI = "544f4d53-5245-5455-524e-414348494e"
    fun uuid(suffix: Int): UUID = UUID.fromString(BASE_HI + "%02x".format(suffix))

    val SERVICE_UUID: UUID = uuid(0x45)
    val CHAR_CARD_READ: UUID = uuid(0x01)
    val CHAR_STATE_UPDATE: UUID = uuid(0x02)
    val CHAR_PAID_CHECK: UUID = uuid(0x03)
    val CHAR_PAID_REPLY: UUID = uuid(0x04)
    val CHAR_HEARTBEAT: UUID = uuid(0x05)
    val CHAR_TIME_SYNC: UUID = uuid(0x06)
    val CHAR_ACK: UUID = uuid(0x08)
    val CHAR_DEVICE_INFO: UUID = uuid(0x09)
    val CHAR_RM_ACK: UUID = uuid(0x0A)
    val CHAR_DIAG: UUID = uuid(0x0E)

    /** The last UUID byte, which is what the signature covers. */
    fun suffixOf(uuid: UUID): Int = (uuid.leastSignificantBits and 0xFF).toInt()

    // ack (0x08) kinds
    const val ACK_KIND_STATE = 1
    const val ACK_KIND_READ = 2

    // diag command and reply for key provisioning (firmware/PROTOCOL.md section 3.7)
    const val CMD_PROVISION_KEY = 0x06
    const val DIAG_PROVISION_REPLY = 0x86

    // Status byte of the provisioning reply
    const val PROVISION_STORED = 0
    const val PROVISION_ALREADY_SET = 1
    const val PROVISION_FLASH_ERROR = 2
    const val PROVISION_REFUSED = 0xFF

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEVICE_NAME_PREFIX = "TOMS-RM-"

    // Verdict codes for paid_reply
    const val VERDICT_OK = 0
    const val VERDICT_UNPAID = 1
    const val VERDICT_UNKNOWN = 2
    const val VERDICT_ALREADY_RETURNED = 3

    // Diagnostic & control command codes (firmware/PROTOCOL.md section 3.7)
    const val CMD_CLEAR_ALARM = 0x05

    // Card status codes for state_update (firmware enum toms_status)
    const val STATUS_AVAILABLE = 0
    const val STATUS_ASSIGNED_UNPAID = 1
    const val STATUS_ASSIGNED_PAID = 2
    const val STATUS_RETURNED = 3
    const val STATUS_RETURNED_UNPAID = 4
    const val STATUS_LOST = 5

    fun cardStateToStatus(state: CardState): Int = when (state) {
        CardState.AVAILABLE -> STATUS_AVAILABLE
        CardState.ASSIGNED_UNPAID -> STATUS_ASSIGNED_UNPAID
        CardState.ASSIGNED_PAID -> STATUS_ASSIGNED_PAID
        CardState.RETURNED -> STATUS_RETURNED
        CardState.RETURNED_UNPAID -> STATUS_RETURNED_UNPAID
        CardState.LOST -> STATUS_LOST
    }

    data class PaidCheck(val checkSeq: Long, val uid: ByteArray, val uidHex: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PaidCheck) return false
            return checkSeq == other.checkSeq && uid.contentEquals(other.uid) && uidHex == other.uidHex
        }

        override fun hashCode(): Int {
            var result = checkSeq.hashCode()
            result = 31 * result + uid.contentHashCode()
            result = 31 * result + uidHex.hashCode()
            return result
        }
    }

    data class CardRead(
        val readSeq: Long,
        val timestamp: Long,
        val bootMs: Long,
        val uid: ByteArray,
        val uidHex: String,
        val atype: Int,
        val stateCached: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CardRead) return false
            return readSeq == other.readSeq && uid.contentEquals(other.uid)
        }

        override fun hashCode(): Int = readSeq.hashCode()
    }

    data class Heartbeat(
        val uptimeS: Long,
        val batteryPct: Int,
        val batteryMv: Int,
        val nfcStatus: Int,
        val stateVersion: Long,
        val bufferedReads: Int,
        val resetReason: Int,
    ) {
        val isOnUsb: Boolean get() = batteryPct == 0xFF
    }

    data class DeviceInfo(
        val protocolVersion: Int,
        val fwMajor: Int,
        val fwMinor: Int,
        val fwPatch: Int,
        val devMode: Boolean,
        val valuesSigned: Boolean,
        val maxStateEntries: Int,
    )

    data class RmAck(val kind: Int, val tableVersion: Long)

    data class StateEntry(val uidHex: String, val status: Int)

    // Decoders

    fun decodePaidCheck(data: ByteArray): PaidCheck? {
        if (data.size < 5) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val checkSeq = bb.int.toLong() and 0xFFFFFFFFL
        val uidLen = bb.get().toInt() and 0xFF
        if (uidLen < 1 || uidLen > 10 || data.size < 5 + uidLen) return null
        val uid = ByteArray(uidLen)
        bb.get(uid)
        val uidHex = uid.joinToString(":") { "%02X".format(it) }
        return PaidCheck(checkSeq, uid, uidHex)
    }

    fun decodeCardRead(data: ByteArray): CardRead? {
        if (data.size < 13) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val readSeq = bb.int.toLong() and 0xFFFFFFFFL
        val timestamp = bb.int.toLong() and 0xFFFFFFFFL
        val bootMs = bb.int.toLong() and 0xFFFFFFFFL
        val uidLen = bb.get().toInt() and 0xFF
        if (uidLen < 1 || uidLen > 10 || data.size < 13 + uidLen) return null
        val uid = ByteArray(uidLen)
        bb.get(uid)
        val atype = if (bb.hasRemaining()) bb.get().toInt() and 0xFF else 0
        val stateCached = if (bb.hasRemaining()) bb.get().toInt() and 0xFF else 0xFF
        val uidHex = uid.joinToString(":") { "%02X".format(it) }
        return CardRead(readSeq, timestamp, bootMs, uid, uidHex, atype, stateCached)
    }

    fun decodeHeartbeat(data: ByteArray): Heartbeat? {
        if (data.size < 15) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val uptimeS = bb.int.toLong() and 0xFFFFFFFFL
        val batteryPct = bb.get().toInt() and 0xFF
        val batteryMv = bb.short.toInt() and 0xFFFF
        val nfcStatus = bb.get().toInt() and 0xFF
        val stateVersion = bb.int.toLong() and 0xFFFFFFFFL
        val bufferedReads = bb.short.toInt() and 0xFFFF
        val resetReason = bb.get().toInt() and 0xFF
        return Heartbeat(uptimeS, batteryPct, batteryMv, nfcStatus, stateVersion, bufferedReads, resetReason)
    }

    fun decodeDeviceInfo(data: ByteArray): DeviceInfo? {
        if (data.size < 7) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val proto = bb.get().toInt() and 0xFF
        val major = bb.get().toInt() and 0xFF
        val minor = bb.get().toInt() and 0xFF
        val patch = bb.get().toInt() and 0xFF
        val flags = bb.get().toInt() and 0xFF
        val maxEntries = bb.short.toInt() and 0xFFFF
        return DeviceInfo(
            protocolVersion = proto,
            fwMajor = major,
            fwMinor = minor,
            fwPatch = patch,
            devMode = (flags and 0x01) != 0,
            valuesSigned = (flags and 0x02) != 0,
            maxStateEntries = maxEntries,
        )
    }

    fun decodeRmAck(data: ByteArray): RmAck? {
        if (data.size < 5) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val kind = bb.get().toInt() and 0xFF
        val tableVersion = bb.int.toLong() and 0xFFFFFFFFL
        return RmAck(kind, tableVersion)
    }

    // Framing. A signed notify is the exact fixed payload followed by an 8-byte trailer; an unsigned one is
    // the exact payload with nothing after it. The lenient decoders above ignore anything past their fields,
    // which is right for a link that has not been verified yet but would let a corrupt or padded frame through
    // unnoticed. The frame* functions are strict: they report what is there, or null when it cannot be one of
    // the two shapes.

    /** A notify split into the part the machine signed and the trailer behind it (null when unsigned). */
    class Framed(val payload: ByteArray, val trailer: ByteArray?)

    /** Fixed payload length of each signed notify, or null for a characteristic that carries no trailer. */
    private fun fixedLen(suffix: Int, value: ByteArray): Int? = when (suffix) {
        RmSignature.SUFFIX_HEARTBEAT -> 15
        RmSignature.SUFFIX_RM_ACK -> 5
        // card_read is 13 + uid_len + 2: the uid length byte decides, so it must be readable and sane first.
        RmSignature.SUFFIX_CARD_READ -> {
            val uidLen = if (value.size > 12) value[12].toInt() and 0xFF else -1
            if (uidLen in 1..10) 15 + uidLen else null
        }
        else -> null
    }

    /**
     * Splits [value] for the characteristic ending in [suffix]. Null when the length is neither the exact
     * payload nor the payload plus a full trailer: too short, too long, or a partial trailer.
     */
    fun frame(suffix: Int, value: ByteArray): Framed? {
        val len = fixedLen(suffix, value) ?: return null
        return when (value.size) {
            len -> Framed(value, null)
            len + RmSignature.TRAILER_LEN -> Framed(
                value.copyOfRange(0, len),
                value.copyOfRange(len, value.size),
            )
            else -> null
        }
    }

    /** card_read from the exact payload only (no trailer). Same fields as [decodeCardRead], none optional. */
    fun decodeCardReadStrict(payload: ByteArray): CardRead? {
        if (payload.size < 15) return null
        val uidLen = payload[12].toInt() and 0xFF
        if (uidLen < 1 || uidLen > 10 || payload.size != 15 + uidLen) return null
        return decodeCardRead(payload)
    }

    /** heartbeat from the exact 15-byte payload only. */
    fun decodeHeartbeatStrict(payload: ByteArray): Heartbeat? =
        if (payload.size == 15) decodeHeartbeat(payload) else null

    /** rm_ack from the exact 5-byte payload only. */
    fun decodeRmAckStrict(payload: ByteArray): RmAck? =
        if (payload.size == 5) decodeRmAck(payload) else null

    /** The reply to key provisioning on the diag characteristic: [0x86, status, 0, 0]. Null for any other diag notify. */
    fun decodeProvisionReply(data: ByteArray): Int? =
        if (data.size == 4 && (data[0].toInt() and 0xFF) == DIAG_PROVISION_REPLY) data[1].toInt() and 0xFF else null

    // Encoders

    /**
     * The read ack: every stored read up to and including [upToReadSeq] may be freed by the machine
     * (firmware/PROTOCOL.md section 6, `ack` kind 2). [kind, value u32 LE].
     */
    fun encodeReadAck(upToReadSeq: Long): ByteArray {
        val bb = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(ACK_KIND_READ.toByte())
        bb.putInt((upToReadSeq and 0xFFFFFFFFL).toInt())
        return bb.array()
    }

    /**
     * A card_read frame as the machine builds it (used by the in-app fake, and round-trip tested
     * against [decodeCardReadStrict]): 13 fixed bytes, the uid, then atype and state_cached.
     */
    fun encodeCardRead(read: CardRead): ByteArray {
        val uid = parseHexBytes(read.uidHex)
        require(uid.size in 1..10) { "A card uid is 1 to 10 bytes" }
        val bb = ByteBuffer.allocate(15 + uid.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((read.readSeq and 0xFFFFFFFFL).toInt())
        bb.putInt((read.timestamp and 0xFFFFFFFFL).toInt())
        bb.putInt((read.bootMs and 0xFFFFFFFFL).toInt())
        bb.put(uid.size.toByte())
        bb.put(uid)
        bb.put((read.atype and 0xFF).toByte())
        bb.put((read.stateCached and 0xFF).toByte())
        return bb.array()
    }

    /** Key provisioning on the diag characteristic: command 0x06 followed by the 32-byte key. */
    fun encodeProvisionKey(key: ByteArray): ByteArray {
        require(key.size == RmSignature.KEY_LEN) { "The Return Machine key must be ${RmSignature.KEY_LEN} bytes" }
        return byteArrayOf(CMD_PROVISION_KEY.toByte()) + key
    }

    /** A heartbeat frame as the machine builds it (used by the in-app fake; mirrors [decodeHeartbeat]). */
    fun encodeHeartbeat(heartbeat: Heartbeat): ByteArray {
        val bb = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((heartbeat.uptimeS and 0xFFFFFFFFL).toInt())
        bb.put((heartbeat.batteryPct and 0xFF).toByte())
        bb.putShort((heartbeat.batteryMv and 0xFFFF).toShort())
        bb.put((heartbeat.nfcStatus and 0xFF).toByte())
        bb.putInt((heartbeat.stateVersion and 0xFFFFFFFFL).toInt())
        bb.putShort((heartbeat.bufferedReads and 0xFFFF).toShort())
        bb.put((heartbeat.resetReason and 0xFF).toByte())
        return bb.array()
    }

    fun encodePaidReply(checkSeq: Long, verdict: Int): ByteArray {
        val bb = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((checkSeq and 0xFFFFFFFFL).toInt())
        bb.put((verdict and 0xFF).toByte())
        return bb.array()
    }

    fun encodeTimeSync(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val epochSeconds = nowMillis / 1000L
        val remainingMs = (nowMillis % 1000L).toInt()
        val bb = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((epochSeconds and 0xFFFFFFFFL).toInt())
        bb.putShort((remainingMs and 0xFFFF).toShort())
        return bb.array()
    }

    /**
     * Encodes command 0x05 for CHAR_DIAG (0x0E).
     * Firmware rm_ble_handle_diag executes: buzzer_alarm_stop() and display_clear_alarm().
     */
    fun encodeAlarmClear(): ByteArray = byteArrayOf(CMD_CLEAR_ALARM.toByte(), 0x00.toByte())

    fun encodeStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<StateEntry>): ByteArray {
        val count = entries.size.coerceAtMost(128)
        var totalBytes = 6 // table_version(4) + flags(1) + count(1)
        for (i in 0 until count) {
            val uidBytes = parseHexBytes(entries[i].uidHex)
            totalBytes += 1 + uidBytes.size + 1 // uid_len(1) + uid + status(1)
        }

        val bb = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((tableVersion and 0xFFFFFFFFL).toInt())
        bb.put(if (snapshot) 0x01.toByte() else 0x00.toByte())
        bb.put(count.toByte())
        for (i in 0 until count) {
            val uidBytes = parseHexBytes(entries[i].uidHex)
            bb.put(uidBytes.size.toByte())
            bb.put(uidBytes)
            bb.put((entries[i].status and 0xFF).toByte())
        }
        return bb.array()
    }

    private fun parseHexBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isLetterOrDigit() }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
