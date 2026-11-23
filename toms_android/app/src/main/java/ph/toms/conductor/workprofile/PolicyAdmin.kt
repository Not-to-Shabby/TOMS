package ph.toms.conductor.workprofile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The few things TOMS asks of Android's device policy service, as a seam: the policy logic and the first-run check run against
 * this, so they can be tested with a fake and never need a real profile. Every method acts on the user the app runs in (the work
 * profile, when it is in one) and nothing else; a profile owner has no say outside its own profile.
 */
interface PolicyAdmin {
    /** Whether this phone declares the work-profile feature at all (some vendor builds leave it out). */
    fun supportsManagedProfiles(): Boolean

    /** Whether TOMS is the profile owner of the user it runs in. */
    fun isProfileOwner(): Boolean

    /** Whether the user TOMS runs in is a work (managed) profile. */
    fun isManagedProfile(): Boolean

    fun setUninstallBlocked(blocked: Boolean)
    fun isUninstallBlocked(): Boolean

    /** Stops the user clearing the app's data or force-stopping it (Android 11+). */
    fun setUserControlDisabled(disabled: Boolean)
    fun isUserControlDisabled(): Boolean

    fun setScreenCaptureDisabled(disabled: Boolean)
    fun isScreenCaptureDisabled(): Boolean

    /** Any user restriction on this profile, whoever set it. Readable by any app. */
    fun hasUserRestriction(key: String): Boolean

    /** Clears a restriction this app set. A profile owner can only clear its own. */
    fun clearUserRestriction(key: String)

    /** Names the profile and turns it on if provisioning left it off; harmless when it is already on. */
    fun finishProfileSetup(name: String)
}

@Singleton
class AndroidPolicyAdmin @Inject constructor(
    @ApplicationContext private val context: Context,
) : PolicyAdmin {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val users = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val admin = ComponentName(context, TomsDeviceAdminReceiver::class.java)

    override fun supportsManagedProfiles(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)

    override fun isProfileOwner(): Boolean = dpm.isProfileOwnerApp(context.packageName)

    override fun isManagedProfile(): Boolean = when {
        // The admin-scoped call (API 24) is only allowed to the profile owner itself.
        isProfileOwner() -> dpm.isManagedProfile(admin)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> users.isManagedProfile
        else -> false
    }

    override fun setUninstallBlocked(blocked: Boolean) = dpm.setUninstallBlocked(admin, context.packageName, blocked)

    override fun isUninstallBlocked(): Boolean = dpm.isUninstallBlocked(admin, context.packageName)

    override fun setUserControlDisabled(disabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        dpm.setUserControlDisabledPackages(admin, if (disabled) listOf(context.packageName) else emptyList())
    }

    override fun isUserControlDisabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            dpm.getUserControlDisabledPackages(admin).contains(context.packageName)

    override fun setScreenCaptureDisabled(disabled: Boolean) = dpm.setScreenCaptureDisabled(admin, disabled)

    override fun isScreenCaptureDisabled(): Boolean = dpm.getScreenCaptureDisabled(admin)

    override fun hasUserRestriction(key: String): Boolean = users.hasUserRestriction(key)

    override fun clearUserRestriction(key: String) = dpm.clearUserRestriction(admin, key)

    override fun finishProfileSetup(name: String) {
        if (!isProfileOwner() || !isManagedProfile()) return
        runCatching { dpm.setProfileName(admin, name) }
        // The platform logs and returns when the profile is already enabled, so this is safe to repeat.
        runCatching { dpm.setProfileEnabled(admin) }
    }
}
