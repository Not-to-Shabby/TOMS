package ph.toms.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A stand-in for the Return Machine that follows the same rules as firmware/lib/toms_core
 * ota.c: in-order chunks only, one resync notification until a good chunk arrives, hash check at
 * FINISH. The uploader is tested against it, including drops and lost writes.
 */
private class FakeMachine(
    val running: Version = Version(1, 0, 0, 0),
    val confirmed: Boolean = true,
    val slot: Int = Ota.MAX_IMAGE,
) {
    var size = 0
    var sha = ByteArray(32)
    var received = ByteArray(0)
    var receiving = false
    var nagged = false
    var starts = 0
    var installed = false
    var loseChunkAt: Int? = null // the radio drops the write at this offset, once
    var corruptAt: Int? = null // a byte is flipped on the way, once

    fun event(op: Int, status: OtaStatus, offset: Long): OtaEvent {
        val st = if (op == Ota.EVENT_RESYNC) OtaEvent.Resync(offset) else null
        return st ?: OtaEvent.Reply(op and 0x7F, status, offset, if (receiving) 1 else 0, running, confirmed)
    }

    fun control(data: ByteArray, send: (OtaEvent) -> Unit) {
        when (data[0].toInt()) {
            Ota.OP_START -> {
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val sz = bb.getInt(1)
                val h = data.copyOfRange(5, 37)
                if (sz <= 32 || sz > slot) return send(event(0x81, OtaStatus.SIZE, 0))
                if (!confirmed) return send(event(0x81, OtaStatus.BUSY, 0))
                starts++
                if (receiving && sz == size && h.contentEquals(sha)) {
                    return send(event(0x81, OtaStatus.OK, received.size.toLong())) // resume
                }
                size = sz; sha = h; received = ByteArray(0); receiving = true; nagged = false
                send(event(0x81, OtaStatus.OK, 0))
            }
            Ota.OP_FINISH -> {
                if (!receiving) return send(event(0x82, OtaStatus.STATE, 0))
                if (received.size != size) return send(event(0x82, OtaStatus.OFFSET, received.size.toLong()))
                val got = MessageDigest.getInstance("SHA-256").digest(received)
                if (!got.contentEquals(sha)) {
                    receiving = false
                    return send(event(0x82, OtaStatus.HASH, 0))
                }
                installed = true
                send(event(0x82, OtaStatus.OK, size.toLong()))
            }
            Ota.OP_ABORT -> { receiving = false; send(event(0x83, OtaStatus.OK, 0)) }
        }
    }

    fun data(frame: ByteArray, send: (OtaEvent) -> Unit) {
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val off = bb.int
        val payload = frame.copyOfRange(4, frame.size)
        if (loseChunkAt == off) { loseChunkAt = null; return }
        if (!receiving) return
        if (off != received.size) {
            if (!nagged) { nagged = true; send(OtaEvent.Resync(received.size.toLong())) }
            return
        }
        if (received.size + payload.size > size) return
        val p = payload.copyOf()
        corruptAt?.let { c -> if (c in off until off + p.size) { p[c - off] = (p[c - off].toInt() xor 0xFF).toByte(); corruptAt = null } }
        received += p
        nagged = false
    }
}

private class FakeTransport(
    val m: FakeMachine,
    override val mtu: Int = 65,
    var dropAfterWrites: Int? = null,
    var dropAfterFinish: Boolean = false,
) : OtaTransport {
    private val ch = Channel<OtaEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<OtaEvent> get() = ch
    var writes = 0
    var maxPayload = 0

    private fun checkDrop() {
        val d = dropAfterWrites
        if (d != null && writes >= d) {
            dropAfterWrites = null
            val e = LinkLost("test drop")
            ch.close(e)
            throw e
        }
    }

    override suspend fun writeControl(data: ByteArray) {
        if (dropAfterFinish && data[0].toInt() == Ota.OP_FINISH) {
            // the machine acts on FINISH, but the link dies before its answer reaches the phone
            dropAfterFinish = false
            m.control(data) { }
            ch.close(LinkLost("dropped after finish"))
            return
        }
        m.control(data) { ch.trySend(it) }
    }

    override suspend fun writeData(data: ByteArray) {
        checkDrop()
        writes++
        maxPayload = maxOf(maxPayload, data.size - 4)
        m.data(data) { ch.trySend(it) }
    }

    override fun close() {}
}

