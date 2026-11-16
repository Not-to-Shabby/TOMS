package ph.toms.conductor.ble

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trailer and the framing, against vectors produced by the firmware's own signing code
 * (firmware/lib/toms_core/src/sig.c, compiled on a host and fed the test-only key below), so a mismatch
 * between the two implementations shows up here without a Return Machine. The key is a test fixture
 * written in this file, not a machine key.
 */
class RmSignatureTest {

    /** 0xA0, 0xA1, ... 0xBF: the firmware test's key. */
    private val key = ByteArray(32) { (0xA0 + it).toByte() }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Output of the firmware's toms_sig_append for each payload, payload first and the 8-byte trailer last.
    private val cardRead = hex("0700000000f1536588130000046ff1ad39030123e35bfb24d9e8d6")
    private val heartbeat = hex("3c000000552410000300000002000078cdfe7f1a52ba42")
    private val rmAck = hex("0107000000cabe3162a5d7515c")
    private val paidCheck = hex("2a000000046ff1ad397619a49ea8120dec")

    private fun payload(signed: ByteArray) = signed.copyOfRange(0, signed.size - 8)
    private fun trailer(signed: ByteArray) = signed.copyOfRange(signed.size - 8, signed.size)

    @Test
    fun `the trailer matches what the firmware produces for card_read, heartbeat, rm_ack and a paid_check-shaped payload`() {
        assertArrayEquals(trailer(cardRead), RmSignature.trailer(key, RmSignature.SUFFIX_CARD_READ, payload(cardRead)))
        assertArrayEquals(trailer(heartbeat), RmSignature.trailer(key, RmSignature.SUFFIX_HEARTBEAT, payload(heartbeat)))
        assertArrayEquals(trailer(rmAck), RmSignature.trailer(key, RmSignature.SUFFIX_RM_ACK, payload(rmAck)))
        assertArrayEquals(trailer(paidCheck), RmSignature.trailer(key, 0x03, payload(paidCheck)))
    }

