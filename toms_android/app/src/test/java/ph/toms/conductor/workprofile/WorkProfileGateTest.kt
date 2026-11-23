package ph.toms.conductor.workprofile

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which situation the phone is in, what the first-run check says, and when the app may go past it. */
class WorkProfileGateTest {

    private fun gate(admin: FakePolicyAdmin, required: Boolean, debug: Boolean) =
        WorkProfileGate(admin, required = required, debugBuilds = debug, policies = WorkProfilePolicies(admin, Build.VERSION_CODES.TIRAMISU))

    @Test
    fun `owner inside a work profile is the intended setup and passes in release`() {
        val check = gate(FakePolicyAdmin(owner = true, managedProfile = true), required = true, debug = false).checkAndApply()
        assertEquals(ProfileStatus.OWNER_IN_WORK_PROFILE, check.status)
        assertTrue(check.canContinue)
        assertFalse(check.canContinueWithoutProfile)
        assertTrue(check.headline.contains("running in a work profile"))
    }

    @Test
    fun `an ordinary install is stopped in release and the check offers the setup`() {
        val check = gate(FakePolicyAdmin(owner = false, managedProfile = false), required = true, debug = false).checkAndApply()
        assertEquals(ProfileStatus.NOT_IN_PROFILE, check.status)
        assertFalse(check.canContinue)
        assertFalse("a release build has no way around the check", check.canContinueWithoutProfile)
        assertTrue(check.headline.contains("not running in a work profile"))
    }

    @Test
    fun `a debug build without a profile can carry on, and the way is offered only while the gate is on`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false)

        val gateOff = gate(admin, required = false, debug = true).checkAndApply()
        assertTrue("development phones run as before", gateOff.canContinue)
        assertFalse(gateOff.canContinueWithoutProfile)

        val gateOn = gate(admin, required = true, debug = true).checkAndApply()
        assertFalse(gateOn.canContinue)
        assertTrue(gateOn.canContinueWithoutProfile)
    }

    @Test
    fun `a work profile owned by another app cannot set policies and is stopped`() {
        val check = gate(FakePolicyAdmin(owner = false, managedProfile = true), required = true, debug = false).checkAndApply()
        assertEquals(ProfileStatus.WORK_PROFILE_NOT_OWNER, check.status)
        assertFalse(check.canContinue)
        assertTrue(check.detail.contains("Another app owns"))
    }

    @Test
    fun `owner of the main profile is reported as that, not as a work profile`() {
        val check = gate(FakePolicyAdmin(owner = true, managedProfile = false), required = true, debug = false).checkAndApply()
        assertEquals(ProfileStatus.OWNER_OUTSIDE_WORK_PROFILE, check.status)
        assertFalse(check.canContinue)
    }

    @Test
    fun `a phone without the work-profile feature says so`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false, managedProfilesSupported = false)
        val check = gate(admin, required = true, debug = false).checkAndApply()
        assertEquals(ProfileStatus.NOT_SUPPORTED, check.status)
        assertFalse(check.canContinue)
    }

    @Test
    fun `checkAndApply names and enables the profile, and check does not`() {
        val admin = FakePolicyAdmin()
        gate(admin, required = true, debug = false).check()
        assertEquals(0, admin.profileEnabledCalls)
        gate(admin, required = true, debug = false).checkAndApply()
        assertEquals(1, admin.profileEnabledCalls)
        assertEquals("TOMS", admin.profileName)
    }

    @Test
    fun `an app outside a work profile never tries to name or enable one`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false)
        gate(admin, required = true, debug = false).checkAndApply()
        assertEquals(0, admin.profileEnabledCalls)
    }

    @Test
    fun `check reads and checkAndApply sets`() {
        val admin = FakePolicyAdmin()
        gate(admin, required = true, debug = false).check()
        assertFalse(admin.uninstallIsBlocked)
        gate(admin, required = true, debug = false).checkAndApply()
        assertTrue(admin.uninstallIsBlocked)
    }

    @Test
    fun `the policies show on the check even outside a profile, all off`() {
        val check = gate(FakePolicyAdmin(owner = false, managedProfile = false), required = true, debug = false).checkAndApply()
        assertEquals(5, check.policies.size)
        assertEquals(PolicyState.INACTIVE, check.policies.single { it.id == PolicyId.UNINSTALL_BLOCKED }.state)
    }
}