class OtaUploaderTest {
    private fun makeImage(bodySize: Int = 5000, version: Version = Version(2, 0, 0, 0)): OtaImage {
        val total = 32 + bodySize + 100
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until total) b.put(i, ((i * 31 + 7) and 0xFF).toByte())
        b.putInt(0, 0x96f3b83d.toInt()); b.putShort(8, 32); b.putInt(12, bodySize)
        b.put(20, version.major.toByte()); b.put(21, version.minor.toByte())
        b.putShort(22, version.revision.toShort()); b.putInt(24, 0)
        return ImageParser.parse(b.array())
    }

    private fun uploader(connect: suspend () -> OtaTransport, reconnects: Int = 5) =
        OtaUploader(connect, maxReconnects = reconnects, reconnectDelayMs = 0)

    @Test fun cleanUploadInstalls() = runTest {
        val m = FakeMachine(); val t = FakeTransport(m); val img = makeImage()
        var last = 0L
        val out = uploader({ t }).upload(img) { sent, _ -> last = sent }
        assertTrue(out is OtaOutcome.Installed)
        assertTrue(m.installed)
        assertArrayEquals(img.bytes, m.received)
        assertEquals(img.size.toLong(), last)
        assertEquals(Version(1, 0, 0, 0), (out as OtaOutcome.Installed).machineRunning)
    }

    @Test fun usesTheNegotiatedMtuAndNeverMore() = runTest {
        for ((mtu, expect) in listOf(65 to 58, 247 to 58, 23 to 16)) {
            val m = FakeMachine(); val t = FakeTransport(m, mtu = mtu)
            uploader({ t }).upload(makeImage(2000)) { _, _ -> }
            assertEquals("mtu $mtu", expect, t.maxPayload)
        }
    }

    @Test fun anImageWithOddLengthStillArrivesWhole() = runTest {
        for (body in listOf(1, 57, 58, 59, 1157)) {
            val m = FakeMachine(); val img = makeImage(body)
            uploader({ FakeTransport(m) }).upload(img) { _, _ -> }
            assertArrayEquals("body $body", img.bytes, m.received)
        }
    }

    @Test fun aLostWriteIsRepairedByTheMachinesResync() = runTest {
        val m = FakeMachine().apply { loseChunkAt = 58 * 20 }
        val img = makeImage()
        val out = uploader({ FakeTransport(m) }).upload(img) { _, _ -> }
        assertTrue(out is OtaOutcome.Installed)
        assertArrayEquals(img.bytes, m.received)
    }

    @Test fun aCorruptedByteIsCaughtByTheMachinesHashCheck() = runTest {
        val m = FakeMachine().apply { corruptAt = 3000 }
        try {
            uploader({ FakeTransport(m) }).upload(makeImage()) { _, _ -> }
            fail("a corrupted upload must not install")
        } catch (e: OtaRefused) {
            assertEquals(OtaStatus.HASH, e.status)
        }
        assertTrue(!m.installed)
    }

    @Test fun aDroppedLinkReconnectsAndResumesWhereItStopped() = runTest {
        val m = FakeMachine(); val img = makeImage(8000)
        val transports = mutableListOf<FakeTransport>()
        val out = uploader({
            FakeTransport(m, dropAfterWrites = if (transports.isEmpty()) 40 else null).also { transports += it }
        }).upload(img) { _, _ -> }
        assertTrue(out is OtaOutcome.Installed)
        assertEquals(2, transports.size)
        assertEquals("the second START resumed instead of erasing", 2, m.starts)
        assertEquals("the second link only sent the rest", true, transports[1].writes < 8132 / 58 - 30)
        assertArrayEquals(img.bytes, m.received)
    }

    @Test fun givesUpAfterTooManyDrops() = runTest {
        val m = FakeMachine()
        try {
            uploader({ FakeTransport(m, dropAfterWrites = 5) }, reconnects = 2).upload(makeImage(8000)) { _, _ -> }
            fail("should give up")
        } catch (e: LinkLost) {
            assertTrue(e.message!!.contains("test drop"))
        }
        assertTrue(!m.installed)
    }

    @Test fun refusesWhenTheRunningImageIsOnTrial() = runTest {
        val m = FakeMachine(confirmed = false)
        try { uploader({ FakeTransport(m) }).upload(makeImage()) { _, _ -> }; fail() }
        catch (e: OtaRefused) { assertEquals(OtaStatus.BUSY, e.status) }
    }

    @Test fun refusesAnImageThatDoesNotFit() = runTest {
        val m = FakeMachine(slot = 1000)
        try { uploader({ FakeTransport(m) }).upload(makeImage(5000)) { _, _ -> }; fail() }
        catch (e: OtaRefused) { assertEquals(OtaStatus.SIZE, e.status) }
    }

    @Test fun aLinkLostRightAfterFinishIsReportedAsUnknownNotAsFailure() = runTest {
        val m = FakeMachine()
        val out = uploader({ FakeTransport(m, dropAfterFinish = true) }).upload(makeImage()) { _, _ -> }
        assertEquals(OtaOutcome.Unknown, out)
        assertTrue("the machine did act on FINISH", m.installed)
    }

    @Test fun aFailedEventStopsTheUpload() = runTest {
        val m = FakeMachine()
        val t = object : OtaTransport by FakeTransport(m) {
            val ch = Channel<OtaEvent>(Channel.UNLIMITED)
            val inner = FakeTransport(m)
            override val events: ReceiveChannel<OtaEvent> get() = inner.events
            var n = 0
            override suspend fun writeData(data: ByteArray) {
                inner.writeData(data)
                if (++n == 10) inner.events.let { (it as Channel<OtaEvent>).trySend(OtaEvent.Failed(OtaStatus.FLASH, 0)) }
            }
            override suspend fun writeControl(data: ByteArray) = inner.writeControl(data)
        }
        try { uploader({ t }).upload(makeImage()) { _, _ -> }; fail() }
        catch (e: OtaRefused) { assertEquals(OtaStatus.FLASH, e.status) }
    }
}