    @Test
    fun `a good trailer verifies and sign produces the same bytes the firmware does`() {
        assertTrue(RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, payload(cardRead), trailer(cardRead)))
        assertArrayEquals(cardRead, RmSignature.sign(key, RmSignature.SUFFIX_CARD_READ, payload(cardRead)))
    }

    @Test
    fun `a trailer is only valid on the characteristic it was signed for`() {
        // The same bytes presented as another characteristic fail: the suffix is part of what is signed.
        assertFalse(RmSignature.verify(key, RmSignature.SUFFIX_HEARTBEAT, payload(cardRead), trailer(cardRead)))
        assertFalse(RmSignature.verify(key, RmSignature.SUFFIX_RM_ACK, payload(heartbeat), trailer(heartbeat)))
    }

    @Test
    fun `any changed payload bit, trailer bit or key bit fails verification`() {
        for (i in payload(cardRead).indices) {
            val flipped = payload(cardRead).also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFalse("payload byte $i", RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, flipped, trailer(cardRead)))
        }
        for (i in 0 until 8) {
            val flipped = trailer(cardRead).also { it[i] = (it[i].toInt() xor 0x80).toByte() }
            assertFalse("trailer byte $i", RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, payload(cardRead), flipped))
        }
        val otherKey = key.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(RmSignature.verify(otherKey, RmSignature.SUFFIX_CARD_READ, payload(cardRead), trailer(cardRead)))
    }

    @Test
    fun `a trailer of the wrong length or a key of the wrong size never verifies`() {
        assertFalse(RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, payload(cardRead), trailer(cardRead).copyOf(7)))
        assertFalse(RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, payload(cardRead), trailer(cardRead) + 0))
        assertFalse(RmSignature.verify(ByteArray(16), RmSignature.SUFFIX_CARD_READ, payload(cardRead), trailer(cardRead)))
        assertFalse(RmSignature.verify(ByteArray(0), RmSignature.SUFFIX_CARD_READ, payload(cardRead), trailer(cardRead)))
    }

    // ---- framing

    @Test
    fun `frame splits a signed notify into payload and trailer, and an unsigned one into payload only`() {
        val signed = RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, cardRead)!!
        assertEquals(19, signed.payload.size)
        assertArrayEquals(trailer(cardRead), signed.trailer)

        val unsigned = RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, payload(cardRead))!!
        assertNull(unsigned.trailer)
        assertEquals(19, unsigned.payload.size)

        assertNotNull(RmProtocol.frame(RmSignature.SUFFIX_HEARTBEAT, heartbeat)!!.trailer)
        assertNotNull(RmProtocol.frame(RmSignature.SUFFIX_RM_ACK, rmAck)!!.trailer)
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_RM_ACK, payload(rmAck))!!.trailer)
    }

    @Test
    fun `frame rejects a partial trailer, extra bytes, a truncated payload and a bad uid length`() {
        // partial trailer, one byte too many, one too few
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, cardRead.copyOf(cardRead.size - 3)))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, cardRead + 0))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, payload(cardRead).copyOf(18)))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_HEARTBEAT, heartbeat + 0))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_RM_ACK, byteArrayOf(1, 2, 3)))
        // uid_len = 0 and 11 are not cards; a card_read with a uid_len byte that is not there is not framed
        val zeroLen = payload(cardRead).also { it[12] = 0 }
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, zeroLen))
        val tooLong = payload(cardRead).also { it[12] = 11 }
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, tooLong))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, ByteArray(5)))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, ByteArray(0)))
        // a characteristic that carries no trailer is never framed
        assertNull(RmProtocol.frame(0x03, paidCheck))
    }

    @Test
    fun `the strict decoders accept only the exact payload`() {
        val read = RmProtocol.decodeCardReadStrict(payload(cardRead))!!
        assertEquals(7L, read.readSeq)
        assertEquals("6F:F1:AD:39", read.uidHex)
        assertEquals(3, read.atype)
        assertEquals(1, read.stateCached)
        assertNull(RmProtocol.decodeCardReadStrict(cardRead)) // still carries the trailer
        assertNull(RmProtocol.decodeCardReadStrict(payload(cardRead).copyOf(18))) // the lenient decoder would invent atype/state

        val hb = RmProtocol.decodeHeartbeatStrict(payload(heartbeat))!!
        assertEquals(60L, hb.uptimeS)
        assertEquals(85, hb.batteryPct)
        assertEquals(2, hb.bufferedReads)
        assertNull(RmProtocol.decodeHeartbeatStrict(heartbeat))

        val ack = RmProtocol.decodeRmAckStrict(payload(rmAck))!!
        assertEquals(1, ack.kind)
        assertEquals(7L, ack.tableVersion)
        assertNull(RmProtocol.decodeRmAckStrict(rmAck))
    }

    @Test
    fun `the lenient card_read decoder still invents fields for a truncated tail, which the strict path refuses`() {
        // Documents why the verified pipeline uses the strict decoder: this frame has no atype or state_cached.
        val truncated = payload(cardRead).copyOf(17)
        val lenient = RmProtocol.decodeCardRead(truncated)!!
        assertEquals(0, lenient.atype)
        assertEquals(0xFF, lenient.stateCached)
        assertNull(RmProtocol.decodeCardReadStrict(truncated))
    }

    // ---- fuzz-style: random lengths and contents never crash and never produce a bogus accept

    @Test
    fun `random bytes of every length never throw and a frame only comes from an exact length`() {
        val rng = Random(20261116)
        repeat(20_000) {
            val len = rng.nextInt(0, 80)
            val bytes = rng.nextBytes(len)
            for (suffix in intArrayOf(RmSignature.SUFFIX_CARD_READ, RmSignature.SUFFIX_HEARTBEAT, RmSignature.SUFFIX_RM_ACK, 0x03, 0x77)) {
                val framed = RmProtocol.frame(suffix, bytes)
                if (framed != null) {
                    // Whatever it accepted is exactly payload [+ trailer] and the parts add up to the input.
                    assertEquals(len, framed.payload.size + (framed.trailer?.size ?: 0))
                    assertTrue(framed.trailer == null || framed.trailer.size == 8)
                }
            }
            // The strict decoders and the provisioning reply decoder must also tolerate anything.
            RmProtocol.decodeCardReadStrict(bytes)
            RmProtocol.decodeHeartbeatStrict(bytes)
            RmProtocol.decodeRmAckStrict(bytes)
            RmProtocol.decodeProvisionReply(bytes)
            RmProtocol.decodeCardRead(bytes)
        }
    }

    @Test
    fun `random trailers never verify and random keys never verify a real frame`() {
        val rng = Random(7)
        repeat(5_000) {
            assertFalse(RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, payload(cardRead), rng.nextBytes(8)))
            assertFalse(RmSignature.verify(rng.nextBytes(32), RmSignature.SUFFIX_CARD_READ, payload(cardRead), trailer(cardRead)))
        }
    }

    @Test
    fun `a valid signed frame of every uid length frames and verifies`() {
        for (uidLen in intArrayOf(4, 7, 10)) {
            val p = ByteArray(15 + uidLen)
            p[12] = uidLen.toByte()
            val signed = RmSignature.sign(key, RmSignature.SUFFIX_CARD_READ, p)
            val framed = RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, signed)!!
            assertTrue(RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, framed.payload, framed.trailer!!))
        }
    }

    // ---- the new encoders and the provisioning reply

    @Test
    fun `read ack is kind 2 followed by the highest read_seq, little endian`() {
        assertArrayEquals(byteArrayOf(2, 0x0A, 0, 0, 0), RmProtocol.encodeReadAck(10))
        assertArrayEquals(byteArrayOf(2, 0x78, 0x56, 0x34, 0x12), RmProtocol.encodeReadAck(0x12345678))
        assertArrayEquals(byteArrayOf(2, -1, -1, -1, -1), RmProtocol.encodeReadAck(0xFFFFFFFFL))
    }

    @Test
    fun `provision key is command 6 then exactly 32 bytes and refuses any other size`() {
        val frame = RmProtocol.encodeProvisionKey(key)
        assertEquals(33, frame.size)
        assertEquals(0x06.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33))
        for (bad in intArrayOf(0, 16, 31, 33, 64)) {
            try {
                RmProtocol.encodeProvisionKey(ByteArray(bad))
                throw AssertionError("a $bad-byte key must be refused")
            } catch (expected: IllegalArgumentException) {
                // the key size is fixed by the firmware
            }
        }
    }

    @Test
    fun `provisioning reply decodes the four statuses and ignores other diag notifies`() {
        assertEquals(RmProtocol.PROVISION_STORED, RmProtocol.decodeProvisionReply(byteArrayOf(0x86.toByte(), 0, 0, 0)))
        assertEquals(RmProtocol.PROVISION_ALREADY_SET, RmProtocol.decodeProvisionReply(byteArrayOf(0x86.toByte(), 1, 0, 0)))
        assertEquals(RmProtocol.PROVISION_FLASH_ERROR, RmProtocol.decodeProvisionReply(byteArrayOf(0x86.toByte(), 2, 0, 0)))
        assertEquals(RmProtocol.PROVISION_REFUSED, RmProtocol.decodeProvisionReply(byteArrayOf(0x86.toByte(), 0xFF.toByte(), 0, 0)))
        assertNull(RmProtocol.decodeProvisionReply(byteArrayOf(0x85.toByte(), 0, 0, 0))) // alarm clear ack
        assertNull(RmProtocol.decodeProvisionReply(byteArrayOf(0x86.toByte(), 0, 0))) // short
        assertNull(RmProtocol.decodeProvisionReply(byteArrayOf(0x86.toByte(), 0, 0, 0, 0))) // long
    }

    @Test
    fun `suffixOf reads the last UUID byte`() {
        assertEquals(0x01, RmProtocol.suffixOf(RmProtocol.CHAR_CARD_READ))
        assertEquals(0x05, RmProtocol.suffixOf(RmProtocol.CHAR_HEARTBEAT))
        assertEquals(0x0A, RmProtocol.suffixOf(RmProtocol.CHAR_RM_ACK))
        assertEquals(0x0E, RmProtocol.suffixOf(RmProtocol.CHAR_DIAG))
    }
}
