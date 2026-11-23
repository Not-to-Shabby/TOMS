package ph.toms.conductor.workprofile

import android.os.Build
import android.os.UserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The policy logic against a fake device policy service. Nothing here runs on a phone. */
class WorkProfilePoliciesTest {

    private fun policies(admin: FakePolicyAdmin, sdk: Int = Build.VERSION_CODES.TIRAMISU) = WorkProfilePolicies(admin, sdk)

    private fun List<PolicyItem>.of(id: PolicyId) = single { it.id == id }

    @Test
    fun `the owner of a work profile gets uninstall, user control and screen capture policies set and read back as on`() {
        val admin = FakePolicyAdmin()
        val items = policies(admin).apply()

        assertTrue(admin.uninstallIsBlocked)
        assertTrue(admin.userControlIsOff)
        assertTrue(admin.screenCaptureIsOff)
        assertEquals(PolicyState.ACTIVE, items.of(PolicyId.UNINSTALL_BLOCKED).state)
        assertEquals(PolicyState.ACTIVE, items.of(PolicyId.USER_CONTROL_DISABLED).state)
        assertEquals(PolicyState.ACTIVE, items.of(PolicyId.SCREEN_CAPTURE_DISABLED).state)
        assertEquals(PolicyState.ACTIVE, items.of(PolicyId.LOCATION_ALLOWED).state)
        assertEquals(PolicyState.ACTIVE, items.of(PolicyId.BLUETOOTH_ALLOWED).state)
    }

    @Test
    fun `setting the policies twice leaves the same state`() {
        val admin = FakePolicyAdmin()
        val first = policies(admin).apply()
        val second = policies(admin).apply()
        assertEquals(first, second)
    }

    @Test
    fun `an app that is not the owner sets nothing and every owned policy reads as off with the reason in plain words`() {
        val admin = FakePolicyAdmin(owner = false, managedProfile = false)
        val items = policies(admin).apply()

        assertTrue("no policy call at all without ownership", admin.calls.isEmpty())
        listOf(PolicyId.UNINSTALL_BLOCKED, PolicyId.USER_CONTROL_DISABLED, PolicyId.SCREEN_CAPTURE_DISABLED).forEach {
            assertEquals(PolicyState.INACTIVE, items.of(it).state)
            assertTrue(items.of(it).text.contains("not the owner"))
        }
    }

    @Test
    fun `user control is not supported before Android 11 and is never called there`() {
        val admin = FakePolicyAdmin()
        val items = policies(admin, sdk = Build.VERSION_CODES.Q).apply()

        assertEquals(PolicyState.NOT_SUPPORTED, items.of(PolicyId.USER_CONTROL_DISABLED).state)
        assertFalse(admin.calls.contains("setUserControlDisabled"))
        assertEquals("the other policies still work", PolicyState.ACTIVE, items.of(PolicyId.UNINSTALL_BLOCKED).state)
    }

    @Test
    fun `a call Android refuses shows as failed with its reason, never as on`() {
        val admin = FakePolicyAdmin().apply { refuse += "setScreenCaptureDisabled" }
        val items = policies(admin).apply()

        val capture = items.of(PolicyId.SCREEN_CAPTURE_DISABLED)
        assertEquals(PolicyState.FAILED, capture.state)
        assertTrue(capture.text.contains("refused"))
        assertEquals("one refusal does not stop the others", PolicyState.ACTIVE, items.of(PolicyId.UNINSTALL_BLOCKED).state)
    }

    @Test
    fun `a restriction TOMS set on location or Bluetooth is cleared`() {
        val admin = FakePolicyAdmin().apply {
            restrictions += UserManager.DISALLOW_SHARE_LOCATION
            setByToms += UserManager.DISALLOW_SHARE_LOCATION
        }
        val items = policies(admin).apply()
        assertEquals(PolicyState.ACTIVE, items.of(PolicyId.LOCATION_ALLOWED).state)
        assertFalse(admin.restrictions.contains(UserManager.DISALLOW_SHARE_LOCATION))
    }

    @Test
    fun `a location or Bluetooth restriction from somewhere else is shown as blocked, not hidden`() {
        val admin = FakePolicyAdmin().apply {
            restrictions += UserManager.DISALLOW_SHARE_LOCATION
            restrictions += UserManager.DISALLOW_BLUETOOTH
        }
        val items = policies(admin).apply()

        assertEquals(PolicyState.INACTIVE, items.of(PolicyId.LOCATION_ALLOWED).state)
        assertTrue(items.of(PolicyId.LOCATION_ALLOWED).text.contains("blocked"))
        assertEquals(PolicyState.INACTIVE, items.of(PolicyId.BLUETOOTH_ALLOWED).state)
    }

    @Test
    fun `report reads without changing anything`() {
        val admin = FakePolicyAdmin()
        policies(admin).report()
        assertTrue(admin.calls.isEmpty())
        assertFalse(admin.uninstallIsBlocked)
    }

    @Test
    fun `no text names a class, a permission or a constant`() {
        val admin = FakePolicyAdmin().apply { refuse += "setUninstallBlocked" }
        val words = policies(admin).apply().joinToString(" ") { it.text }
        listOf("DevicePolicyManager", "DISALLOW_", "SecurityException", "android.").forEach {
            assertFalse("text mentions $it", words.contains(it))
        }
    }
}
