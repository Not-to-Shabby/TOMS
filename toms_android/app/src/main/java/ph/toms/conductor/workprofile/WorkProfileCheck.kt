package ph.toms.conductor.workprofile

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ph.toms.conductor.BuildConfig

/** Where the app is running, as far as Android's profile ownership goes. */
enum class ProfileStatus {
    /** The intended setup: inside a work profile, and TOMS is that profile's owner. */
    OWNER_IN_WORK_PROFILE,

    /** Inside a work profile, but TOMS is not its owner, so no policy can be set. */
    WORK_PROFILE_NOT_OWNER,

    /** TOMS is a profile owner of the main user, not of a work profile. Policies apply to the whole main user, not to a work profile. */
    OWNER_OUTSIDE_WORK_PROFILE,

    /** An ordinary install: no work profile and no owner. */
    NOT_IN_PROFILE,

    /** This phone does not offer work profiles at all. */
    NOT_SUPPORTED,
}

/** What the first-run check shows. All text is plain words for the conductor and the researcher. */
data class WorkProfileCheck(
    val status: ProfileStatus,
    val headline: String,
    val detail: String,
    val policies: List<PolicyItem>,
    /** True in a release build: the app does not go past the check unless it runs as intended (a debug build sets it with -PrequireWorkProfile=true). */
    val required: Boolean,
    /** True when this build may carry on without the profile (debug builds only). */
    val canContinueWithoutProfile: Boolean,
    val canContinue: Boolean,
)

/**
 * Reads where the app runs and which policies are active, and decides whether the app may carry on.
 *
 * A release build needs the intended setup. A debug build never does, so a development phone without a profile still runs; there
 * it shows the same check but offers "Continue without a work profile". [required] can be turned on in a debug build with
 * `-PrequireWorkProfile=true`, to try the gate on a development phone.
 */
class WorkProfileGate(
    private val admin: PolicyAdmin,
    private val required: Boolean = BuildConfig.REQUIRE_WORK_PROFILE,
    private val debugBuilds: Boolean = BuildConfig.DEBUG,
    private val policies: WorkProfilePolicies = WorkProfilePolicies(admin),
) {

    /**
     * Names and enables the work profile if provisioning left it off, sets the policies the app can set on itself (when it is the
     * owner), then reads the whole picture. The adb setup route sends no "provisioning complete" broadcast, so this runs at every
     * launch as well; each step is harmless to repeat.
     */
    fun checkAndApply(): WorkProfileCheck {
        admin.finishProfileSetup(TomsDeviceAdminReceiver.PROFILE_NAME)
        return build(policies.apply())
    }

    /** Reads the picture without changing anything. */
    fun check(): WorkProfileCheck = build(policies.report())

    private fun build(items: List<PolicyItem>): WorkProfileCheck {
        val status = status()
        val (headline, detail) = when (status) {
            ProfileStatus.OWNER_IN_WORK_PROFILE ->
                "TOMS is running in a work profile." to
                    "TOMS manages this work profile itself. The policies below are the ones it keeps on."
            ProfileStatus.WORK_PROFILE_NOT_OWNER ->
                "TOMS is in a work profile, but does not manage it." to
                    "Another app owns this work profile, so TOMS cannot set any policy. The research setup needs TOMS to own it."
            ProfileStatus.OWNER_OUTSIDE_WORK_PROFILE ->
                "TOMS manages this phone's main profile, not a work profile." to
                    "The policies below apply to the whole main profile. The intended setup is a work profile."
            ProfileStatus.NOT_IN_PROFILE ->
                "TOMS is not running in a work profile." to
                    "No policy is active. The app can be uninstalled, its data cleared, and the screen captured."
            ProfileStatus.NOT_SUPPORTED ->
                "This phone does not support work profiles." to
                    "TOMS cannot protect itself here. Use a phone that supports work profiles."
        }
        val intended = status == ProfileStatus.OWNER_IN_WORK_PROFILE
        return WorkProfileCheck(
            status = status,
            headline = headline,
            detail = detail,
            policies = items,
            required = required,
            // The way past the gate for a development phone. A release build never offers it.
            canContinueWithoutProfile = debugBuilds && required && !intended,
            canContinue = intended || !required,
        )
    }

    private fun status(): ProfileStatus {
        val owner = admin.isProfileOwner()
        val managed = admin.isManagedProfile()
        return when {
            owner && managed -> ProfileStatus.OWNER_IN_WORK_PROFILE
            managed -> ProfileStatus.WORK_PROFILE_NOT_OWNER
            owner -> ProfileStatus.OWNER_OUTSIDE_WORK_PROFILE
            !admin.supportsManagedProfiles() -> ProfileStatus.NOT_SUPPORTED
            else -> ProfileStatus.NOT_IN_PROFILE
        }
    }
}

/** Remembers that the first-run check was read, so a phone that already runs as intended is not asked again. */
interface WorkProfileAck {
    var acknowledged: Boolean

    /** The debug-only choice to carry on without a profile. [WorkProfileCheck.canContinueWithoutProfile] decides whether it counts. */
    var debugContinueWithoutProfile: Boolean
}

@Singleton
class PrefsWorkProfileAck @Inject constructor(
    @ApplicationContext context: Context,
) : WorkProfileAck {
    private val prefs: SharedPreferences = context.getSharedPreferences("toms_work_profile", Context.MODE_PRIVATE)

    override var acknowledged: Boolean
        get() = prefs.getBoolean(KEY_ACK, false)
        set(value) = prefs.edit().putBoolean(KEY_ACK, value).apply()

    override var debugContinueWithoutProfile: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_SKIP, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_SKIP, value).apply()

    private companion object {
        const val KEY_ACK = "acknowledged"
        const val KEY_DEBUG_SKIP = "debug_skip"
    }
}
