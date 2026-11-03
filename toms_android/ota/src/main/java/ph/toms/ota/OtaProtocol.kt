package ph.toms.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * Byte layouts of the Return Machine's firmware-update protocol. The firmware side is
 * firmware/lib/toms_core/include/toms/ota.h; the two must agree byte for byte. Pure Kotlin, so
 * everything here is unit tested without a phone.
 *
 *   control write   START: op=1, size u32, sha256[32]   FINISH: op=2   ABORT: op=3   STATUS: op=4
 *   data write      offset u32, payload (1..58 bytes)
 *   control notify  12 bytes: op, status, offset u32, state, ver major, ver minor, ver rev u16, flags
 */
object Ota {
    const val OP_START = 1
    const val OP_FINISH = 2
    const val OP_ABORT = 3
    const val OP_STATUS = 4

    const val EVENT_PROGRESS = 0xA0
    const val EVENT_RESYNC = 0xB0
    const val EVENT_FAILED = 0xF0

    const val CHUNK_MAX = 240
    const val CHUNK_HEADER = 4
    const val ATT_HEADER = 3
    const val REPLY_LEN = 12
    const val START_LEN = 37

    const val SLOT_SIZE = 1344 * 1024
    const val TRAILER_RESERVE = 16 * 1024
    const val MAX_IMAGE = SLOT_SIZE - TRAILER_RESERVE

    private const val BASE_HI = "544f4d53-5245-5455-524e-414348494e"
    fun uuid(suffix: Int): UUID = UUID.fromString(BASE_HI + "%02x".format(suffix))
    val SERVICE: UUID = uuid(0x45)
    val CARD_READ: UUID = uuid(0x01)
    val PAID_CHECK: UUID = uuid(0x03)
    val PAID_REPLY: UUID = uuid(0x04)
    val HEARTBEAT: UUID = uuid(0x05)
    val SIM_CARD: UUID = uuid(0x0B)
    val CONTROL: UUID = uuid(0x0C)
    val DATA: UUID = uuid(0x0D)
    val DIAG: UUID = uuid(0x0E)

