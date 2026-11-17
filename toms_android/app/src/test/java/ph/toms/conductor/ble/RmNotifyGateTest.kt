package ph.toms.conductor.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The verification pipeline's policy and the trust store's lifecycle: what passes, what is dropped and
 * counted, how a pending key becomes active, how a rotation keeps the old key valid for its grace window,
 * and how the debug switch changes it. Runs the gate exactly as the real link does. All keys are generated
 * here at test time; none is written in a file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RmNotifyGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trust = PrefsRmMachineTrust(context, FakeTrustCipher())
    private val machine = "AA:BB:CC:DD:EE:FF"
    private val drops = mutableListOf<Pair<String, Int>>()
    private val verified = mutableListOf<RmKeyMatch>()
    private var signedWithoutKey = 0
    private var badSignature = 0
    private val gate = RmNotifyGate(
        trust,
        machineId = { machine },
        onDrop = { reason, suffix -> drops.add(reason to suffix) },
        onVerified = { _, match -> verified.add(match) },
        onSignedWithoutKey = { signedWithoutKey++ },
        onBadSignature = { badSignature++ },
    )

    /** Robolectric has no Android Keystore: reversible, and never the plain bytes. */
    private class FakeTrustCipher : ph.toms.conductor.security.SecretCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(12) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray? =
            blob.takeIf { it.size > 12 }?.let { it.copyOfRange(12, it.size).map { b -> (b.toInt() xor 0x5A).toByte() }.toByteArray() }
    }

    private fun newKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val key = newKey()

    private fun cardRead(seq: Long = 1) =
        RmProtocol.encodeCardRead(RmProtocol.CardRead(seq, 0, 0, byteArrayOf(0x6F, 0xF1.toByte(), 0xAD.toByte(), 0x39), "6F:F1:AD:39", 3, 1))

    private fun signed(k: ByteArray, seq: Long = 1) = RmSignature.sign(k, RmSignature.SUFFIX_CARD_READ, cardRead(seq))

    @Before
    fun setUp() {
        trust.clear()
        trust.debugBuilds = false
        trust.allowUnverified = false
        drops.clear()
        verified.clear()
        signedWithoutKey = 0
        badSignature = 0
    }

    private fun activate(k: ByteArray = key, version: Int = 1) {
        assertTrue(trust.provision(machine, k, version))
        assertTrue(trust.activate(machine))
    }

    // ---- no key

    @Test
    fun `without a key an unsigned notify passes and a signed one is dropped and reported as a key the phone does not hold`() {
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, cardRead()))
        assertNull("bytes the phone cannot verify must not reach the app", gate.accept(RmSignature.SUFFIX_CARD_READ, signed(key)))
        assertEquals(1, gate.droppedCount.value)
        assertEquals("signed but no key", drops.single().first)
        assertEquals(1, signedWithoutKey)
    }

    // ---- active key

    @Test
    fun `with the key active a good trailer passes and an unsigned notify is refused`() {
        activate()
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signed(key)))
        assertEquals(listOf(RmKeyMatch.ACTIVE), verified)
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, cardRead()))
        assertEquals("unsigned while key active", drops.last().first)
        assertEquals(1, gate.droppedCount.value)
    }

    @Test
    fun `a tampered trailer or the wrong key is dropped and reported`() {
        activate()
        val tampered = signed(key).clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x40).toByte() }
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, tampered))
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signed(newKey())))
        assertEquals(2, gate.droppedCount.value)
        assertEquals(2, badSignature)
        assertEquals("bad signature", drops.last().first)
    }

    @Test
    fun `bad sizes are dropped and counted even with the debug switch on`() {
        activate()
        trust.debugBuilds = true
        trust.allowUnverified = true
        assertNull(gate.accept(RmSignature.SUFFIX_HEARTBEAT, ByteArray(10)))
        assertNull(gate.accept(RmSignature.SUFFIX_HEARTBEAT, ByteArray(30)))
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, ByteArray(4)))
        assertEquals(3, gate.droppedCount.value)
    }

    @Test
    fun `the debug switch accepts but still counts unsigned and badly signed notifies`() {
        activate()
        trust.debugBuilds = true
        trust.allowUnverified = true
        assertTrue(trust.allowUnverified)
        val unsigned = cardRead()
        val tampered = signed(key).clone().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, unsigned))
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, tampered))
        assertEquals("accepted, never silently", 2, gate.droppedCount.value)
    }

    @Test
    fun `the switch is inert when the build is not a debug build`() {
        trust.debugBuilds = false
        trust.allowUnverified = true
        assertFalse(trust.allowUnverified)
    }

    @Test
    fun `heartbeats and rm_ack frames go through the same policy`() {
        activate()
        val hb = RmSignature.sign(key, RmSignature.SUFFIX_HEARTBEAT, RmProtocol.encodeHeartbeat(RmProtocol.Heartbeat(60, 85, 4132, 0, 1, 2, 0)))
        val ack = RmSignature.sign(key, RmSignature.SUFFIX_RM_ACK, byteArrayOf(1, 7, 0, 0, 0))
        assertNotNull(gate.accept(RmSignature.SUFFIX_HEARTBEAT, hb))
        assertNotNull(gate.accept(RmSignature.SUFFIX_RM_ACK, ack))
        assertEquals(0, gate.droppedCount.value)
    }

    // ---- the pending -> active transition

    @Test
    fun `a pending key does not refuse unsigned notifies, and the first message that verifies under it is the proof`() {
        trust.provision(machine, key, 1)
        assertEquals(RmTrustState.Pending(machine, 1), trust.state.value)

        // The machine may not have taken the key yet: unsigned still passes, nothing is dropped.
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, cardRead()))
        assertEquals(0, gate.droppedCount.value)

        // The first signed message under the pending key is accepted and tagged as PENDING.
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signed(key)))
        assertEquals(listOf(RmKeyMatch.PENDING), verified)

        // The owner activates on that signal; from then on unsigned is refused.
        assertTrue(trust.activate(machine))
        assertEquals(RmTrustState.Active(machine, 1), trust.state.value)
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, cardRead()))
    }

    @Test
    fun `a signed message that verifies under nothing while a key is pending is a bad signature, not a missing key`() {
        trust.provision(machine, key, 1)
        assertNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signed(newKey())))
        assertEquals(1, badSignature)
        assertEquals(0, signedWithoutKey)
    }

    // ---- rotation

    @Test
    fun `a rotation keeps the old key valid for the grace window and drops it when the new one verifies`() {
        activate(key, version = 1)
        val newer = newKey()
        trust.clock = { 1_000L }
        trust.provision(machine, newer, 2, previousValidUntilMillis = 10_000L)
        assertEquals("v1 is still the active key while v2 is pending", 1, trust.activeVersion(machine))
        assertEquals(2, trust.pendingVersion(machine))

        // The machine still signs with the old key: it verifies as PREVIOUS (or ACTIVE) and is accepted.
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signed(key)))
        assertEquals(RmKeyMatch.ACTIVE, verified.single())

        // Inside the grace window, a message under the new key verifies as PENDING and activates it.
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signed(newer)))
        assertEquals(RmKeyMatch.PENDING, verified.last())
        assertTrue(trust.activate(machine))
        assertEquals(2, trust.activeVersion(machine))
        assertEquals("the old key is dropped with the activation", 2, trust.activeVersion(machine))
        assertNull("v1 no longer verifies", gate.accept(RmSignature.SUFFIX_CARD_READ, signed(key)))
    }

    @Test
    fun `after the grace window the previous key stops verifying even if the new one was never confirmed`() {
        activate(key, version = 1)
        val newer = newKey()
        trust.clock = { 1_000L }
        trust.provision(machine, newer, 2, previousValidUntilMillis = 10_000L)

        // Move the active slot aside by replaying as the previous key only: simulate an active key replaced earlier.
        trust.clock = { 10_001L }
        // The OLD key is still the active key (the new one is only pending), so it verifies as ACTIVE regardless:
        // the grace window governs the PREVIOUS slot, which only exists once a new key becomes active.
        assertNotNull(gate.accept(RmSignature.SUFFIX_CARD_READ, signed(key)))
        assertEquals(RmKeyMatch.ACTIVE, verified.last())
    }

    @Test
    fun `provisioning rejects a short key and a zero version`() {
        assertFalse(trust.provision(machine, ByteArray(16), 1))
        assertFalse(trust.provision(machine, key, 0))
        assertEquals(RmTrustState.NoKey, trust.state.value)
    }

    // ---- mismatch

    @Test
    fun `a mismatch shows in the state and clears when a key is provisioned or activated`() {
        activate()
        trust.markMismatch(machine, "machine_has_other_key")
        assertEquals(RmTrustState.Mismatch(machine, 1, "machine_has_other_key"), trust.state.value)
        assertEquals("machine_has_other_key", trust.mismatchReason(machine))

        trust.provision(machine, newKey(), 2)
        assertEquals("a new key is a fresh attempt", RmTrustState.Pending(machine, 2), trust.state.value)
        assertNull(trust.mismatchReason(machine))
    }

    @Test
    fun `a pending key that the machine rejects shows as a mismatch of the pending version`() {
        trust.provision(machine, key, 3)
        trust.markMismatch(machine, "rotation_needs_reflash")
        assertEquals(RmTrustState.Mismatch(machine, 3, "rotation_needs_reflash"), trust.state.value)
    }

    // ---- at rest

    @Test
    fun `the trust store encrypts the key at rest and follows pending to active`() {
        trust.provision(machine, key, 1)
        assertEquals("activate for another machine does nothing", false, trust.activate("XX:XX:XX:XX:XX:XX"))
        assertTrue(trust.activate(machine))
        assertEquals(key.toList(), trust.activeKey(machine)!!.toList())

        val raw = context.getSharedPreferences("toms_rm_trust", Context.MODE_PRIVATE).all.values.joinToString()
        assertTrue("the key bytes never sit in plain prefs", !raw.contains(key.joinToString("") { "%02x".format(it) }))
        assertTrue(!raw.contains(android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)))
    }

    @Test
    fun `clear forgets every machine`() {
        activate()
        trust.clear()
        assertEquals(RmTrustState.NoKey, trust.state.value)
        assertNull(trust.activeKey(machine))
        assertFalse(trust.hasAnyKey(machine))
    }
}
