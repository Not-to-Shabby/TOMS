package ph.toms.conductor.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
 * The phone's side of firmware/PROTOCOL.md sections 6 and 8 (draft PR #35, not merged): which writes are signed and how, the pairing
 * notices, and the key replace flow with its failures. The "machine" is [FakeReturnMachineLink]'s reading of that spec, so these
 * tests prove the phone follows the text, not that a real Return Machine behaves like it. Keys are generated at test time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RmSecurityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trust = PrefsRmMachineTrust(context, object : ph.toms.conductor.security.SecretCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(12) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray? =
            blob.takeIf { it.size > 12 }?.let { it.copyOfRange(12, it.size).map { b -> (b.toInt() xor 0x5A).toByte() }.toByteArray() }
    })
    private val link = FakeReturnMachineLink()
    private val machine = "AA:BB:CC:DD:EE:FF"

    private fun newKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun hex(s: String) = ByteArray(s.replace(" ", "").length / 2) { s.replace(" ", "").substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Before
    fun setUp() {
        trust.clear()
        link.trust = trust
        link.simulateConnected(address = machine)
    }

    /** The phone and the machine hold [key]: the phone has activated it, the machine has stored it. */
    private fun bothHold(key: ByteArray, version: Int = 1) {
        assertTrue(trust.provision(machine, key, version))
        assertTrue(trust.activate(machine))
        link.machineKey = key.copyOf()
    }

    // ---- 1. which key signs a write

    @Test
    fun `no key anywhere means plain writes, as before`() {
        assertNull(RmSignedWrites.writeKey(trust, machine))
        assertEquals(2, RmSignedWrites.alarmClear(null).size)
        assertEquals(5, RmSignedWrites.paidReply(null, 3, RmProtocol.VERDICT_OK).size)
    }

    @Test
    fun `only the active key signs, never a pending one and never for a machine flagged as mismatched`() {
        val key = newKey()
        assertTrue(trust.provision(machine, key, 1))
        assertNull("a key the machine has not yet been seen to hold is not used", RmSignedWrites.writeKey(trust, machine))

        assertTrue(trust.activate(machine))
        assertArrayEquals(key, RmSignedWrites.writeKey(trust, machine))

        trust.markMismatch(machine, "machine_has_other_key")
        assertNull("a mismatched machine is not signed for", RmSignedWrites.writeKey(trust, machine))
        assertNull(RmSignedWrites.writeKey(trust, null))
    }

    @Test
    fun `during a replace the old key keeps signing until the new one verifies`() {
        val v1 = newKey()
        val v2 = newKey()
        bothHold(v1, 1)
        assertTrue(trust.provision(machine, v2, 2, System.currentTimeMillis() + 3_600_000))
        assertArrayEquals("the old key is still the active one", v1, RmSignedWrites.writeKey(trust, machine))
        assertTrue(trust.activate(machine))
        assertArrayEquals(v2, RmSignedWrites.writeKey(trust, machine))
    }

    // ---- 2. byte layouts of the signed writes (PROTOCOL.md 6 and 8.3)

    @Test
    fun `each write carries the trailer of its own characteristic and no other`() {
        val key = newKey()
        val reply = RmSignedWrites.paidReply(key, 7, RmProtocol.VERDICT_UNPAID)
        assertEquals(5 + 8, reply.size)
        assertTrue(RmSignature.verify(key, 0x04, reply.copyOf(5), reply.copyOfRange(5, 13)))
        assertFalse("a paid_reply trailer is not valid on state_update", RmSignature.verify(key, 0x02, reply.copyOf(5), reply.copyOfRange(5, 13)))

        val time = RmSignedWrites.timeSync(key, 1_700_000_000_123L)
        assertEquals(6 + 8, time.size)
        assertTrue(RmSignature.verify(key, 0x06, time.copyOf(6), time.copyOfRange(6, 14)))

        val ack = RmSignedWrites.readAck(key, 41)
        assertEquals(5 + 8, ack.size)
        assertTrue(RmSignature.verify(key, 0x08, ack.copyOf(5), ack.copyOfRange(5, 13)))

        val entries = listOf(RmProtocol.StateEntry("6FF1AD39", RmProtocol.STATUS_ASSIGNED_UNPAID))
        val state = RmSignedWrites.stateUpdate(key, 9, true, entries)
        val plain = RmProtocol.encodeStateUpdate(9, true, entries)
        assertEquals(plain.size + 8, state.size)
        assertTrue(RmSignature.verify(key, 0x02, plain, state.copyOfRange(plain.size, state.size)))
    }

    @Test
    fun `the Clear alarm bytes are 05 00 and the trailer over 0E, 05, 00`() {
        val key = newKey()
        val bytes = RmProtocol.encodeAlarmClear(key)
        assertEquals(10, bytes.size)
        assertArrayEquals(hex("0500"), bytes.copyOf(2))
        // The signed bytes are the diag suffix 0x0E then the two command bytes (section 8.3).
        val expected = RmSignature.trailer(key, 0x0E, hex("0500"))
        assertArrayEquals(expected, bytes.copyOfRange(2, 10))
        assertArrayEquals("with no key it is the two bytes it always was", hex("0500"), RmProtocol.encodeAlarmClear(null))
    }

    @Test
    fun `the replace write is 08, the new key and a trailer over 0E, 08, new key, 41 bytes`() {
        val current = newKey()
        val fresh = newKey()
        val bytes = RmProtocol.encodeKeyReplace(current, fresh)
        assertEquals(41, bytes.size)
        assertEquals(0x08, bytes[0].toInt())
        assertArrayEquals(fresh, bytes.copyOfRange(1, 33))
        assertArrayEquals(RmSignature.trailer(current, 0x0E, bytes.copyOf(33)), bytes.copyOfRange(33, 41))
        assertFalse("not signed under the new key", RmSignature.verify(fresh, 0x0E, bytes.copyOf(33), bytes.copyOfRange(33, 41)))
    }

    @Test
    fun `a replace key of the wrong size is refused before anything is sent`() {
        var thrown = false
        try { RmProtocol.encodeKeyReplace(newKey(), ByteArray(16)) } catch (_: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    // ---- 3. the writes against the machine's rules

    @Test
    fun `every write the phone makes is signed once both hold a key, and the machine accepts each`() {
        bothHold(newKey())
        link.sendTimeSync(1_700_000_000_000L)
        link.sendPaidReply(3, RmProtocol.VERDICT_OK)
        link.sendStateUpdate(2, false, emptyList())
        link.sendReadAck(10)
        link.sendAlarmClear()

        assertEquals(5, link.writes.size)
        assertTrue(link.writes.joinToString { it.reason }, link.writes.all { it.accepted })
        assertTrue("every write has a trailer", link.writes.all { it.reason == "signature valid" })
        assertEquals(1, link.alarmClearsAccepted)
    }

    @Test
    fun `Clear alarm works with no key at all`() {
        link.sendAlarmClear()
        assertEquals(1, link.alarmClearsAccepted)
        assertEquals("machine has no key", link.writes.single().reason)
    }

    @Test
    fun `an unsigned Clear alarm is refused once the machine has a key, so signing it is what keeps the button working`() {
        // The machine got a key but the phone does not know it yet (the phone writes unsigned).
        link.machineKey = newKey()
        link.sendAlarmClear()
        assertEquals(0, link.alarmClearsAccepted)
        assertEquals("unsigned, refused once a key exists", link.writes.single().reason)

        // The phone holds the key: the same press works.
        trust.clear()
        bothHold(link.machineKey!!)
        link.sendAlarmClear()
        assertEquals(1, link.alarmClearsAccepted)
    }

    @Test
    fun `plain writes with no trailer are still accepted and counted, so a phone with no key is not locked out`() {
        link.machineKey = newKey()
        link.sendReadAck(5)
        link.sendTimeSync(1_700_000_000_000L)
        assertTrue(link.writes.all { it.accepted && it.reason.startsWith("unsigned") })
    }

    @Test
    fun `a wrong key is worse than none and the machine refuses the write`() {
        bothHold(newKey())
        link.machineKey = newKey() // the machine holds a different key
        link.sendReadAck(5)
        link.sendAlarmClear()
        assertTrue(link.writes.none { it.accepted })
        assertEquals(0, link.alarmClearsAccepted)
    }

    @Test
    fun `a machine flagged as mismatched is written to without a trailer`() {
        bothHold(newKey())
        trust.markMismatch(machine, "machine_has_other_key")
        link.machineKey = null
        link.sendReadAck(5)
        assertEquals("machine has no key", link.writes.single().reason)
    }

    @Test
    fun `the interim rule is not relied on`() {
        link.machineKey = newKey()
        link.interimUnsignedAlarm = true
        trust.clear() // phone has no key, writes unsigned
        link.sendAlarmClear()
        assertEquals("accepted only because the fake models the interim rule", 1, link.alarmClearsAccepted)
        assertEquals(2, link.writes.single().bytes.size)
    }

    // ---- 4. pairing

    private class Notices { val seen = mutableListOf<RmKeyStatus?>() }

    private fun tracker(n: Notices) = RmPairingTracker { n.seen.add(it) }

    @Test
    fun `numeric comparison and passkey pairings are fine, nothing is said`() {
        for (variant in listOf(1, 2, 4)) {
            val n = Notices()
            val t = tracker(n)
            t.onConnected(bonded = false)
            t.onBondRequested()
            t.onPairingRequest(variant)
            t.onBondChanged(RmPairingTracker.BOND_NONE, RmPairingTracker.BOND_BONDING)
            t.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_BONDED)
            assertEquals(RmPairingTracker.Phase.BONDED, t.phase)
            assertNull("variant $variant", n.seen.last())
        }
    }

    @Test
    fun `a pairing without a number to compare is reported with the steps to redo it`() {
        for (variant in listOf(0, 3, 5, 6, -1)) {
            val n = Notices()
            val t = tracker(n)
            t.onConnected(bonded = false)
            t.onBondRequested()
            t.onPairingRequest(variant)
            assertEquals(RmPairingTracker.Phase.WEAK, t.phase)
            val text = n.seen.last()!!.text
            assertTrue(text, text.contains("hold its button") && text.contains("60 seconds"))
            assertEquals(RmKeyStatus.Severity.BAD, n.seen.last()!!.severity)
        }
    }

    @Test
    fun `a pairing that ends with no bond says the machine did not accept it, and names the button`() {
        val n = Notices()
        val t = tracker(n)
        t.onConnected(bonded = false)
        t.onBondRequested()
        t.onBondChanged(RmPairingTracker.BOND_NONE, RmPairingTracker.BOND_BONDING)
        t.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_NONE)
        assertEquals(RmPairingTracker.Phase.REFUSED, t.phase)
        val text = n.seen.last()!!.text
        assertTrue(text, text.contains("did not accept pairing") && text.contains("hold its button"))
        assertFalse("it does not claim to know why", text.contains("window is closed"))
    }

    @Test
    fun `a good pairing afterwards clears the refusal`() {
        val n = Notices()
        val t = tracker(n)
        t.onConnected(bonded = false)
        t.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_NONE)
        assertNotNull(n.seen.last())
        t.onBondRequested()
        t.onPairingRequest(2)
        t.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_BONDED)
        assertNull(n.seen.last())
        assertEquals(RmPairingTracker.Phase.BONDED, t.phase)
    }

    @Test
    fun `a write refused for lack of an authenticated link is reported, other refusals are not`() {
        val n = Notices()
        val t = tracker(n)
        t.onConnected(bonded = true)
        t.onWriteRefused(0)   // success
        t.onWriteRefused(3)   // write not permitted: not a pairing problem
        t.onWriteRefused(13)  // invalid length
        assertEquals(RmPairingTracker.Phase.BONDED, t.phase)
        assertNull(n.seen.last())

        t.onWriteRefused(RmPairingTracker.GATT_INSUFFICIENT_AUTHENTICATION)
        assertEquals(RmPairingTracker.Phase.LINK_REFUSED, t.phase)
        assertTrue(n.seen.last()!!.text.contains("not paired with it securely"))
    }

    @Test
    fun `the machine's own not-authenticated answer reaches the pairing notice through the fake link`() = runBlocking {
        bothHold(newKey())
        link.linkAuthenticated = false
        link.sendAlarmClear()
        assertEquals(0, link.alarmClearsAccepted)
        assertEquals(RmPairingTracker.Phase.LINK_REFUSED, link.pairing.phase)
        assertTrue(link.pairingNotice.value!!.text.contains("not paired with it securely"))
    }

    @Test
    fun `a new connection clears the notice and a removed pairing goes back to idle`() {
        val n = Notices()
        val t = tracker(n)
        t.onConnected(bonded = false)
        t.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_NONE)
        assertNotNull(n.seen.last())
        t.onDisconnected()
        assertNotNull("the notice is still there after the link dropped", n.seen.last())
        t.onConnected(bonded = true)
        assertNull(n.seen.last())
        t.onBondChanged(RmPairingTracker.BOND_BONDED, RmPairingTracker.BOND_NONE)
        assertEquals(RmPairingTracker.Phase.IDLE, t.phase)
    }

    @Test
    fun `no notice text names a command, a status byte, a key or a constant`() {
        val n = Notices()
        val t = tracker(n)
        t.onConnected(bonded = false)
        t.onPairingRequest(3)
        t.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_NONE)
        t.onWriteRefused(5)
        for (s in n.seen.filterNotNull()) {
            listOf("0x", "diag", "BOND_", "SMP", "ATT", "HMAC", "key").forEach { assertFalse("${s.text} mentions $it", s.text.contains(it)) }
        }
    }

    // ---- 5. key replace

    private class Server : RmKeyService {
        var next: RmKeyFetch = RmKeyFetch.Failed("offline")
        val mismatches = mutableListOf<String>()
        val verified = mutableListOf<Int>()
        override suspend fun fetchKey() = next
        override suspend fun reportMismatch(reason: String): Boolean { mismatches.add(reason); return true }
        override suspend fun reportVerified(keyVersion: Int): Boolean { verified.add(keyVersion); return true }
    }

    private val server = Server()
    private val provisioner get() = RealRmProvisioner(link, trust, server)

    private fun issued(version: Int, key: ByteArray, previous: ByteArray? = null) =
        RmKeyFetch.Issued(machine, version, key, previous, if (previous != null) System.currentTimeMillis() + 3_600_000 else null)

    private fun provision() = runBlocking { provisioner.provisionConnectedMachine() }

    @Test
    fun `a newer key is sent with diag 08 signed under the key the machine holds, not with 06`() {
        val v1 = newKey()
        val v2 = newKey()
        bothHold(v1, 1)
        server.next = issued(2, v2, previous = v1)

        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals("one replace write", 1, link.replaceWrites)
        assertEquals("diag 06 is for the first key only", 0, link.provisionedKeys.size)
        assertArrayEquals(v2, link.machineKey)
        assertArrayEquals("the machine keeps the old key for one step", v1, link.machineGraceKey)
        assertEquals(2, trust.pendingVersion(machine))
        assertEquals("the old key is still the one the phone signs with", 1, trust.activeVersion(machine))
    }

    @Test
    fun `the full transition ends on the first message that verifies under the new key`() {
        val v1 = newKey()
        val v2 = newKey()
        bothHold(v1, 1)
        server.next = issued(2, v2, previous = v1)
        provision()

        // While waiting, the phone still signs with v1, which the machine accepts under its grace.
        link.sendReadAck(4)
        assertTrue(link.writes.last().accepted)
        assertNotNull(link.machineGraceKey)

        // A machine message signed with the new key verifies as PENDING and activates it.
        val payload = RmProtocol.encodeReadAck(1)
        assertEquals(RmKeyMatch.PENDING, trust.verifyUnder(machine, RmSignature.SUFFIX_RM_ACK, payload, RmSignature.trailer(v2, RmSignature.SUFFIX_RM_ACK, payload)))
        val enrolment = RealRmKeyEnrolment(provisioner, trust, server)
        enrolment.onConnected(machine)
        enrolment.onVerified(machine, RmKeyMatch.PENDING)
        val end = System.currentTimeMillis() + 5_000
        while (trust.activeVersion(machine) != 2 && System.currentTimeMillis() < end) Thread.sleep(15)
        assertEquals(2, trust.activeVersion(machine))
        assertNull(trust.pendingVersion(machine))
        assertArrayEquals("writes are now signed with the new key", v2, RmSignedWrites.writeKey(trust, machine))
        enrolment.stop()
    }

    @Test
    fun `a replace whose reply was lost is sent again and still accepted, and the old key keeps working meanwhile`() {
        val v1 = newKey()
        val v2 = newKey()
        bothHold(v1, 1)
        server.next = issued(2, v2, previous = v1)
        link.machineAnswersReplace = false

        assertEquals("no answer", RmProvisionResult.NO_REPLY, provision())
        assertEquals("the machine did apply it", 1, link.replaceWrites)
        assertEquals("the key stays pending", 2, trust.pendingVersion(machine))

        link.machineAnswersReplace = true
        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals("sent again under the old key, which the machine still accepts", 2, link.replaceWrites)
        assertArrayEquals(v2, link.machineKey)
        assertArrayEquals("the grace key is still the old one", v1, link.machineGraceKey)
        link.sendReadAck(3)
        assertTrue("the phone's old-key writes still work through all of this", link.writes.last().accepted)
    }

    @Test
    fun `a machine that does not hold the key the phone has answers not signed, and that is a mismatch`() {
        val v1 = newKey()
        bothHold(v1, 1)
        link.machineKey = newKey() // it was reset and given another key
        server.next = issued(2, newKey(), previous = v1)

        assertEquals(RmProvisionResult.MACHINE_KEPT_ITS_KEY, provision())
        assertEquals(listOf("machine_has_other_key"), server.mismatches)
        assertEquals("machine_has_other_key", trust.mismatchReason(machine))
        assertNull("the key the machine did not take is dropped", trust.pendingVersion(machine))
        assertEquals("the key the phone trusts is untouched", 1, trust.activeVersion(machine))
    }

    @Test
    fun `a machine with no key left is given the new key the first-time way`() {
        val v1 = newKey()
        bothHold(v1, 1)
        link.machineKey = null
        server.next = issued(2, newKey(), previous = v1)

        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals(1, link.replaceWrites)
        assertEquals("then diag 06", 1, link.provisionedKeys.size)
    }

    @Test
    fun `a firmware that does not know command 08 is flagged as needing an update and never as success`() {
        val v1 = newKey()
        bothHold(v1, 1)
        link.machineKnowsReplace = false
        server.next = issued(2, newKey(), previous = v1)

        assertEquals(RmProvisionResult.MACHINE_KEPT_ITS_KEY, provision())
        assertEquals(listOf("rotation_needs_reflash"), server.mismatches)
        assertNull(trust.pendingVersion(machine))
        assertEquals(1, trust.activeVersion(machine))
    }

    @Test
    fun `an older firmware's echo of the replace command is never read as a status`() {
        // [0x88, first key byte, 0, 0] for every possible first byte, and the generic nack.
        for (first in 0..255) {
            val reply = RmProtocol.decodeReplaceReply(byteArrayOf(0x88.toByte(), first.toByte(), 0, 0))!!
            if (reply.status == RmProtocol.REPLACE_OK) fail("first byte $first read as a success")
        }
        assertEquals(RmProtocol.REPLACE_UNRECOGNISED, RmProtocol.decodeReplaceReply(hex("88 00 00 00"))!!.status)
        assertEquals(RmProtocol.REPLACE_UNRECOGNISED, RmProtocol.decodeReplaceReply(hex("88 00 00 FF"))!!.status)
        assertEquals(RmProtocol.REPLACE_OK, RmProtocol.decodeReplaceReply(hex("88 00 01 00"))!!.status)
        assertNull(RmProtocol.decodeReplaceReply(hex("87 00 02 02")))
        assertNull(RmProtocol.decodeReplaceReply(hex("88 00 01")))
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    @Test
    fun `each status of the spec maps to its own outcome`() {
        val cases = listOf(
            RmProtocol.REPLACE_STORAGE to RmProvisionResult.FLASH_ERROR,
            RmProtocol.REPLACE_MALFORMED to RmProvisionResult.REFUSED,
            RmProtocol.REPLACE_NOT_AUTHENTICATED to RmProvisionResult.REFUSED,
        )
        for ((status, expected) in cases) {
            trust.clear()
            val v1 = newKey()
            bothHold(v1, 1)
            server.next = issued(2, newKey(), previous = v1)
            link.replaceReplyRaw = byteArrayOf(0x88.toByte(), status.toByte(), 0, 0)
            assertEquals("status $status", expected, provision())
            assertNull("status $status leaves no pending key", trust.pendingVersion(machine))
            assertEquals("status $status leaves the active key", 1, trust.activeVersion(machine))
            assertEquals("no mismatch for $status", 0, server.mismatches.size)
        }
    }

    @Test
    fun `an unauthenticated link refuses the replace and the pairing notice says so`() {
        val v1 = newKey()
        bothHold(v1, 1)
        link.linkAuthenticated = false
        server.next = issued(2, newKey(), previous = v1)

        assertEquals(RmProvisionResult.REFUSED, provision())
        assertEquals(RmPairingTracker.Phase.LINK_REFUSED, link.pairing.phase)
    }

    @Test
    fun `the same version again writes nothing and a lower one is not sent`() {
        val v1 = newKey()
        bothHold(v1, 1)
        server.next = issued(1, v1)
        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals(0, link.replaceWrites)
        assertEquals(0, link.provisionedKeys.size)
    }

    @Test
    fun `a first key still goes by diag 06 and never by 08`() {
        server.next = issued(1, newKey())
        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals(1, link.provisionedKeys.size)
        assertEquals(0, link.replaceWrites)
    }

    // ---- 6. what the conductor reads

    @Test
    fun `failures of the key write are told in plain words on More, with no byte, command or key`() {
        val v1 = newKey()
        bothHold(v1, 1)
        val texts = mutableListOf<String>()
        val enrolment = RealRmKeyEnrolment(provisioner, trust, server)
        enrolment.delayMillis = { }

        for (raw in listOf(RmProtocol.REPLACE_STORAGE, RmProtocol.REPLACE_NOT_AUTHENTICATED)) {
            trust.clear(); bothHold(v1, 1)
            server.next = issued(2, newKey(), previous = v1)
            link.replaceReplyRaw = byteArrayOf(0x88.toByte(), raw.toByte(), 0, 0)
            enrolment.onConnected(machine)
            val end = System.currentTimeMillis() + 5_000
            while (enrolment.status.value.severity != RmKeyStatus.Severity.BAD && System.currentTimeMillis() < end) Thread.sleep(15)
            texts += enrolment.status.value.text
            enrolment.onDisconnected()
        }
        enrolment.stop()

        assertTrue(texts[0], texts[0].contains("could not save the new key"))
        assertTrue(texts[1], texts[1].contains("refused the new key"))
        for (t in texts) listOf("0x", "diag", "88", "status", "HMAC", "08").forEach { assertFalse("$t mentions $it", t.contains(it)) }
    }

    @Test
    fun `the mismatch texts name the real cause and no key material`() {
        trust.markMismatch(machine, "machine_has_other_key")
        val enrolment = RealRmKeyEnrolment(provisioner, trust, server)
        enrolment.onConnected(machine)
        Thread.sleep(100)
        val t = enrolment.status.value.text
        assertTrue(t, t.contains("must be signed under the machine's current key"))
        enrolment.stop()

        trust.clear()
        trust.markMismatch(machine, "rotation_needs_reflash")
        val e2 = RealRmKeyEnrolment(provisioner, trust, server)
        e2.onConnected(machine)
        Thread.sleep(100)
        assertTrue(e2.status.value.text.contains("software is too old"))
        e2.stop()
    }
}
