package ph.toms.conductor.workprofile

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the first-run check is shown, when it is out of the way, and the debug-only way past it. */
class WorkProfileViewModelTest {

    private class FakeAck(override var acknowledged: Boolean = false, override var debugContinueWithoutProfile: Boolean = false) : WorkProfileAck

    private fun vm(admin: FakePolicyAdmin, ack: FakeAck, required: Boolean, debug: Boolean) = WorkProfileViewModel(
        WorkProfileGate(admin, required, debug, WorkProfilePolicies(admin, Build.VERSION_CODES.TIRAMISU)),
        ack,
    )

    @Test
    fun `the intended setup is shown once, then passes on later launches`() {
        val admin = FakePolicyAdmin()
        val ack = FakeAck()
        val first = vm(admin, ack, required = true, debug = false)
        assertFalse("first run shows the check", first.state.value.passed)
        first.continueApp()
        assertTrue(first.state.value.passed)

        val later = vm(admin, ack, required = true, debug = false)
        assertTrue("the next launch goes straight on", later.state.value.passed)
    }

    @Test
    fun `a release build outside the intended setup is stopped at every launch, and continue does nothing`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false)
        val ack = FakeAck(acknowledged = true)
        val model = vm(admin, ack, required = true, debug = false)
        assertFalse(model.state.value.passed)
        model.continueApp()
        model.continueWithoutProfile()
        assertFalse(model.state.value.passed)
        assertFalse("the debug switch is not stored in a release build", ack.debugContinueWithoutProfile)
    }

    @Test
    fun `a previous acknowledgement does not let a phone that lost its profile through`() {
        val ack = FakeAck(acknowledged = true)
        assertTrue(vm(FakePolicyAdmin(), ack, required = true, debug = false).state.value.passed)
        assertFalse(vm(FakePolicyAdmin(owner = false, managedProfile = false), ack, required = true, debug = false).state.value.passed)
    }

    @Test
    fun `a development phone with the gate on can choose to continue without a profile, and the choice is remembered`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false)
        val ack = FakeAck()
        val model = vm(admin, ack, required = true, debug = true)
        assertFalse(model.state.value.passed)
        model.continueWithoutProfile()
        assertTrue(model.state.value.passed)
        assertTrue(vm(admin, ack, required = true, debug = true).state.value.passed)
    }

    @Test
    fun `a debug build with the gate off shows the check once and then runs as before`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false)
        val ack = FakeAck()
        val model = vm(admin, ack, required = false, debug = true)
        assertFalse(model.state.value.passed)
        model.continueApp()
        assertTrue(model.state.value.passed)
    }

    @Test
    fun `check again picks up a profile set up after the screen was shown`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false)
        val ack = FakeAck()
        val model = vm(admin, ack, required = true, debug = false)
        assertEquals(ProfileStatus.NOT_IN_PROFILE, model.state.value.check.status)

        admin.owner = true
        admin.managedProfile = true
        model.recheck()

        assertEquals(ProfileStatus.OWNER_IN_WORK_PROFILE, model.state.value.check.status)
        assertTrue("the policies were set on the recheck", admin.uninstallIsBlocked)
        assertTrue(model.state.value.check.canContinue)
    }
}
