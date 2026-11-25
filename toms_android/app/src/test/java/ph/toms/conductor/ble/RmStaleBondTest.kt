package ph.toms.conductor.ble

import kotlinx.coroutines.runBlocking
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
 * Recovery for a Return Machine that cleared its bond (firmware/PROTOCOL.md section 8.7, hardening draft PR #35, not merged): the
 * machine deleted its half of the pairing, Android still holds the phone's half, so the machine refuses to encrypt and every link
 * ends before it is usable, and the app never asks to pair because Android says "bonded".
 *
 * What these tests drive is the fake link's model of that situation, built from the spec's text ("from reading the stack's and the
 * apps' code; none of it was run"). They show the phone follows the spec, not that a real machine and the Tecno phone behave so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RmStaleBondTest {

    private val link = FakeReturnMachineLink()

    /** The attempt the real link makes every few seconds after a drop. */
    private fun attempts(n: Int) = repeat(n) { link.simulateConnectAttempt() }

    private fun clearedBond() = link.simulateMachineClearedBond()

    // ---- detection

    @Test
    fun `a healthy link with a bond never raises the prompt`() {
        link.androidHoldsBond = true
        repeat(5) { link.simulateConnectAttempt(becomesUsable = true) }
        assertFalse(link.staleBond.value)
        assertNull(link.pairingNotice.value)
    }

    @Test
    fun `one or two dropped attempts are a radio glitch, not a cleared bond`() {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER - 1)
        assertFalse(link.staleBond.value)
        assertNull(link.pairingNotice.value)
    }

    @Test
    fun `repeated attempts that end before the link is usable, with Android still bonded, raise the prompt`() {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)

        assertTrue(link.staleBond.value)
        assertEquals(RmPairingTracker.Phase.STALE_BOND, link.pairing.phase)
        val notice = link.pairingNotice.value!!
        assertEquals(RmKeyStatus.Severity.BAD, notice.severity)
        assertTrue(notice.text, notice.text.contains("has forgotten this phone"))
        assertTrue(notice.text, notice.text.contains("Forget old pairing"))
        assertTrue(notice.text, notice.text.contains("60 seconds"))
    }

    @Test
    fun `attempts that never reach the machine do not count`() {
        clearedBond()
        repeat(10) { link.simulateConnectAttempt(linkComesUp = false) }
        assertFalse(link.staleBond.value)
    }

    @Test
    fun `without a bond on the phone this is the ordinary pairing path, not this problem`() {
        link.androidHoldsBond = false
        attempts(10)
        assertFalse(link.staleBond.value)
        assertNull(link.pairingNotice.value)
    }

    @Test
    fun `one attempt counts once however many of its steps fail`() {
        val tracker = RmPairingTracker(publish = {})
        tracker.onConnected(bonded = true)
        repeat(10) { tracker.onSetupFailed(bonded = true) }
        assertFalse("ten failures of one attempt are one failure", tracker.staleBond)
    }

    @Test
    fun `a good connection in between resets the count`() {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER - 1)
        link.simulateConnectAttempt(becomesUsable = true)
        attempts(RmPairingTracker.STALE_AFTER - 1)
        assertFalse(link.staleBond.value)
    }

    @Test
    fun `the link's own up, ready and down calls drive the count, in the order the real link makes them`() {
        val tracker = RmPairingTracker(publish = {})
        repeat(RmPairingTracker.STALE_AFTER - 1) {
            tracker.onLinkUp(bonded = true)
            tracker.onLinkDown(bonded = true)
        }
        assertFalse(tracker.staleBond)
        tracker.onLinkUp(bonded = true)
        tracker.onLinkDown(bonded = true)
        assertTrue("the third dropped attempt raises it", tracker.staleBond)
    }

    @Test
    fun `a link that became usable before it ended is not a failed attempt`() {
        val tracker = RmPairingTracker(publish = {})
        repeat(10) {
            tracker.onLinkUp(bonded = true)
            tracker.onLinkReady()
            tracker.onLinkDown(bonded = true)
        }
        assertFalse(tracker.staleBond)
    }

    @Test
    fun `an attempt that never came up has no down to count`() {
        val tracker = RmPairingTracker(publish = {})
        repeat(10) { tracker.onLinkDown(bonded = true) }
        assertFalse(tracker.staleBond)
    }

    @Test
    fun `a notification that could not be switched on counts as a failed attempt, once`() {
        val tracker = RmPairingTracker(publish = {})
        repeat(RmPairingTracker.STALE_AFTER) {
            tracker.onLinkUp(bonded = true)
            tracker.onSetupFailed(bonded = true) // the real link reports it when subscribing fails
            tracker.onLinkDown(bonded = true)    // and the drop that follows must not count it again
        }
        assertTrue(tracker.staleBond)
        val twice = RmPairingTracker(publish = {})
        repeat(RmPairingTracker.STALE_AFTER - 1) {
            twice.onLinkUp(bonded = true)
            twice.onSetupFailed(bonded = true)
            twice.onLinkDown(bonded = true)
        }
        assertFalse("two attempts are two failures, not four", twice.staleBond)
    }

    @Test
    fun `a weak pairing is reported as weak, never as a cleared bond`() {
        clearedBond()
        link.pairing.onConnected(bonded = true)
        link.pairing.onPairingRequest(3)
        link.pairing.onSetupFailed(bonded = true)
        link.pairing.onSetupFailed(bonded = true)
        link.pairing.onSetupFailed(bonded = true)
        assertFalse(link.staleBond.value)
        assertEquals(RmPairingTracker.Phase.WEAK, link.pairing.phase)
    }

    @Test
    fun `the prompt survives the retries that follow it`() {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)
        val first = link.pairingNotice.value
        // After EACH retry, not only after a batch: a prompt that vanished and came back would pass a batch check.
        repeat(6) { i ->
            link.simulateConnectAttempt()
            assertTrue("prompt gone after retry ${i + 1}", link.staleBond.value)
            assertEquals("prompt changed after retry ${i + 1}", first, link.pairingNotice.value)
            assertEquals(RmPairingTracker.Phase.STALE_BOND, link.pairing.phase)
        }
    }

    // ---- recovery

    @Test
    fun `forgetting the old pairing removes it, clears the prompt and then explains how to pair again`() = runBlocking {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)

        assertEquals(ForgetPairingResult.REMOVED, link.forgetStalePairing())

        assertFalse("no longer bonded on the phone", link.androidHoldsBond)
        assertFalse(link.staleBond.value)
        assertEquals(RmPairingTracker.Phase.RE_PAIRING, link.pairing.phase)
        val notice = link.pairingNotice.value!!
        assertEquals(RmKeyStatus.Severity.WARN, notice.severity)
        assertTrue(notice.text, notice.text.contains("old pairing was removed"))
        assertTrue(notice.text, notice.text.contains("compare the number"))
    }

    @Test
    fun `a pairing made afterwards ends the notice for good`() = runBlocking {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)
        link.forgetStalePairing()

        // the next attempt has no bond, so the app asks Android to pair, and the numbers are compared
        link.pairing.onConnected(bonded = false)
        link.pairing.onBondRequested()
        link.pairing.onPairingRequest(2)
        link.pairing.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_BONDED)
        link.pairing.onReady()

        assertNull(link.pairingNotice.value)
        assertEquals(RmPairingTracker.Phase.BONDED, link.pairing.phase)
        assertFalse(link.staleBond.value)
    }

    @Test
    fun `a pairing refused after the old one was removed says the machine did not accept it`() = runBlocking {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)
        link.forgetStalePairing()

        link.pairing.onConnected(bonded = false)
        link.pairing.onBondRequested()
        link.pairing.onBondChanged(RmPairingTracker.BOND_NONE, RmPairingTracker.BOND_BONDING)
        link.pairing.onBondChanged(RmPairingTracker.BOND_BONDING, RmPairingTracker.BOND_NONE)

        assertEquals(RmPairingTracker.Phase.REFUSED, link.pairing.phase)
        assertTrue(link.pairingNotice.value!!.text.contains("did not accept pairing"))
    }

    @Test
    fun `when Android will not remove the pairing the prompt tells how to do it by hand`() = runBlocking {
        clearedBond()
        link.androidAllowsRemoveBond = false
        attempts(RmPairingTracker.STALE_AFTER)

        assertEquals(ForgetPairingResult.REFUSED, link.forgetStalePairing())

        assertTrue("the button stays so the conductor can read the steps", link.staleBond.value)
        assertTrue(link.androidHoldsBond)
        val text = link.pairingNotice.value!!.text
        assertTrue(text, text.contains("did not let the app remove the old pairing"))
        assertTrue(text, text.contains("Bluetooth settings") && text.contains("Forget"))
    }

    @Test
    fun `removing the pairing by hand in Android settings is recognised too`() {
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)
        assertTrue(link.staleBond.value)

        link.androidHoldsBond = false
        link.pairing.onBondChanged(RmPairingTracker.BOND_BONDED, RmPairingTracker.BOND_NONE)

        assertFalse(link.staleBond.value)
        assertEquals(RmPairingTracker.Phase.RE_PAIRING, link.pairing.phase)
    }

    @Test
    fun `when the old pairing is already gone there is nothing to remove`() = runBlocking {
        link.androidHoldsBond = false
        assertEquals(ForgetPairingResult.NOT_NEEDED, link.forgetStalePairing())
        assertNull(link.pairingNotice.value)
    }

    @Test
    fun `the signing key is untouched by all of this, so signed writes work again after re-pairing`() = runBlocking {
        val trust = PrefsRmMachineTrust(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            object : ph.toms.conductor.security.SecretCipher {
                override fun encrypt(plain: ByteArray) = ByteArray(12) + plain
                override fun decrypt(blob: ByteArray): ByteArray? = blob.takeIf { it.size > 12 }?.copyOfRange(12, blob.size)
            },
        )
        trust.clear()
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        trust.provision("AA:BB:CC:DD:EE:FF", key, 1)
        trust.activate("AA:BB:CC:DD:EE:FF")
        link.trust = trust
        link.machineKey = key.copyOf()
        link.simulateConnected(address = "AA:BB:CC:DD:EE:FF")

        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)
        link.forgetStalePairing()

        assertNotNull(trust.activeKey("AA:BB:CC:DD:EE:FF"))
        assertEquals(1, trust.activeVersion("AA:BB:CC:DD:EE:FF"))
        link.sendAlarmClear()
        assertEquals("signed Clear alarm is accepted with the kept key", 1, link.alarmClearsAccepted)
    }

    // ---- wording

    @Test
    fun `no text names a command, a status, a key or a firmware detail`() = runBlocking {
        val texts = mutableListOf<String>()
        clearedBond()
        attempts(RmPairingTracker.STALE_AFTER)
        texts += link.pairingNotice.value!!.text
        link.androidAllowsRemoveBond = false
        link.forgetStalePairing()
        texts += link.pairingNotice.value!!.text
        link.androidAllowsRemoveBond = true
        link.forgetStalePairing()
        texts += link.pairingNotice.value!!.text

        for (t in texts) {
            listOf("0x", "diag", "bt_unpair", "SMP", "ATT", "HMAC", "key", "bond", "LTK", "5 s").forEach {
                assertFalse("\"$t\" mentions $it", t.contains(it))
            }
        }
    }
}
