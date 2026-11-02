package ph.toms.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OtaProtocolTest {
    /** A well-formed unpadded signed image: header 32, body, 100-byte signature block. */
    private fun image(body: Int = 1000, tlv: Int = 100, ver: Version = Version(2, 1, 3, 7)): ByteArray {
        val total = 32 + body + tlv
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0x96f3b83d.toInt())
        b.putShort(8, 32)
        b.putInt(12, body)
        b.put(20, ver.major.toByte())
        b.put(21, ver.minor.toByte())
        b.putShort(22, ver.revision.toShort())
        b.putInt(24, ver.build.toInt())
        return b.array()
    }

    @Test fun startFrameMatchesFirmwareLayout() {
        val sha = ByteArray(32) { it.toByte() }
        val f = Ota.startFrame(0x12345678, sha)
        assertEquals(37, f.size)
        assertEquals(1, f[0].toInt())
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), f.copyOfRange(1, 5))
        assertArrayEquals(sha, f.copyOfRange(5, 37))
    }

    @Test fun chunkFrameMatchesFirmwareLayout() {
        val f = Ota.chunkFrame(10000, byteArrayOf(0xAA.toByte()))
        assertArrayEquals(byteArrayOf(0x10, 0x27, 0, 0, 0xAA.toByte()), f)
        try { Ota.chunkFrame(0, ByteArray(241)); fail() } catch (_: IllegalArgumentException) {}
        try { Ota.chunkFrame(0, ByteArray(0)); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(244, Ota.chunkFrame(0, ByteArray(240)).size)
    }

    @Test fun chunkSizeFollowsMtuButNeverExceedsTheMachineLimit() {
        assertEquals(58, Ota.chunkSizeFor(65))
        assertEquals(240, Ota.chunkSizeFor(517))
        assertEquals(16, Ota.chunkSizeFor(23))
        assertEquals(1, Ota.chunkSizeFor(5))
    }

    @Test fun parsesTheFirmwareReplyBytes() {
        // the same bytes the firmware test checks: op 0x81, OFFSET(5), offset 0x01020304, state 1, ver 1.2.0x0304, confirmed
        val b = byteArrayOf(0x81.toByte(), 5, 4, 3, 2, 1, 1, 1, 2, 4, 3, 1)
        val e = Ota.parseEvent(b) as OtaEvent.Reply
        assertEquals(Ota.OP_START, e.op)
        assertEquals(OtaStatus.OFFSET, e.status)
        assertEquals(0x01020304L, e.offset)
        assertEquals(Version(1, 2, 0x0304, 0), e.running)
        assertTrue(e.confirmed)
    }

    @Test fun parsesEvents() {
        fun ev(op: Int, st: Int, off: Int): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(op.toByte()).put(st.toByte()).putInt(off).array()
        assertEquals(OtaEvent.Progress(1856), Ota.parseEvent(ev(0xA0, 0, 1856)))
        assertEquals(OtaEvent.Resync(116), Ota.parseEvent(ev(0xB0, 5, 116)))
        assertEquals(OtaEvent.Failed(OtaStatus.FLASH, 0), Ota.parseEvent(ev(0xF0, 10, 0)))
        assertEquals(OtaStatus.UNKNOWN, (Ota.parseEvent(ev(0x82, 99, 0)) as OtaEvent.Reply).status)
        assertNull(Ota.parseEvent(ByteArray(11)))
        assertNull(Ota.parseEvent(ByteArray(13)))
        assertNull(Ota.parseEvent(ev(0x05, 0, 0))) // no reply flag, not an event we know
        // an offset above 2^31 stays positive
        assertEquals(0xFFFFFFF0L, (Ota.parseEvent(ev(0x81, 0, -16)) as OtaEvent.Reply).offset)
    }

    @Test fun uuidsAreTheDocumentedOnes() {
        assertEquals("544f4d53-5245-5455-524e-414348494e45", Ota.SERVICE.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e0c", Ota.CONTROL.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e0d", Ota.DATA.toString())
    }

    @Test fun acceptsAWellFormedImageAndReadsItsVersion() {
        val img = ImageParser.parse(image())
        assertEquals(Version(2, 1, 3, 7), img.version)
        assertEquals(1132, img.size)
        assertEquals(32, img.sha256.size)
    }

    private fun rejects(bytes: ByteArray, part: String) {
        try {
            ImageParser.parse(bytes)
            fail("should have been rejected: $part")
        } catch (e: ImageRejected) {
            assertTrue(e.message, e.message!!.contains(part, ignoreCase = true))
        }
    }

    @Test fun rejectsBadFiles() {
        rejects(ByteArray(10), "too small")
        rejects(ByteArray(2000), "magic")
        val noTlv = image(tlv = 4)
        rejects(noTlv, "signature")
        val cut = image().copyOf(500)
        rejects(cut, "cut short")
        rejects(image(tlv = 5000), "padded")
        val badHeader = image().also { it[8] = 8; it[9] = 0 }
        rejects(badHeader, "header")
        val tooBig = image(body = Ota.MAX_IMAGE)
        rejects(tooBig, "slot")
    }

    @Test fun rejectsSlotPaddedFilesWithAClearHint() {
        // a *.pending.bin is the same image padded with 0xFF to the whole 1344 KB slot
        val padded = image().copyOf(Ota.SLOT_SIZE).also { for (i in 1132 until it.size) it[i] = 0xFF.toByte() }
        rejects(padded, "plain *.signed.bin")
    }

    @Test fun versionOrder() {
        assertTrue(Version(1, 0, 0, 1) > Version(1, 0, 0, 0))
        assertTrue(Version(2, 0, 0, 0) > Version(1, 9, 9, 9))
        assertEquals(0, Version(1, 2, 3, 4).compareTo(Version(1, 2, 3, 4)))
        assertEquals("1.2.3+4", Version(1, 2, 3, 4).toString())
        assertEquals("2.0.0", Version(2, 0, 0, 0).toString())
    }
}
