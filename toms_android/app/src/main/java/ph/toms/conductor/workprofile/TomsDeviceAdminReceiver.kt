package ph.toms.conductor.workprofile

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import ph.toms.conductor.MainActivity

/**
 * TOMS as the owner of its own work profile. There is no management server: this receiver exists so Android can make TOMS the
 * profile owner (`dpm set-profile-owner`, or provisioning), and so TOMS can set the few policies in [WorkProfilePolicies] on itself.
 *
 * Do not rename or move this class once phones are provisioned: Android stores the owner by component name, and a changed name
 * drops the profile owner.
 */
class TomsDeviceAdminReceiver : DeviceAdminReceiver() {

    /** The system sends this once the work profile is set up. Finish enabling it, set the policies, and open the app. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        WorkProfileGate(AndroidPolicyAdmin(context.applicationContext)).checkAndApply()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Set again whenever the owner role is enabled, so a policy cleared by a restart or an update comes back. */
    override fun onEnabled(context: Context, intent: Intent) {
        WorkProfileGate(AndroidPolicyAdmin(context.applicationContext)).checkAndApply()
    }

    companion object {
        const val PROFILE_NAME = "TOMS"
    }
}