    fun simCardFrame(uid: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(1 + uid.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(uid.size.toByte())
        b.put(uid)
        return b.array()
    }

    fun diagCmdFrame(cmd: Int, arg: Int = 0): ByteArray = byteArrayOf(cmd.toByte(), arg.toByte())

    fun parseCardRead(b: ByteArray): CardReadEvent? {
        if (b.size < 13) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val readSeq = bb.int.toLong() and 0xFFFFFFFFL
        val timestamp = bb.int.toLong() and 0xFFFFFFFFL
        val bootMs = bb.int.toLong() and 0xFFFFFFFFL
        val uidLen = bb.get().toInt() and 0xFF
        if (uidLen < 1 || uidLen > 10 || b.size < 13 + uidLen) return null
        val uidBytes = ByteArray(uidLen)
        bb.get(uidBytes)
        val atype = if (bb.hasRemaining()) bb.get().toInt() and 0xFF else 0
        val hex = uidBytes.joinToString(" ") { "%02X".format(it) }
        return CardReadEvent(hex, uidLen, atype, bootMs)
    }

    fun parsePaidCheck(b: ByteArray): PaidCheckEvent? {
        if (b.size < 5) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val checkSeq = bb.int.toLong() and 0xFFFFFFFFL
        val uidLen = bb.get().toInt() and 0xFF
        if (uidLen < 1 || uidLen > 10 || b.size < 5 + uidLen) return null
        val uidBytes = ByteArray(uidLen)
        bb.get(uidBytes)
        val hex = uidBytes.joinToString(" ") { "%02X".format(it) }
        return PaidCheckEvent(checkSeq, hex, uidLen)
    }

    fun paidReplyFrame(checkSeq: Long, verdict: Int): ByteArray {
        val bb = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(checkSeq.toInt())
        bb.put(verdict.toByte())
        return bb.array()
    }

    fun parseHeartbeat(b: ByteArray): HeartbeatEvent? {
        if (b.size < 15) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val uptimeS = bb.int.toLong() and 0xFFFFFFFFL
        val batteryPct = bb.get().toInt() and 0xFF
        val batteryMv = bb.short.toInt() and 0xFFFF
        val nfcStatus = bb.get().toInt() and 0xFF
        val stateVersion = bb.int.toLong() and 0xFFFFFFFFL
        val bufferedReads = bb.short.toInt() and 0xFFFF
        val resetReason = bb.get().toInt() and 0xFF
        return HeartbeatEvent(uptimeS, batteryPct, batteryMv, nfcStatus, stateVersion, bufferedReads, resetReason)
    }

    val DIAG_UNLOCK_MAGIC: ByteArray = byteArrayOf(0x00, 'T'.code.toByte(), 'O'.code.toByte(), 'M'.code.toByte(), 'S'.code.toByte(), '!'.code.toByte())

    fun diagUnlockFrame(): ByteArray = DIAG_UNLOCK_MAGIC

    fun parseDiagEvent(b: ByteArray): DiagEvent {
        if (b.isEmpty()) return DiagEvent.Unknown(b)
        val op = b[0].toInt() and 0xFF
        return when (op) {
            0x80 -> {
                val success = if (b.size >= 3) b[2].toInt() == 1 else false
                DiagEvent.SessionUnlocked(success)
            }
            0x81 -> {
                val count = if (b.size >= 3) (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8) else 0
                DiagEvent.ButtonPress(count)
            }
            0x84 -> {
                val status = if (b.size >= 2) b[1].toInt() and 0xFF else 3
                val ic = if (b.size >= 3) b[2].toInt() and 0xFF else 0
                val verMajor = if (b.size >= 4) b[3].toInt() and 0xFF else 0
                val verMinor = if (b.size >= 5) b[4].toInt() and 0xFF else 0
                DiagEvent.NfcHealth(isOnline = (status == 0), versionStr = "IC 0x%02X v%d.%d".format(ic, verMajor, verMinor))
            }
            else -> {
                val arg = if (b.size >= 2) b[1].toInt() and 0xFF else 0
                DiagEvent.Ack(op and 0x7F, arg)
            }
        }
    }

    fun startFrame(size: Int, sha256: ByteArray): ByteArray {
        require(sha256.size == 32) { "SHA-256 must be 32 bytes" }
        return ByteBuffer.allocate(START_LEN).order(ByteOrder.LITTLE_ENDIAN)
            .put(OP_START.toByte()).putInt(size).put(sha256).array()
    }

    fun chunkFrame(offset: Int, payload: ByteArray): ByteArray {
        require(payload.size in 1..CHUNK_MAX) { "chunk payload must be 1..$CHUNK_MAX bytes" }
        return ByteBuffer.allocate(CHUNK_HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(offset).put(payload).array()
    }

    fun simpleFrame(op: Int): ByteArray = byteArrayOf(op.toByte())

    /** The payload size that fits one write at this MTU, never above what the machine accepts. */
    fun chunkSizeFor(mtu: Int): Int = (mtu - ATT_HEADER - CHUNK_HEADER).coerceIn(1, CHUNK_MAX)

    fun parseEvent(b: ByteArray): OtaEvent? {
        if (b.size != REPLY_LEN) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val op = bb.get().toInt() and 0xFF
        val status = OtaStatus.of(bb.get().toInt() and 0xFF)
        val offset = bb.int.toLong() and 0xFFFFFFFFL
        val state = bb.get().toInt() and 0xFF
        val major = bb.get().toInt() and 0xFF
        val minor = bb.get().toInt() and 0xFF
        val rev = bb.short.toInt() and 0xFFFF
        val flags = bb.get().toInt() and 0xFF
        val running = Version(major, minor, rev, 0)
        return when {
            op == EVENT_PROGRESS -> OtaEvent.Progress(offset)
            op == EVENT_RESYNC -> OtaEvent.Resync(offset)
            op == EVENT_FAILED -> OtaEvent.Failed(status, offset)
            op and 0x80 != 0 -> OtaEvent.Reply(op and 0x7F, status, offset, state, running, flags and 1 != 0)
            else -> null
        }
    }
}

data class Version(val major: Int, val minor: Int, val revision: Int, val build: Long) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.revision }, { it.build })

    override fun toString(): String = "$major.$minor.$revision" + if (build != 0L) "+$build" else ""
}

