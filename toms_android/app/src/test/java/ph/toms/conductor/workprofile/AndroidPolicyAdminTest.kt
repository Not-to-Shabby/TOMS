package ph.toms.conductor.workprofile

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The real classes against Robolectric's stand-in for the platform: the manifest declares the receiver the way Android needs it, and
 * the real [AndroidPolicyAdmin] reads the owner state. Robolectric does not enforce who may call a policy, so this does not prove
 * the policies hold on a phone; that is what the checklist's unticked boxes are for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidPolicyAdminTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val component = ComponentName(context, TomsDeviceAdminReceiver::class.java)

    @Test
    fun `the manifest declares the receiver with the bind permission, the policy file and the two actions Android sends`() {
        val pm = context.packageManager
        val info = pm.getReceiverInfo(component, PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_DEVICE_ADMIN", info.permission)
        assertNotNull(info.metaData)
        assertTrue(info.metaData.containsKey(DeviceAdminReceiver.DEVICE_ADMIN_META_DATA))

        listOf(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED, "android.app.action.PROFILE_PROVISIONING_COMPLETE").forEach { action ->
            val found = pm.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertTrue("no receiver for $action", found.any { it.activityInfo.name == TomsDeviceAdminReceiver::class.java.name })
        }
    }

    @Test
    fun `the real admin reports an ordinary install as no owner and no profile`() {
        val admin = AndroidPolicyAdmin(context)
        assertFalse(admin.isProfileOwner())
        assertFalse(admin.isManagedProfile())
    }

    @Test
    fun `the real admin reports the owner state the platform gives it`() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setProfileOwner(component)
        val admin = AndroidPolicyAdmin(context)
        assertTrue(admin.isProfileOwner())
        // An owner of the main user is not a work profile.
        assertFalse(admin.isManagedProfile())
    }

    @Test
    fun `the full check on an ordinary install reads without throwing`() {
        val check = WorkProfileGate(AndroidPolicyAdmin(context), required = true, debugBuilds = false).check()
        // Robolectric may or may not declare the work-profile feature; either way there is no owner and no profile.
        assertTrue(check.status == ProfileStatus.NOT_IN_PROFILE || check.status == ProfileStatus.NOT_SUPPORTED)
        assertFalse(check.canContinue)
    }
}
