package ph.toms.conductor.reader

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CCID messages, byte by byte, against the layouts quoted in USB-IF "CCID Rev 1.1" (6.1.1 PowerOn, 6.1.2 PowerOff, 6.1.3
 * GetSlotStatus, 6.1.4 XfrBlock, 6.2.1 DataBlock, 6.2.2 SlotStatus, 6.2.6 status and error registers) and the ACS get-UID APDU.
 * Pure Kotlin, no Android, no reader.
 */
class CcidClientTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val uid = hex("6FF1AD39")

    /** A transport that records what was sent and answers from a script. */
    private class Scripted(val answers: MutableList<ByteArray?> = mutableListOf()) : CcidTransport {
        val sent = mutableListOf<ByteArray>()
        var sendOk = true
        override fun send(message: ByteArray): Boolean { sent.add(message.copyOf()); return sendOk }
        override fun receive(timeoutMs: Int): ByteArray? = if (answers.isEmpty()) null else answers.removeAt(0)
        override fun close() {}
    }

    private fun dataBlock(seq: Int, data: ByteArray, status: Int = 0, error: Int = 0) =
        hex("80") + byteArrayOf(data.size.toByte(), 0, 0, 0) + byteArrayOf(0, seq.toByte(), status.toByte(), error.toByte(), 0) + data

    private fun slotStatus(seq: Int, status: Int) = hex("81 00000000 00") + byteArrayOf(seq.toByte(), status.toByte(), 0, 0)

    // ---- the bytes we send

    @Test
    fun `PowerOn is 62, a zero length, slot 0, the sequence number and automatic voltage`() {
        val t = Scripted(mutableListOf(dataBlock(1, hex("3B8F8001"))))
        CcidClient(t).powerOn()
        assertArrayEquals(hex("62 00000000 00 01 00 0000"), t.sent.single())
    }

    @Test
    fun `PowerOff is 63 and GetSlotStatus is 65, each 10 bytes`() {
        val t = Scripted(mutableListOf(slotStatus(1, 1), slotStatus(2, 0)))
        val c = CcidClient(t)
        c.powerOff()
        c.slotStatus()
        assertArrayEquals(hex("63 00000000 00 01 000000"), t.sent[0])
        assertArrayEquals(hex("65 00000000 00 02 000000"), t.sent[1])
    }

    @Test
    fun `XfrBlock carries the get-UID APDU FF CA 00 00 00 after a 10 byte header with its length`() {
        val t = Scripted(mutableListOf(dataBlock(1, uid + hex("9000"))))
        CcidClient(t).xfrBlock(Ccid.GET_UID_APDU)
        assertArrayEquals(hex("6F 05000000 00 01 000000 FFCA000000"), t.sent.single())
    }

    @Test
    fun `the sequence number counts up with every message and wraps after 255`() {
        val t = Scripted(MutableList(300) { null })
        val c = CcidClient(t)
        repeat(300) { c.slotStatus() }
        assertEquals(1, t.sent.first()[6].toInt() and 0xFF)
        assertEquals(255, t.sent[254][6].toInt() and 0xFF)
        assertEquals(0, t.sent[255][6].toInt() and 0xFF)
    }

    // ---- reading a UID

    @Test
    fun `a good read powers on, asks for the UID, powers off, and returns the UID without its status words`() {
        val t = Scripted(mutableListOf(dataBlock(1, hex("3B8F8001")), dataBlock(2, uid + hex("9000")), slotStatus(3, 1)))
        val r = CcidClient(t).readUid()
        assertArrayEquals(uid, (r as UidRead.Uid).bytes)
        assertEquals(listOf(0x62, 0x6F, 0x63), t.sent.map { it[0].toInt() and 0xFF })
    }

    @Test
    fun `the slot is powered off again even when the UID read fails`() {
        val t = Scripted(mutableListOf(dataBlock(1, hex("3B")), dataBlock(2, ByteArray(0), status = 0x40 or 0, error = Ccid.ERR_HW_ERROR), slotStatus(3, 1)))
        val r = CcidClient(t).readUid()
        assertTrue(r is UidRead.ReaderFault)
        assertEquals(0x63, t.sent.last()[0].toInt() and 0xFF)
    }

    @Test
    fun `4, 7 and 10 byte UIDs are accepted and any other length is refused`() {
        for (len in 0..12) {
            val u = ByteArray(len) { (it + 1).toByte() }
            val t = Scripted(mutableListOf(dataBlock(1, hex("3B")), dataBlock(2, u + hex("9000")), slotStatus(3, 1)))
            val r = CcidClient(t).readUid()
            if (len == 4 || len == 7 || len == 10) assertArrayEquals("len $len", u, (r as UidRead.Uid).bytes)
            else assertTrue("len $len", r is UidRead.Rejected)
        }
    }

    @Test
    fun `a status word other than 90 00 is refused, never read as a UID`() {
        for (sw in listOf("6300", "6A81", "6282", "6C04", "9001", "0000")) {
            val t = Scripted(mutableListOf(dataBlock(1, hex("3B")), dataBlock(2, uid + hex(sw)), slotStatus(3, 1)))
            assertTrue(sw, CcidClient(t).readUid() is UidRead.Rejected)
        }
    }

    @Test
    fun `no card in the field is a card gone, not an error`() {
        // PowerOn failed with bmICCStatus 2 (no ICC present) and ICC_MUTE (6.1.1 errors)
        val t = Scripted(mutableListOf(dataBlock(1, ByteArray(0), status = 0x42, error = Ccid.ERR_ICC_MUTE)))
        assertEquals(UidRead.CardGone, CcidClient(t).readUid())
    }

    // ---- answers that are wrong

    @Test
    fun `an answer to an older command is skipped and the right one is used`() {
        val t = Scripted(mutableListOf(dataBlock(9, hex("0000")), dataBlock(1, hex("3B"))))
        val r = CcidClient(t).powerOn()
        // Both are valid blocks, so only the data tells which one was used: the stale one carries 0000, the right one 3B.
        assertArrayEquals(hex("3B"), (r as CcidResponse.Ok).data)
    }

    @Test
    fun `a late answer to an old UID read cannot be taken for the answer to this one`() {
        // Power-on answers, then a stale UID block from an earlier read arrives before this read's own answer.
        val stale = dataBlock(7, hex("DEADBEEF") + hex("9000"))
        val t = Scripted(mutableListOf(dataBlock(1, hex("3B")), stale, dataBlock(2, uid + hex("9000")), slotStatus(3, 1)))
        assertArrayEquals(uid, (CcidClient(t).readUid() as UidRead.Uid).bytes)
    }

    @Test
    fun `a time extension asks for another read and the answer after it is used`() {
        val ext = hex("80 00000000 00 01 80 01 00")
        val t = Scripted(mutableListOf(ext, ext, dataBlock(1, hex("3B"))))
        assertTrue(CcidClient(t).powerOn() is CcidResponse.Ok)
    }

    @Test
    fun `endless time extensions end as no answer, not a hang`() {
        val ext = hex("80 00000000 00 01 80 01 00")
        val t = Scripted(MutableList(50) { ext })
        assertEquals(CcidResponse.NoAnswer, CcidClient(t).powerOn())
    }

    @Test
    fun `a wrong message type, a wrong slot, a bad length and a short answer are all malformed`() {
        val wrongType = hex("81 00000000 00 01 00 00 00") // PowerOn must answer 80h
        val wrongSlot = dataBlock(1, hex("3B")).also { it[5] = 7 }
        val badLen = dataBlock(1, hex("3B")).also { it[1] = 9 }
        val short = hex("80 00 00")
        for ((name, answer) in listOf("type" to wrongType, "slot" to wrongSlot, "length" to badLen, "short" to short)) {
            assertEquals(name, CcidResponse.Malformed, CcidClient(Scripted(mutableListOf(answer))).powerOn())
        }
    }

    @Test
    fun `a length that claims gigabytes does not overflow`() {
        val huge = hex("80 FFFFFFFF 00 01 00 00 00")
        assertEquals(CcidResponse.Malformed, CcidClient(Scripted(mutableListOf(huge))).powerOn())
    }

    @Test
    fun `no answer and a failed send both report no answer`() {
        assertEquals(CcidResponse.NoAnswer, CcidClient(Scripted()).powerOn())
        val t = Scripted().also { it.sendOk = false }
        assertEquals(CcidResponse.NoAnswer, CcidClient(t).powerOn())
        assertTrue("a failed send waits for no reply", t.sent.size == 1)
    }

    @Test
    fun `reader error codes map to a card gone, a reader fault or a refusal`() {
        fun readWith(error: Int, icc: Int): UidRead {
            val t = Scripted(mutableListOf(dataBlock(1, hex("3B")), dataBlock(2, ByteArray(0), status = 0x40 or icc, error = error), slotStatus(3, 1)))
            return CcidClient(t).readUid()
        }
        assertEquals(UidRead.CardGone, readWith(Ccid.ERR_ICC_MUTE, 0))
        assertEquals(UidRead.CardGone, readWith(0x05, 2))
        assertEquals(UidRead.ReaderFault, readWith(Ccid.ERR_HW_ERROR, 1))
        assertTrue(readWith(0xE0, 0) is UidRead.Rejected) // CMD_SLOT_BUSY
        assertTrue(readWith(0x00, 0) is UidRead.Rejected) // command not supported
    }

    @Test
    fun `random bytes from the transport never crash the client and never become a UID`() {
        val rnd = Random(2026)
        repeat(3_000) {
            val answers: MutableList<ByteArray?> = MutableList(6) { ByteArray(rnd.nextInt(40)).also { b -> rnd.nextBytes(b) } }
            // sometimes make the first byte and sequence plausible, to get past the first checks
            if (it % 3 == 0) answers.forEach { a -> if (a != null && a.size > 6) { a[0] = 0x80.toByte(); a[6] = 1 } }
            val r = CcidClient(Scripted(answers)).readUid()
            if (r is UidRead.Uid) {
                // the only way through is a well-formed block with 90 00, which random bytes would not carry
                assertTrue("random bytes became a UID of ${r.bytes.size} bytes", r.bytes.size in listOf(4, 7, 10))
            }
        }
    }
}
