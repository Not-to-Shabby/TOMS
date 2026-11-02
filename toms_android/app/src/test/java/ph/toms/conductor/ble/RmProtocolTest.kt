package ph.toms.conductor.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.toms.conductor.domain.CardState

class RmProtocolTest {

    @Test
    fun `UUID format matches PROTOCOL spec with 1-byte suffix`() {
        assertEquals("544f4d53-5245-5455-524e-414348494e45", RmProtocol.SERVICE_UUID.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e01", RmProtocol.CHAR_CARD_READ.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e02", RmProtocol.CHAR_STATE_UPDATE.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e03", RmProtocol.CHAR_PAID_CHECK.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e04", RmProtocol.CHAR_PAID_REPLY.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e05", RmProtocol.CHAR_HEARTBEAT.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e06", RmProtocol.CHAR_TIME_SYNC.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e09", RmProtocol.CHAR_DEVICE_INFO.toString())
        assertEquals("544f4d53-5245-5455-524e-414348494e0a", RmProtocol.CHAR_RM_ACK.toString())
    }

    @Test
    fun `decode paid_check parses sequence and 4-byte or 7-byte UID`() {
        val payload4 = byteArrayOf(
            0x2A, 0x00, 0x00, 0x00, // check_seq = 42
            0x04,                   // uid_len = 4
            0x6F.toByte(), 0xF1.toByte(), 0xAD.toByte(), 0x39.toByte(),
        )
        val check4 = RmProtocol.decodePaidCheck(payload4)
        assertNotNull(check4)
        assertEquals(42L, check4!!.checkSeq)
        assertEquals("6F:F1:AD:39", check4.uidHex)

        val payload7 = byteArrayOf(
            0x01, 0x02, 0x00, 0x00, // check_seq = 513
            0x07,                   // uid_len = 7
            0x04, 0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(),
        )
        val check7 = RmProtocol.decodePaidCheck(payload7)
        assertNotNull(check7)
        assertEquals(513L, check7!!.checkSeq)
        assertEquals("04:12:34:56:78:9A:BC", check7.uidHex)
    }

    @Test
    fun `decode paid_check rejects truncated or malformed frames`() {
        assertNull(RmProtocol.decodePaidCheck(byteArrayOf(0x01, 0x00, 0x00, 0x00))) // missing uid_len
        assertNull(RmProtocol.decodePaidCheck(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x04, 0x01, 0x02))) // truncated uid
    }

    @Test
    fun `encode paid_reply formats little-endian check_seq and verdict`() {
        val bytes = RmProtocol.encodePaidReply(100L, RmProtocol.VERDICT_OK)
        assertEquals(5, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(100, bb.int)
        assertEquals(RmProtocol.VERDICT_OK, bb.get().toInt())

        val unpaid = RmProtocol.encodePaidReply(999L, RmProtocol.VERDICT_UNPAID)
        val bb2 = ByteBuffer.wrap(unpaid).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(999, bb2.int)
        assertEquals(RmProtocol.VERDICT_UNPAID, bb2.get().toInt())
    }

    @Test
    fun `encode time_sync encodes epoch seconds and remaining ms`() {
        val now = 1700000000500L // 1700000000s + 500ms
        val bytes = RmProtocol.encodeTimeSync(now)
        assertEquals(6, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1700000000, bb.int)
        assertEquals(500, bb.short.toInt())
    }

    @Test
    fun `encode state_update packs full snapshot and deltas correctly`() {
        val entries = listOf(
            RmProtocol.StateEntry("6F:F1:AD:39", RmProtocol.STATUS_ASSIGNED_PAID),
            RmProtocol.StateEntry("04:12:34:56:78:9A:BC", RmProtocol.STATUS_ASSIGNED_UNPAID),
        )

        val snapshot = RmProtocol.encodeStateUpdate(tableVersion = 10L, snapshot = true, entries = entries)
        val bb = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10, bb.int) // table_version
        assertEquals(0x01, bb.get().toInt()) // flags: bit 0 = snapshot
        assertEquals(2, bb.get().toInt()) // count

        // Entry 1 (4-byte UID)
        assertEquals(4, bb.get().toInt())
        val uid1 = ByteArray(4)
        bb.get(uid1)
        assertEquals("6F:F1:AD:39", uid1.joinToString(":") { "%02X".format(it) })
        assertEquals(RmProtocol.STATUS_ASSIGNED_PAID, bb.get().toInt())

        // Entry 2 (7-byte UID)
        assertEquals(7, bb.get().toInt())
        val uid2 = ByteArray(7)
        bb.get(uid2)
        assertEquals("04:12:34:56:78:9A:BC", uid2.joinToString(":") { "%02X".format(it) })
        assertEquals(RmProtocol.STATUS_ASSIGNED_UNPAID, bb.get().toInt())
        assertFalse(bb.hasRemaining())
    }

    @Test
    fun `decode heartbeat parses battery, NFC status, and uptime`() {
        val payload = byteArrayOf(
            0x3C, 0x00, 0x00, 0x00, // uptime_s = 60
            85,                     // battery_pct = 85%
            0x24, 0x10,             // battery_mv = 4132 mV (0x1024)
            0x00,                   // nfc_status = 0 (Online)
            0x05, 0x00, 0x00, 0x00, // state_version = 5
            0x02, 0x00,             // buffered_reads = 2
            0x00,                   // reset_reason = 0 (power on)
        )
        val hb = RmProtocol.decodeHeartbeat(payload)
        assertNotNull(hb)
        assertEquals(60L, hb!!.uptimeS)
        assertEquals(85, hb.batteryPct)
        assertEquals(4132, hb.batteryMv)
        assertEquals(0, hb.nfcStatus)
        assertEquals(5L, hb.stateVersion)
        assertEquals(2, hb.bufferedReads)
        assertEquals(0, hb.resetReason)
        assertFalse(hb.isOnUsb)

        // USB power indicator (0xFF)
        val usbPayload = payload.clone()
        usbPayload[4] = 0xFF.toByte()
        val usbHb = RmProtocol.decodeHeartbeat(usbPayload)
        assertTrue(usbHb!!.isOnUsb)
    }

    @Test
    fun `cardStateToStatus maps domain CardState correctly to protocol status`() {
        assertEquals(RmProtocol.STATUS_AVAILABLE, RmProtocol.cardStateToStatus(CardState.AVAILABLE))
        assertEquals(RmProtocol.STATUS_ASSIGNED_UNPAID, RmProtocol.cardStateToStatus(CardState.ASSIGNED_UNPAID))
        assertEquals(RmProtocol.STATUS_ASSIGNED_PAID, RmProtocol.cardStateToStatus(CardState.ASSIGNED_PAID))
        assertEquals(RmProtocol.STATUS_RETURNED, RmProtocol.cardStateToStatus(CardState.RETURNED))
        assertEquals(RmProtocol.STATUS_RETURNED_UNPAID, RmProtocol.cardStateToStatus(CardState.RETURNED_UNPAID))
        assertEquals(RmProtocol.STATUS_LOST, RmProtocol.cardStateToStatus(CardState.LOST))
    }

    @Test
    fun `decode rm_ack parses version acknowledgment`() {
        val payload = byteArrayOf(
            0x01,                   // kind = state_ack
            0x07, 0x00, 0x00, 0x00, // version = 7
        )
        val ack = RmProtocol.decodeRmAck(payload)
        assertNotNull(ack)
        assertEquals(1, ack!!.kind)
        assertEquals(7L, ack.tableVersion)
    }
}
