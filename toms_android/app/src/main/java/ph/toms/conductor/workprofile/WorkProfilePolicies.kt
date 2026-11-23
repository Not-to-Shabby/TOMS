package ph.toms.conductor.workprofile

import android.os.Build
import android.os.UserManager

enum class PolicyId { UNINSTALL_BLOCKED, USER_CONTROL_DISABLED, SCREEN_CAPTURE_DISABLED, LOCATION_ALLOWED, BLUETOOTH_ALLOWED }

enum class PolicyState {
    /** In force right now. */
    ACTIVE,

    /** Not in force (not set, or something blocks it). */
    INACTIVE,

    /** This Android version has no such policy; nothing to do and nothing wrong. */
    NOT_SUPPORTED,

    /** Android refused the call. */
    FAILED,
}

/** One policy as the conductor reads it: [text] is plain words, never a class or permission name. */
data class PolicyItem(val id: PolicyId, val state: PolicyState, val text: String)

/**
 * The policies TOMS sets on itself as the owner of its work profile, and how it reads them back.
 *
 * What it sets, and only that:
 *  - the app cannot be uninstalled from the work profile;
 *  - the user cannot clear its data or force-stop it (Android 11+);
 *  - screen capture is off for the work profile.
 *
 * What it leaves alone on purpose, and only reports: location and Bluetooth. A profile owner can't grant runtime permissions for
 * location (Android 12+) and can't set Bluetooth restrictions outside the main user, so the permission prompts the app already
 * shows stay the way these are granted. It clears any location or Bluetooth restriction it set itself, and reports the state, so a
 * restriction from anywhere else is shown, not hidden.
 *
 * What it can not do, so it does not try: stop the user removing or pausing the work profile, or lock the whole phone to one app.
 * Those belong to a device owner, which this setup deliberately does not use.
 */
class WorkProfilePolicies(
    private val admin: PolicyAdmin,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    /** Sets what can be set, then reads everything back. A call Android refuses shows up as [PolicyState.FAILED], never as success. */
    fun apply(): List<PolicyItem> {
        val failures = mutableMapOf<PolicyId, String>()
        if (admin.isProfileOwner()) {
            attempt(failures, PolicyId.UNINSTALL_BLOCKED) { admin.setUninstallBlocked(true) }
            if (sdkInt >= Build.VERSION_CODES.R) {
                attempt(failures, PolicyId.USER_CONTROL_DISABLED) { admin.setUserControlDisabled(true) }
            }
            attempt(failures, PolicyId.SCREEN_CAPTURE_DISABLED) { admin.setScreenCaptureDisabled(true) }
            attempt(failures, PolicyId.LOCATION_ALLOWED) { admin.clearUserRestriction(UserManager.DISALLOW_SHARE_LOCATION) }
            attempt(failures, PolicyId.BLUETOOTH_ALLOWED) { admin.clearUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH) }
        }
        return report(failures)
    }

    /** Reads the current state without changing anything. */
    fun report(failures: Map<PolicyId, String> = emptyMap()): List<PolicyItem> {
        val owner = admin.isProfileOwner()
        val notOwner = "Not set: TOMS is not the owner of this profile."
        fun owned(id: PolicyId, read: () -> Boolean, on: String, off: String): PolicyItem {
            failures[id]?.let { return PolicyItem(id, PolicyState.FAILED, "Android refused this: $it") }
            if (!owner) return PolicyItem(id, PolicyState.INACTIVE, notOwner)
            val active = runCatching(read).getOrElse {
                return PolicyItem(id, PolicyState.FAILED, "Could not be read: ${it.message ?: "unknown error"}")
            }
            return PolicyItem(id, if (active) PolicyState.ACTIVE else PolicyState.INACTIVE, if (active) on else off)
        }

        val userControl = if (sdkInt < Build.VERSION_CODES.R) {
            PolicyItem(
                PolicyId.USER_CONTROL_DISABLED, PolicyState.NOT_SUPPORTED,
                "Clearing data and force-stop cannot be blocked on this Android version.",
            )
        } else {
            owned(
                PolicyId.USER_CONTROL_DISABLED, admin::isUserControlDisabled,
                "The app's data cannot be cleared and the app cannot be force-stopped.",
                "The app's data can be cleared and the app can be force-stopped.",
            )
        }

        return listOf(
            owned(
                PolicyId.UNINSTALL_BLOCKED, admin::isUninstallBlocked,
                "The app cannot be uninstalled from the work profile.",
                "The app can be uninstalled from the work profile.",
            ),
            userControl,
            owned(
                PolicyId.SCREEN_CAPTURE_DISABLED, admin::isScreenCaptureDisabled,
                "Screenshots and screen recording are off in the work profile.",
                "Screenshots and screen recording are allowed.",
            ),
            restriction(PolicyId.LOCATION_ALLOWED, failures, "Location is allowed.", "Location sharing is blocked in this profile.", UserManager.DISALLOW_SHARE_LOCATION),
            restriction(
                PolicyId.BLUETOOTH_ALLOWED, failures, "Bluetooth is allowed.", "Bluetooth is blocked in this profile.",
                UserManager.DISALLOW_CONFIG_BLUETOOTH, UserManager.DISALLOW_BLUETOOTH,
            ),
        )
    }

    private fun restriction(id: PolicyId, failures: Map<PolicyId, String>, allowed: String, blocked: String, vararg keys: String): PolicyItem {
        failures[id]?.let { return PolicyItem(id, PolicyState.FAILED, "Android refused this: $it") }
        val isBlocked = keys.any { runCatching { admin.hasUserRestriction(it) }.getOrDefault(false) }
        return PolicyItem(id, if (isBlocked) PolicyState.INACTIVE else PolicyState.ACTIVE, if (isBlocked) blocked else allowed)
    }

    private fun attempt(failures: MutableMap<PolicyId, String>, id: PolicyId, call: () -> Unit) {
        try {
            call()
        } catch (e: SecurityException) {
            failures[id] = e.message ?: "not allowed"
        } catch (e: RuntimeException) {
            failures[id] = e.message ?: "unexpected error"
        }
    }
}
