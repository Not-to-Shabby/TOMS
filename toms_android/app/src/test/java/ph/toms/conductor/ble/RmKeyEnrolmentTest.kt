package ph.toms.conductor.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A server stand-in: what it issues, and what the phone reported back. Keys are generated at test time. */
private class FakeKeyService : RmKeyService {
    var next: RmKeyFetch = RmKeyFetch.Failed("offline")
    var fetches = 0
    val mismatches = mutableListOf<String>()
    val verifiedVersions = mutableListOf<Int>()

    override suspend fun fetchKey(): RmKeyFetch {
        fetches++
        return next
    }

    override suspend fun reportMismatch(reason: String): Boolean {
        mismatches.add(reason)
        return true
    }

    override suspend fun reportVerified(keyVersion: Int): Boolean {
        verifiedVersions.add(keyVersion)
        return true
    }
}

/**
 * The enrolment loop end to end over the fakes: fetch the key from the server, write it to the machine
 * (diag 06), activate it on the machine's first signed message, close the loop with the server; and the
 * failures — offline server, an unregistered vehicle, a machine that refuses, keeps another key, or never
 * answers — each leaving the phone in a clear state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RmKeyEnrolmentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trust = PrefsRmMachineTrust(context, object : ph.toms.conductor.security.SecretCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(12) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray? =
            blob.takeIf { it.size > 12 }?.let { it.copyOfRange(12, it.size).map { b -> (b.toInt() xor 0x5A).toByte() }.toByteArray() }
    })
    private val link = FakeReturnMachineLink()
    private val service = FakeKeyService()
    private val provisioner = RealRmProvisioner(link, trust, service)
    private val machine = "AA:BB:CC:DD:EE:FF"

    private fun newKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun issued(version: Int = 1, key: ByteArray = newKey(), previous: ByteArray? = null, until: Long? = null) =
        RmKeyFetch.Issued(machine, version, key, previous, until)

    @Before
    fun setUp() {
        trust.clear()
        link.simulateConnected(address = machine)
        link.nextProvisionStatus = RmProtocol.PROVISION_STORED
    }

    private fun enrolment(): RealRmKeyEnrolment = RealRmKeyEnrolment(provisioner, trust, service)

    private fun provision() = runBlocking { provisioner.provisionConnectedMachine() }

    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(15)
        assertTrue("condition not met in time", condition())
    }

    // ---- the happy path

    @Test
    fun `the server's key is written to the machine and waits as pending until the machine signs`() {
        val key = newKey()
        service.next = issued(1, key)
        assertEquals(RmProvisionResult.WRITTEN, provision())

        assertEquals("the key reached the machine over diag 06", 1, link.provisionedKeys.size)
        assertEquals(key.toList(), link.provisionedKeys.single().toList())
        assertEquals(RmTrustState.Pending(machine, 1), trust.state.value)
        assertNull("a pending key is not active", trust.activeVersion(machine))
    }

    @Test
    fun `the machine's first signed message activates the key and tells the server`() {
        val key = newKey()
        service.next = issued(1, key)
        provision()

        val enrolment = enrolment()
        enrolment.onConnected(machine)
        settle { trust.state.value is RmTrustState.Pending || trust.state.value is RmTrustState.Active }
        enrolment.onVerified(machine, RmKeyMatch.PENDING)

        settle { trust.state.value == RmTrustState.Active(machine, 1) }
        settle { service.verifiedVersions == listOf(1) }
        assertEquals(RmKeyStatus.Severity.GOOD, enrolment.status.value.severity)
        assertTrue(enrolment.status.value.text.contains("verified"))
        enrolment.stop()
    }

    @Test
    fun `later verified messages do not repeat the server report`() {
        service.next = issued(1)
        provision()
        val enrolment = enrolment()
        enrolment.onConnected(machine)
        enrolment.onVerified(machine, RmKeyMatch.PENDING)
        settle { service.verifiedVersions == listOf(1) }

        repeat(5) { enrolment.onVerified(machine, RmKeyMatch.ACTIVE) }
        Thread.sleep(150)
        assertEquals("once per key version", listOf(1), service.verifiedVersions)
        enrolment.stop()
    }

    @Test
    fun `provisioning again with the same version writes nothing a second time`() {
        service.next = issued(1)
        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals("the machine saw the key once", 1, link.provisionedKeys.size)
    }

    // ---- failures at the server

    @Test
    fun `an offline server or a refused token provisions nothing and leaves the phone with no key`() {
        service.next = RmKeyFetch.Failed("offline")
        assertEquals(RmProvisionResult.NOT_POSSIBLE, provision())
        assertEquals(0, link.provisionedKeys.size)
        assertEquals(RmTrustState.NoKey, trust.state.value)
    }

    @Test
    fun `a vehicle with no registered machine is not an error and writes nothing`() {
        service.next = RmKeyFetch.NotRegistered("No Return Machine is registered for this vehicle.")
        assertEquals(RmProvisionResult.NOT_POSSIBLE, provision())
        assertEquals(0, link.provisionedKeys.size)
        assertEquals(RmTrustState.NoKey, trust.state.value)
    }

    @Test
    fun `with no machine connected nothing is fetched or written`() {
        link.simulateDisconnected()
        service.next = issued(1)
        assertEquals(RmProvisionResult.NOT_POSSIBLE, provision())
        assertEquals(0, service.fetches)
        assertEquals(0, link.provisionedKeys.size)
    }

    // ---- failures at the machine

    @Test
    fun `a machine that already holds a key is flagged as a mismatch and reported once, never retried`() {
        service.next = issued(1)
        link.nextProvisionStatus = RmProtocol.PROVISION_ALREADY_SET
        assertEquals(RmProvisionResult.MACHINE_KEPT_ITS_KEY, provision())

        assertEquals(listOf("machine_has_other_key"), service.mismatches)
        assertEquals(RmTrustState.Mismatch(machine, 1, "machine_has_other_key"), trust.state.value)
    }

    @Test
    fun `a rotation the machine cannot apply is flagged as needing a re-flash`() {
        // The phone already trusts v1 ...
        service.next = issued(1)
        provision()
        trust.activate(machine)
        // ... and the machine refuses v2, since it keeps the key it has.
        service.next = issued(2)
        link.nextProvisionStatus = RmProtocol.PROVISION_ALREADY_SET
        assertEquals(RmProvisionResult.MACHINE_KEPT_ITS_KEY, provision())

        assertEquals(listOf("rotation_needs_reflash"), service.mismatches)
        assertEquals(1, trust.activeVersion(machine))
        assertEquals("rotation_needs_reflash", trust.mismatchReason(machine))
    }

    @Test
    fun `a flash error, a refusal and a missing reply each end the attempt with the right result`() {
        service.next = issued(1)
        link.nextProvisionStatus = RmProtocol.PROVISION_FLASH_ERROR
        assertEquals(RmProvisionResult.FLASH_ERROR, provision())

        trust.clear()
        link.nextProvisionStatus = RmProtocol.PROVISION_REFUSED
        assertEquals(RmProvisionResult.REFUSED, provision())

        trust.clear()
        link.nextProvisionStatus = null
        assertEquals(RmProvisionResult.NO_REPLY, provision())
        assertEquals("the key stays pending for a retry", RmTrustState.Pending(machine, 1), trust.state.value)
        assertEquals("none of these are mismatches", 0, service.mismatches.size)
    }

    // ---- rotation

    @Test
    fun `a rotation provisions the new key as pending while the old one keeps verifying, and activates on the first signed message`() {
        val v1 = newKey()
        service.next = issued(1, v1)
        provision()
        trust.activate(machine)

        val v2 = newKey()
        service.next = issued(2, v2, previous = v1, until = System.currentTimeMillis() + 3_600_000)
        assertEquals(RmProvisionResult.WRITTEN, provision())
        assertEquals(2, trust.pendingVersion(machine))
        assertEquals("the old key still verifies meanwhile", 1, trust.activeVersion(machine))

        val enrolment = enrolment()
        enrolment.onConnected(machine)
        enrolment.onVerified(machine, RmKeyMatch.PENDING)
        settle { trust.activeVersion(machine) == 2 }
        settle { service.verifiedVersions.contains(2) }
        enrolment.stop()
    }

    @Test
    fun `a message still under the previous key does not activate the new one and flags nothing`() {
        service.next = issued(1)
        provision()
        trust.activate(machine)
        service.next = issued(2)
        provision()

        val enrolment = enrolment()
        enrolment.onConnected(machine)
        enrolment.onVerified(machine, RmKeyMatch.PREVIOUS)
        Thread.sleep(150)
        assertEquals(2, trust.pendingVersion(machine))
        assertEquals(0, service.mismatches.size)
        enrolment.stop()
    }

    // ---- unknown signatures and mismatch status

    @Test
    fun `a signed message with no key on the phone flags a machine that holds a key from somewhere else`() {
        val enrolment = enrolment()
        enrolment.onConnected(machine)
        enrolment.onUnknownSignature(machine)

        settle { service.mismatches == listOf("machine_has_other_key") }
        assertEquals(RmKeyStatus.Severity.BAD, enrolment.status.value.severity)
        assertTrue(enrolment.status.value.text.contains("different key"))

        // A second one does not repeat the report.
        enrolment.onUnknownSignature(machine)
        Thread.sleep(150)
        assertEquals(1, service.mismatches.size)
        enrolment.stop()
    }

    @Test
    fun `a signed message that verifies nowhere while a key is held flags that the messages are not arriving signed right`() {
        service.next = issued(1)
        provision()
        trust.activate(machine)

        val enrolment = enrolment()
        enrolment.onConnected(machine)
        enrolment.onUnknownSignature(machine)
        settle { service.mismatches == listOf("no_signed_messages") }
        enrolment.stop()
    }

    @Test
    fun `a verified message under the active key clears a mismatch the phone had set`() {
        service.next = issued(1)
        provision()
        trust.activate(machine)
        trust.markMismatch(machine, "no_signed_messages")

        val enrolment = enrolment()
        enrolment.onConnected(machine)
        enrolment.onVerified(machine, RmKeyMatch.ACTIVE)
        // The published state is what the conductor reads, so wait on that, not on the preference.
        settle { trust.state.value == RmTrustState.Active(machine, 1) }
        assertNull(trust.mismatchReason(machine))
        enrolment.stop()
    }

    // ---- the retry loop with backoff

    @Test
    fun `an offline server is retried with a growing capped delay and succeeds when it comes back`() {
        val waits = mutableListOf<Long>()
        val enrolment = enrolment().also {
            it.delayMillis = { ms -> synchronized(waits) { waits.add(ms) } }
        }
        service.next = RmKeyFetch.Failed("offline")
        enrolment.onConnected(machine)
        settle { synchronized(waits) { waits.size >= 7 } }

        service.next = issued(1)
        settle { link.provisionedKeys.size == 1 }
        enrolment.stop()

        val seen = synchronized(waits) { waits.toList() }
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L), seen.take(7))
        assertTrue("the delay never exceeds five minutes", seen.all { it <= 300_000L })
    }

    @Test
    fun `a machine that refuses is not retried in a loop`() {
        service.next = issued(1)
        link.nextProvisionStatus = RmProtocol.PROVISION_REFUSED
        val waits = mutableListOf<Long>()
        val enrolment = enrolment().also { it.delayMillis = { ms -> synchronized(waits) { waits.add(ms) } } }
        enrolment.onConnected(machine)
        settle { link.provisionedKeys.size == 1 }
        Thread.sleep(200)
        assertEquals(1, link.provisionedKeys.size)
        assertEquals(0, synchronized(waits) { waits.size })
        enrolment.stop()
    }

    @Test
    fun `a disconnect stops the retry loop`() {
        val waits = mutableListOf<Long>()
        val enrolment = enrolment().also { it.delayMillis = { ms -> synchronized(waits) { waits.add(ms) } } }
        service.next = RmKeyFetch.Failed("offline")
        enrolment.onConnected(machine)
        settle { synchronized(waits) { waits.size >= 2 } }
        enrolment.onDisconnected()
        val after = synchronized(waits) { waits.size }
        Thread.sleep(200)
        assertTrue("no new attempts after the disconnect", synchronized(waits) { waits.size } <= after + 1)
        enrolment.stop()
    }

    // ---- status text

    @Test
    fun `status text follows the trust state and never contains key material`() {
        val enrolment = enrolment()
        assertEquals(RmKeyStatus.NONE, enrolment.status.value)

        val key = newKey()
        service.next = issued(1, key)
        provision()
        enrolment.onConnected(machine)
        settle { enrolment.status.value.text.contains("written") }
        assertEquals(RmKeyStatus.Severity.WARN, enrolment.status.value.severity)

        val hex = key.joinToString("") { "%02x".format(it) }
        val b64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        listOf(
            enrolment.status.value.text,
            RmKeyStatus.NONE.text,
        ).forEach {
            assertFalse(it.contains(hex))
            assertFalse(it.contains(b64))
        }
        enrolment.stop()
    }
}
