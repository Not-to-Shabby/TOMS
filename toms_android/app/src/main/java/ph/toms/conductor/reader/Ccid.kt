package ph.toms.conductor.reader

/**
 * The few messages of the USB CCID specification (USB-IF "Specification for Integrated Circuit(s) Cards Interface Devices", rev 1.1)
 * that reading a card's UID needs: power the slot on, send one APDU, read the answer, power it off. Every message has a 10-byte
 * header (section 6.1, 6.2): type, dwLength (4 bytes, little endian), slot, sequence number, then 3 type-specific bytes.
 *
 * Only what is needed is here. No other command, no secure PIN entry, no escape commands, no parameters.
 */
interface CcidTransport {
    /** Writes one message to the bulk-OUT endpoint. False when it was not written completely. */
    fun send(message: ByteArray): Boolean

    /** Reads one message from the bulk-IN endpoint, or null on a timeout or a transfer error. */
    fun receive(timeoutMs: Int): ByteArray?

    fun close()
}

object Ccid {
    // Bulk-OUT message types (table 6.1-1)
    const val ICC_POWER_ON = 0x62
    const val ICC_POWER_OFF = 0x63
    const val GET_SLOT_STATUS = 0x65
    const val XFR_BLOCK = 0x6F

    // Bulk-IN message types (section 6.2)
    const val DATA_BLOCK = 0x80
    const val SLOT_STATUS = 0x81

    // bmICCStatus (table 6.2-3)
    const val ICC_ACTIVE = 0
    const val ICC_INACTIVE = 1
    const val ICC_ABSENT = 2

    // Slot error register when bmCommandStatus = 1 (table 6.2-2)
    const val ERR_ICC_MUTE = 0xFE
    const val ERR_HW_ERROR = 0xFB

    const val HEADER_LEN = 10

    /** The pseudo-APDU that returns the UID of the card in the field: FF CA 00 00 00 (ACS "Get Data", API manuals for the ACR122U and ACR1252U, 4.1 / 5.2.2.1). */
    val GET_UID_APDU = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)
}

/** What came back for one command. */
sealed interface CcidResponse {
    /** The command worked. [data] is the message's data part (an ATR, an APDU answer, or nothing). */
    class Ok(val iccStatus: Int, val data: ByteArray) : CcidResponse

    /** The reader reported a failure (bmCommandStatus = 1): [error] is the slot error register. */
    class Failed(val iccStatus: Int, val error: Int) : CcidResponse

    /** Bytes arrived but they are not a valid answer to this command. */
    data object Malformed : CcidResponse

    /** Nothing came back in time, or the transfer failed. */
    data object NoAnswer : CcidResponse
}

/** The result of one try at reading a card's UID. */
sealed interface UidRead {
    class Uid(val bytes: ByteArray) : UidRead

    /** The card went away, or never answered, during the read. Nothing to report; the next presentation tries again. */
    data object CardGone : UidRead

    /** The card answered, but not with a UID this app can use ([why] is for a debug log only). */
    class Rejected(val why: String) : UidRead

    /** The reader itself failed (no answer, garbage, a hardware error). */
    data object ReaderFault : UidRead
}

/**
 * Talks to one CCID slot over a [CcidTransport]. Not thread-safe: one coroutine uses it. Nothing here throws on bad bytes from the
 * transport; every answer is checked and a bad one is [CcidResponse.Malformed].
 */
class CcidClient(private val transport: CcidTransport, private val slot: Int = 0) {

    private var seq = 0

    fun slotStatus(): CcidResponse =
        exchange(Ccid.GET_SLOT_STATUS, byteArrayOf(0, 0, 0), ByteArray(0), Ccid.SLOT_STATUS, STATUS_TIMEOUT_MS)

    fun powerOn(): CcidResponse =
        // bPowerSelect 00h: automatic voltage selection (6.1.1); the two bytes after it are reserved.
        exchange(Ccid.ICC_POWER_ON, byteArrayOf(0, 0, 0), ByteArray(0), Ccid.DATA_BLOCK, POWER_TIMEOUT_MS)

    fun powerOff(): CcidResponse =
        exchange(Ccid.ICC_POWER_OFF, byteArrayOf(0, 0, 0), ByteArray(0), Ccid.SLOT_STATUS, STATUS_TIMEOUT_MS)

