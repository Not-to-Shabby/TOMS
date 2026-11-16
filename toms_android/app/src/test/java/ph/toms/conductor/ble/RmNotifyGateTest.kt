package ph.toms.conductor.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The verification pipeline's policy: what passes, what is dropped and counted, and how the debug
 * switch changes it. Runs the gate exactly as the real link does — same trust, same machine id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RmNotifyGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trust = PrefsRmMachineTrust(context, FakeTrustCipher())
    private val machine = "AA:BB:CC:DD:EE:FF"
    private val drops = mutableListOf<Pair<String, Int>>()
    private val gate = RmNotifyGate(trust, machineId = { machine }, onDrop = { reason, suffix -> drops.add(reason to suffix) })

    /** Robolectric has no Android Keystore: reversible, and never the plain bytes. */
    private class FakeTrustCipher : ph.toms.conductor.security.SecretCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(12) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray? =
            blob.takeIf { it.size > 12 }?.let { it.copyOfRange(12, it.size).map { b -> (b.toInt() xor 0x5A).toByte() }.toByteArray() }
    }

    private val key = ByteArray(32) { (0x10 + it).toByte() }

    private fun signedCardRead(seq: Long = 1): ByteArray =
        RmSignature.sign(key, RmSignature.SUFFIX_CARD_READ, RmProtocol.encodeCardRead(RmProtocol.CardRead(seq, 0, 0, byteArrayOf(0x6F, 0xF1.toByte(), 0xAD.toByte(), 0x39), "6F:F1:AD:39", 3, 1)))

    @Before
    fun setUp() {
        context.getSharedPreferences("toms_rm_trust", Context.MODE_PRIVATE).edit().clear().commit()
        trust.clear()
        drops.clear()
    }

    @Test
    fun `without a key an unsigned notify passes and a signed one is dropped as unverifiable`() {
        val unsigned = RmProtocol.encodeCardRead(RmProtocol.CardRead(1, 0, 0, byteArrayOf(0x6F), "6F", 3, 1))
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, unsigned))
        assertNull("bytes the phone cannot verify must not reach the app", gate.accept(RmSignature.SUFFIX_CARD_READ, signedCardRead()))
        assertEquals(1, gate.droppedCount.value)
        assertEquals("signed but no key", drops.single().first)
        assertEquals(RmSignature.SUFFIX_CARD_READ, drops.single().second)
    }

    @Test
    fun `with the key active a good trailer passes and an unsigned notify is refused`() {
        trust.provision(machine, key)
        trust.activate(machine)

        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signedCardRead()))
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signedCardRead().copyOfRange(0, signedCardRead().size - 8)))
        assertEquals("unsigned while key active", drops.last().first)
        assertEquals(1, gate.droppedCount.value)
    }

    @Test
    fun `a tampered trailer is dropped with the same policy as an unsigned one`() {
        trust.provision(machine, key)
        trust.activate(machine)
        val tampered = signedCardRead().clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x40).toByte() }
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, tampered))
        assertEquals("bad signature", drops.last().first)
        assertEquals(1, gate.droppedCount.value)
    }

    @Test
    fun `a wrong key or another machine's key fails verification`() {
        trust.provision(machine, ByteArray(32) { 1 })
        trust.activate(machine)
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signedCardRead()))
        assertEquals("bad signature", drops.last().first)
    }

    @Test
    fun `bad sizes are dropped and counted even with the debug switch on`() {
        trust.provision(machine, key)
        trust.activate(machine)
        trust.allowUnverified = true

        assertNull(gate.accept(RmSignature.SUFFIX_HEARTBEAT, ByteArray(10)))
        assertNull(gate.accept(RmSignature.SUFFIX_HEARTBEAT, ByteArray(30)))
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, ByteArray(4)))
        assertEquals(3, gate.droppedCount.value)
    }

    @Test
    fun `the debug switch accepts but still counts unsigned and badly signed notifies, and a release build cannot relax it`() {
        trust.debugBuilds = true
        trust.provision(machine, key)
        trust.activate(machine)
        trust.allowUnverified = true
        assertTrue("the debug switch relaxes verification in a debug build", trust.allowUnverified)

        val unsigned = signedCardRead().copyOfRange(0, signedCardRead().size - 8)
        val tampered = signedCardRead().clone().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, unsigned))
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, tampered))
        assertEquals("accepted, never silently", 2, gate.droppedCount.value)
    }

    @Test
    fun `heartbeats and rm_ack frames go through the same policy`() {
        trust.provision(machine, key)
        trust.activate(machine)
        val hb = RmSignature.sign(key, RmSignature.SUFFIX_HEARTBEAT, RmProtocol.encodeHeartbeat(RmProtocol.Heartbeat(60, 85, 4132, 0, 1, 2, 0)))
        val ack = RmSignature.sign(key, RmSignature.SUFFIX_RM_ACK, byteArrayOf(1, 7, 0, 0, 0))
        assertNotNull(gate.accept(RmSignature.SUFFIX_HEARTBEAT, hb))
        assertNotNull(gate.accept(RmSignature.SUFFIX_RM_ACK, ack))
        assertEquals(0, gate.droppedCount.value)
    }

    @Test
    fun `the trust store encrypts the key at rest and follows pending to active`() {
        trust.provision(machine, key)
        assertEquals(RmTrustState.Pending(machine), trust.state.value)
        assertEquals("activate for another machine does nothing", false, trust.activate("XX:XX:XX:XX:XX:XX"))
        assertTrue(trust.activate(machine))
        assertEquals(RmTrustState.Active(machine), trust.state.value)
        assertEquals(key.toList(), trust.activeKey(machine)!!.toList())

        val raw = context.getSharedPreferences("toms_rm_trust", Context.MODE_PRIVATE).all.values.joinToString()
        assertTrue("the key bytes never sit in plain prefs", !raw.contains(key.joinToString("") { "%02x".format(it) }))
    }

    @Test
    fun `the switch is inert when the build is not a debug build`() {
        trust.debugBuilds = false
        trust.allowUnverified = true
        assertEquals(false, trust.allowUnverified)
    }

    @Test
    fun `clear forgets every machine`() {
        trust.provision(machine, key)
        trust.activate(machine)
        trust.clear()
        assertEquals(RmTrustState.NoKey, trust.state.value)
        assertNull(trust.activeKey(machine))
    }
}
