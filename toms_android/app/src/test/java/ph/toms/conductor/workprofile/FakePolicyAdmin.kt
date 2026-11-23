package ph.toms.conductor.workprofile

/**
 * A stand-in for Android's device policy service that follows the rules the platform source states for a profile owner: only the
 * owner may set a policy (a `SecurityException` otherwise), a restriction can be cleared only if the owner set it, and some
 * versions have no such policy. Tests change [owner], [managedProfile] and the refusals to walk through each situation.
 */
class FakePolicyAdmin(
    var owner: Boolean = true,
    var managedProfile: Boolean = true,
    var managedProfilesSupported: Boolean = true,
) : PolicyAdmin {

    var uninstallIsBlocked = false
    var userControlIsOff = false
    var screenCaptureIsOff = false

    /** Restrictions in force from anywhere, and which of them this app set itself. */
    val restrictions = mutableSetOf<String>()
    val setByToms = mutableSetOf<String>()

    /** Policies the fake refuses with a SecurityException, whatever the caller. */
    val refuse = mutableSetOf<String>()

    val calls = mutableListOf<String>()
    var profileName: String? = null
    var profileEnabledCalls = 0

    private fun guard(name: String) {
        calls += name
        if (!owner) throw SecurityException("not a profile owner: $name")
        if (name in refuse) throw SecurityException("refused: $name")
    }

    override fun supportsManagedProfiles() = managedProfilesSupported
    override fun isProfileOwner() = owner
    override fun isManagedProfile() = managedProfile

    override fun setUninstallBlocked(blocked: Boolean) { guard("setUninstallBlocked"); uninstallIsBlocked = blocked }
    override fun isUninstallBlocked() = uninstallIsBlocked

    override fun setUserControlDisabled(disabled: Boolean) { guard("setUserControlDisabled"); userControlIsOff = disabled }
    override fun isUserControlDisabled() = userControlIsOff

    override fun setScreenCaptureDisabled(disabled: Boolean) { guard("setScreenCaptureDisabled"); screenCaptureIsOff = disabled }
    override fun isScreenCaptureDisabled() = screenCaptureIsOff

    override fun hasUserRestriction(key: String) = key in restrictions

    override fun clearUserRestriction(key: String) {
        guard("clearUserRestriction:$key")
        // A profile owner can only clear what it set; anything else stays, with no error.
        if (key in setByToms) { restrictions.remove(key); setByToms.remove(key) }
    }

    override fun finishProfileSetup(name: String) {
        calls += "finishProfileSetup"
        if (owner && managedProfile) { profileName = name; profileEnabledCalls++ }
    }
}