enum class OtaStatus(val code: Int, val meaning: String) {
    OK(0, "ok"),
    BAD_MSG(1, "the machine did not understand the message"),
    BUSY(2, "the machine is busy, or its running image is still on trial (not confirmed yet)"),
    STATE(3, "wrong moment for that message"),
    SIZE(4, "the image does not fit the update slot"),
    OFFSET(5, "data arrived at the wrong place"),
    HASH(6, "the stored image does not match its SHA-256: corrupted in transit, nothing was installed"),
    HEADER(7, "the stored data is not a valid MCUboot image"),
    VERSION(8, "the image is not newer than the running one"),
    SECURITY(9, "the link is not paired/encrypted"),
    FLASH(10, "the machine's flash write or erase failed"),
    BATTERY(11, "battery too low"),
    UNKNOWN(255, "unknown status");

    companion object {
        fun of(code: Int): OtaStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

sealed interface OtaEvent {
    data class Reply(
        val op: Int, val status: OtaStatus, val offset: Long, val state: Int,
        val running: Version, val confirmed: Boolean,
    ) : OtaEvent

    data class Progress(val offset: Long) : OtaEvent
    data class Resync(val offset: Long) : OtaEvent
    data class Failed(val status: OtaStatus, val offset: Long) : OtaEvent
}

data class CardReadEvent(
    val uidHex: String,
    val uidLen: Int,
    val atype: Int,
    val bootMs: Long,
)

data class PaidCheckEvent(
    val checkSeq: Long,
    val uidHex: String,
    val uidLen: Int,
)

sealed interface DiagEvent {
    data class SessionUnlocked(val success: Boolean) : DiagEvent
    data class ButtonPress(val count: Int) : DiagEvent
    data class NfcHealth(val isOnline: Boolean, val versionStr: String) : DiagEvent
    data class Ack(val cmd: Int, val arg: Int) : DiagEvent
    data class Unknown(val raw: ByteArray) : DiagEvent
}

data class HeartbeatEvent(
    val uptimeS: Long,
    val batteryPct: Int,
    val batteryMv: Int,
    val nfcStatus: Int, // 0 = OK, 3 = Init failed
    val stateVersion: Long,
    val bufferedReads: Int,
    val resetReason: Int
)

/** A firmware file that passed the checks the phone can do before sending anything. */
class OtaImage(val bytes: ByteArray, val version: Version) {
    val size: Int get() = bytes.size
    val sha256: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
}

class ImageRejected(message: String) : Exception(message)

object ImageParser {
    private const val MAGIC = 0x96f3b83dL
    private const val HEADER_MIN = 32
    private const val HEADER_MAX = 4096
    private const val TLV_MIN = 8

    /** The signature block of a normal signed image is a few hundred bytes. */
    private const val TLV_SANE_MAX = 2048

    fun parse(bytes: ByteArray): OtaImage {
        if (bytes.size <= HEADER_MIN) throw ImageRejected("The file is too small to be a firmware image.")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if ((bb.getInt(0).toLong() and 0xFFFFFFFFL) != MAGIC) {
            throw ImageRejected("This is not an MCUboot firmware image (wrong magic number).")
        }
        val headerSize = bb.getShort(8).toInt() and 0xFFFF
        val imageSize = bb.getInt(12).toLong() and 0xFFFFFFFFL
        if (headerSize < HEADER_MIN || headerSize > HEADER_MAX) throw ImageRejected("The image header is damaged.")
        if (imageSize == 0L || headerSize + imageSize > bytes.size) throw ImageRejected("The image is cut short.")
        val tlv = bytes.size - headerSize - imageSize
        if (tlv < TLV_MIN) throw ImageRejected("The image has no signature block.")
        if (tlv > TLV_SANE_MAX) {
            throw ImageRejected(
                "This file is padded out to the whole flash slot (the one for USB flashing). " +
                    "Choose the plain *.signed.bin file instead.",
            )
        }
        if (bytes.size > Ota.MAX_IMAGE) {
            throw ImageRejected("The image is ${bytes.size} bytes; the slot takes at most ${Ota.MAX_IMAGE}.")
        }
        val v = Version(
            bytes[20].toInt() and 0xFF,
            bytes[21].toInt() and 0xFF,
            bb.getShort(22).toInt() and 0xFFFF,
            bb.getInt(24).toLong() and 0xFFFFFFFFL,
        )
        return OtaImage(bytes, v)
    }
}
