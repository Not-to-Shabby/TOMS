package ph.toms.conductor.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire layout 2 (firmware/PROTOCOL.md section 7): the byte examples of the spec as test vectors, the framing that follows the
 * layout in force, the parser fuzz for both layouts, and the phone's side of choosing the layout. These are the spec's bytes,
 * not bytes from a machine: nothing here claims a Return Machine was involved. Keys are generated at test time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RmLayoutV2Test {

    private fun hex(s: String) = ByteArray(s.replace(" ", "").length / 2) { s.replace(" ", "").substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Section 7 examples: a card_read with uid 6FF1AD39 and outcome 1, and a heartbeat with dropped 0x0102 and late 0x0304.
    private val cardReadV2 = hex("07000000 00f15365 92100000 04 6ff1ad39 01 02 01")
    private val heartbeatV2 = hex("3c000000 55 2410 00 05000000 0200 00 0201 0403")

    private fun newKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // ---- vectors

    @Test
    fun `the section 7 card_read decodes with its trailing outcome byte`() {
        assertEquals(20, cardReadV2.size)
        val read = RmProtocol.decodeCardReadStrict(cardReadV2, RmProtocol.Layout.V2)!!
        assertEquals(7L, read.readSeq)
        assertEquals("6FF1AD39", read.uidHex.replace(":", ""))
        assertEquals(1, read.atype)
        assertEquals(2, read.stateCached)
        assertEquals(RmProtocol.OUTCOME_UNVERIFIED_TIMEOUT, read.outcome)
        assertTrue(read.unverified)
        assertArrayEquals(cardReadV2, RmProtocol.encodeCardRead(read, RmProtocol.Layout.V2))
    }

    @Test
    fun `the section 7 heartbeat decodes with dropped_reads and late_replies as uint16`() {
        assertEquals(19, heartbeatV2.size)
        val hb = RmProtocol.decodeHeartbeatStrict(heartbeatV2, RmProtocol.Layout.V2)!!
        assertEquals(60L, hb.uptimeS)
        assertEquals(85, hb.batteryPct)
        assertEquals(4132, hb.batteryMv)
        assertEquals(5L, hb.stateVersion)
        assertEquals(2, hb.bufferedReads)
        assertEquals(0x0102, hb.droppedReads)
        assertEquals(0x0304, hb.lateReplies)
        assertArrayEquals(heartbeatV2, RmProtocol.encodeHeartbeat(hb, RmProtocol.Layout.V2))
    }

    @Test
    fun `the same layout-2 bytes are refused under the layout-1 rule and the layout-1 bytes under layout 2`() {
        assertNull(RmProtocol.decodeCardReadStrict(cardReadV2, RmProtocol.Layout.V1))
        assertNull(RmProtocol.decodeHeartbeatStrict(heartbeatV2, RmProtocol.Layout.V1))
        val v1Read = cardReadV2.copyOf(cardReadV2.size - 1)
        assertNotNull(RmProtocol.decodeCardReadStrict(v1Read, RmProtocol.Layout.V1))
        assertNull(RmProtocol.decodeCardReadStrict(v1Read, RmProtocol.Layout.V2))
        assertNull(RmProtocol.decodeHeartbeatStrict(heartbeatV2.copyOf(15), RmProtocol.Layout.V2))
        assertNull(RmProtocol.decodeCardReadStrict(v1Read, RmProtocol.Layout.V1)!!.outcome)
        assertNull(RmProtocol.decodeHeartbeatStrict(heartbeatV2.copyOf(15), RmProtocol.Layout.V1)!!.droppedReads)
    }

    @Test
    fun `an outcome byte the machine never sends refuses the whole frame`() {
        for (bad in 3..255) {
            val frame = cardReadV2.copyOf().also { it[it.size - 1] = bad.toByte() }
            assertNull("outcome $bad", RmProtocol.decodeCardReadStrict(frame, RmProtocol.Layout.V2))
        }
        for (ok in 0..2) {
            val frame = cardReadV2.copyOf().also { it[it.size - 1] = ok.toByte() }
            assertEquals(ok, RmProtocol.decodeCardReadStrict(frame, RmProtocol.Layout.V2)!!.outcome)
        }
    }

    // ---- framing and the trailer

    @Test
    fun `layout-2 frames split at the layout-2 offset, signed or not`() {
        val key = newKey()
        val signedRead = RmSignature.sign(key, RmSignature.SUFFIX_CARD_READ, cardReadV2)
        assertEquals(28, signedRead.size)
        val framed = RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, signedRead, RmProtocol.Layout.V2)!!
        assertArrayEquals(cardReadV2, framed.payload)
        assertTrue(RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, framed.payload, framed.trailer!!))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, signedRead, RmProtocol.Layout.V1))
        // the unsigned layout-2 payload under the layout-1 rule is one byte too long
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, cardReadV2, RmProtocol.Layout.V1))

        val signedHb = RmSignature.sign(key, RmSignature.SUFFIX_HEARTBEAT, heartbeatV2)
        assertEquals(27, signedHb.size)
        val hbFramed = RmProtocol.frame(RmSignature.SUFFIX_HEARTBEAT, signedHb, RmProtocol.Layout.V2)!!
        assertArrayEquals(heartbeatV2, hbFramed.payload)
        assertTrue(RmSignature.verify(key, RmSignature.SUFFIX_HEARTBEAT, hbFramed.payload, hbFramed.trailer!!))
        assertNull(RmProtocol.frame(RmSignature.SUFFIX_HEARTBEAT, signedHb, RmProtocol.Layout.V1))
    }

    @Test
    fun `the outcome byte and the counters are inside the signed region`() {
        val key = newKey()
        val read = RmSignature.sign(key, RmSignature.SUFFIX_CARD_READ, cardReadV2)
        val hb = RmSignature.sign(key, RmSignature.SUFFIX_HEARTBEAT, heartbeatV2)
        for (i in cardReadV2.indices) {
            val bad = read.clone().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val f = RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, bad, RmProtocol.Layout.V2)
            assertFalse("card_read byte $i", f != null && RmSignature.verify(key, RmSignature.SUFFIX_CARD_READ, f.payload, f.trailer!!))
        }
        for (i in heartbeatV2.indices) {
            val bad = hb.clone().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val f = RmProtocol.frame(RmSignature.SUFFIX_HEARTBEAT, bad, RmProtocol.Layout.V2)
            assertFalse("heartbeat byte $i", f != null && RmSignature.verify(key, RmSignature.SUFFIX_HEARTBEAT, f.payload, f.trailer!!))
        }
    }

    @Test
    fun `random bytes of every length never throw and only an exact length frames, under both layouts`() {
        val rnd = Random(7)
        for (layout in RmProtocol.Layout.values()) {
            for (suffix in listOf(RmSignature.SUFFIX_CARD_READ, RmSignature.SUFFIX_HEARTBEAT, RmSignature.SUFFIX_RM_ACK)) {
                repeat(3000) {
                    val bytes = rnd.nextBytes(rnd.nextInt(0, 48))
                    val framed = RmProtocol.frame(suffix, bytes, layout)
                    if (framed != null) {
                        assertTrue(framed.trailer == null || framed.trailer.size == RmSignature.TRAILER_LEN)
                        assertEquals(bytes.size, framed.payload.size + (framed.trailer?.size ?: 0))
                    }
                    RmProtocol.decodeCardReadStrict(bytes, layout)
                    RmProtocol.decodeHeartbeatStrict(bytes, layout)
                    RmProtocol.decodeWireReply(bytes)
                }
            }
        }
        // every uid length frames with the outcome byte on layout 2, and one byte short never does
        for (uidLen in 1..10) {
            val payload = ByteArray(16 + uidLen).also { it[12] = uidLen.toByte() }
            assertNotNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, payload, RmProtocol.Layout.V2))
            assertNull(RmProtocol.frame(RmSignature.SUFFIX_CARD_READ, payload.copyOf(payload.size - 1), RmProtocol.Layout.V2))
        }
    }

    // ---- the gate follows the layout

    @Test
    fun `the notify gate verifies layout-2 frames only while the layout in force is 2`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val trust = PrefsRmMachineTrust(context, object : ph.toms.conductor.security.SecretCipher {
            override fun encrypt(plain: ByteArray) = ByteArray(12) + plain
            override fun decrypt(blob: ByteArray): ByteArray? = blob.takeIf { it.size > 12 }?.copyOfRange(12, blob.size)
        })
        trust.clear()
        val machine = "AA:BB:CC:DD:EE:FF"
        val key = newKey()
        trust.debugBuilds = false
        trust.allowUnverified = false
        assertTrue(trust.provision(machine, key, 1))
        assertTrue(trust.activate(machine))
        val neg = RmLayoutNegotiation()
        val drops = mutableListOf<String>()
        val gate = RmNotifyGate(trust, machineId = { machine }, onDrop = { r, _ -> drops.add(r) }, layout = { neg.layout.value })
        val signed = RmSignature.sign(key, RmSignature.SUFFIX_CARD_READ, cardReadV2)

        assertNull("layout 1 in force: the longer frame is a bad size", gate.accept(RmSignature.SUFFIX_CARD_READ, signed))
        neg.onDiagNotify(hex("87 00 02 02"))
        assertArrayEquals(cardReadV2, gate.accept(RmSignature.SUFFIX_CARD_READ, signed))
        neg.reset()
        assertNull("a new connection starts on layout 1 again", gate.accept(RmSignature.SUFFIX_CARD_READ, signed))
    }

    // ---- choosing the layout

    @Test
    fun `the select write is diag 07 02 and the reply decodes with its status`() {
        assertArrayEquals(hex("0702"), RmProtocol.encodeWireSelect(RmProtocol.Layout.V2))
        val ok = RmProtocol.decodeWireReply(hex("87 00 02 02"))!!
        assertEquals(listOf(0, 2, 2), listOf(ok.status, ok.versionInForce, ok.maxVersion))
        val unsupported = RmProtocol.decodeWireReply(hex("87 01 01 02"))!!
        assertEquals(1, unsupported.status)
        assertEquals(1, unsupported.versionInForce)
        assertNull(RmProtocol.decodeWireReply(hex("86 00 00 00")))
        assertNull(RmProtocol.decodeWireReply(hex("87 00 02")))
    }

    @Test
    fun `the phone writes 07 02 after a connect and switches to layout 2 on the acknowledgement`() = runBlocking {
        val link = FakeReturnMachineLink()
        assertEquals(RmProtocol.Layout.V1, link.layout.value)
        val result = link.negotiateLayout()
        assertEquals(RmProtocol.Layout.V2, result)
        assertEquals(listOf(listOf(0x07, 0x02)), link.layoutWrites)
        link.simulateDisconnected()
        assertEquals("the layout is per connection", RmProtocol.Layout.V1, link.layout.value)
        assertEquals(RmProtocol.Layout.V2, link.negotiateLayout())
        assertEquals("written again after every connect", 2, link.layoutWrites.size)
    }

    @Test
    fun `a machine that does not answer keeps layout 1`() = runBlocking {
        val neg = RmLayoutNegotiation(replyTimeoutMs = 50)
        var writes = 0
        assertEquals(RmProtocol.Layout.V1, neg.negotiate { writes++; true })
        assertEquals(1, writes)
        assertEquals(RmProtocol.Layout.V1, neg.layout.value)
    }

    @Test
    fun `status unsupported or malformed leaves layout 1, and a failed write waits for nothing`() = runBlocking {
        for (reply in listOf("87 01 01 02", "87 02 01 02")) {
            val neg = RmLayoutNegotiation(replyTimeoutMs = 1_000)
            assertEquals(RmProtocol.Layout.V1, neg.negotiate { neg.onDiagNotify(hex(reply)); true })
        }
        val neg = RmLayoutNegotiation(replyTimeoutMs = 10_000)
        val start = System.currentTimeMillis()
        assertEquals(RmProtocol.Layout.V1, neg.negotiate { false })
        assertTrue(System.currentTimeMillis() - start < 2_000)
    }

    @Test
    fun `the layout is applied on the notify thread, before the waiting coroutine resumes`() = runBlocking {
        val neg = RmLayoutNegotiation(replyTimeoutMs = 2_000)
        val job = async { neg.negotiate { true } }
        while (!job.isActive) Thread.sleep(1)
        Thread.sleep(50)
        neg.onDiagNotify(hex("87 00 02 02"))
        // No suspension point between the notify and the next parse: the layout is already 2.
        assertEquals(RmProtocol.Layout.V2, neg.layout.value)
        assertEquals(RmProtocol.Layout.V2, job.await())
    }

    @Test
    fun `a reply that arrives after the timeout is still applied`() = runBlocking {
        val neg = RmLayoutNegotiation(replyTimeoutMs = 20)
        assertEquals(RmProtocol.Layout.V1, neg.negotiate { true })
        neg.onDiagNotify(hex("87 00 02 02"))
        assertEquals(RmProtocol.Layout.V2, neg.layout.value)
    }

    @Test
    fun `other diag notifies are ignored by the negotiation`() {
        val neg = RmLayoutNegotiation()
        neg.onDiagNotify(hex("86 00 00 00"))
        neg.onDiagNotify(ByteArray(0))
        assertEquals(RmProtocol.Layout.V1, neg.layout.value)
    }

    @Test
    fun `a fake machine that does not know layout 2 leaves the phone on layout 1 and sends the old frames`() = runBlocking {
        val link = FakeReturnMachineLink()
        link.machineSupportsLayout2 = false
        assertEquals(RmProtocol.Layout.V1, link.negotiateLayout())
        val neg = RmLayoutNegotiation(replyTimeoutMs = 20)
        assertEquals(RmProtocol.Layout.V1, neg.negotiate { true })
        assertEquals(15, RmProtocol.encodeHeartbeat(RmProtocol.Heartbeat(1, 2, 3, 0, 1, 0, 0), RmProtocol.Layout.V1).size)
    }
}