    fun xfrBlock(apdu: ByteArray): CcidResponse =
        // bBWI 00h and wLevelParameter 0000h: the whole APDU is in this message (short APDU level, 6.1.4).
        exchange(Ccid.XFR_BLOCK, byteArrayOf(0, 0, 0), apdu, Ccid.DATA_BLOCK, XFR_TIMEOUT_MS)

    /** Power the slot on, ask for the UID, power it off. The slot is powered off again whatever happened in between. */
    fun readUid(): UidRead {
        val on = powerOn()
        if (on is CcidResponse.Failed) return failedAsRead(on)
        if (on !is CcidResponse.Ok) return UidRead.ReaderFault
        try {
            val answer = xfrBlock(Ccid.GET_UID_APDU)
            return when (answer) {
                is CcidResponse.Failed -> failedAsRead(answer)
                is CcidResponse.Ok -> parseUid(answer.data)
                else -> UidRead.ReaderFault
            }
        } finally {
            powerOff()
        }
    }

    private fun failedAsRead(r: CcidResponse.Failed): UidRead = when {
        r.iccStatus == Ccid.ICC_ABSENT || r.error == Ccid.ERR_ICC_MUTE -> UidRead.CardGone
        r.error == Ccid.ERR_HW_ERROR -> UidRead.ReaderFault
        else -> UidRead.Rejected("reader error")
    }

    private fun parseUid(data: ByteArray): UidRead {
        if (data.size < 2) return UidRead.Rejected("answer too short")
        val sw1 = data[data.size - 2].toInt() and 0xFF
        val sw2 = data[data.size - 1].toInt() and 0xFF
        if (sw1 != 0x90 || sw2 != 0x00) return UidRead.Rejected("status word %02X%02X".format(sw1, sw2))
        val uid = data.copyOf(data.size - 2)
        // ISO 14443-A UIDs are 4, 7 or 10 bytes. Anything else is not a passenger card this app can use.
        if (uid.size != 4 && uid.size != 7 && uid.size != 10) return UidRead.Rejected("uid length ${uid.size}")
        return UidRead.Uid(uid)
    }

    private fun exchange(type: Int, header3: ByteArray, data: ByteArray, expectedType: Int, timeoutMs: Int): CcidResponse {
        seq = (seq + 1) and 0xFF
        val message = ByteArray(Ccid.HEADER_LEN + data.size)
        message[0] = type.toByte()
        message[1] = (data.size and 0xFF).toByte()
        message[2] = ((data.size shr 8) and 0xFF).toByte()
        message[5] = slot.toByte()
        message[6] = seq.toByte()
        header3.copyInto(message, 7)
        data.copyInto(message, Ccid.HEADER_LEN)
        if (!transport.send(message)) return CcidResponse.NoAnswer

        // A late answer to an earlier command, or a time-extension notice, is skipped; a few of them are allowed, not a flood.
        repeat(MAX_READS) {
            val r = transport.receive(timeoutMs) ?: return CcidResponse.NoAnswer
            if (r.size < Ccid.HEADER_LEN) return CcidResponse.Malformed
            val length = (r[1].toLong() and 0xFF) or ((r[2].toLong() and 0xFF) shl 8) or
                ((r[3].toLong() and 0xFF) shl 16) or ((r[4].toLong() and 0xFF) shl 24)
            if (length != (r.size - Ccid.HEADER_LEN).toLong()) return CcidResponse.Malformed
            if ((r[6].toInt() and 0xFF) != seq) return@repeat // not the answer to this command
            if ((r[0].toInt() and 0xFF) != expectedType || (r[5].toInt() and 0xFF) != slot) return CcidResponse.Malformed
            val status = r[7].toInt() and 0xFF
            val iccStatus = status and 0x03
            return when ((status shr 6) and 0x03) {
                0 -> CcidResponse.Ok(iccStatus, r.copyOfRange(Ccid.HEADER_LEN, r.size))
                1 -> CcidResponse.Failed(iccStatus, r[8].toInt() and 0xFF)
                2 -> return@repeat // the reader asks for more time (6.2.6): read again
                else -> CcidResponse.Malformed
            }
        }
        return CcidResponse.NoAnswer
    }

    private companion object {
        const val MAX_READS = 6
        const val STATUS_TIMEOUT_MS = 500
        const val POWER_TIMEOUT_MS = 2_000
        const val XFR_TIMEOUT_MS = 1_500
    }
}
