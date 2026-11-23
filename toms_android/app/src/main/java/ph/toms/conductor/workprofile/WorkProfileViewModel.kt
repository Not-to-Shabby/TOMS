package ph.toms.conductor.workprofile

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the first-run check screen needs: the picture, and whether the app may go past it. */
data class WorkProfileUiState(
    val check: WorkProfileCheck,
    /** True when the check screen is done with: the app goes on to sign-in. */
    val passed: Boolean,
)

/**
 * Runs the work-profile check at every launch. The policies are set again each time (the adb route sends no provisioning-complete
 * broadcast, and an update can clear one), the picture is shown on the first run and whenever the app runs outside what it needs.
 */
@HiltViewModel
class WorkProfileViewModel @Inject constructor(
    private val gate: WorkProfileGate,
    private val ack: WorkProfileAck,
) : ViewModel() {

    private val _state = MutableStateFlow(compute(gate.checkAndApply()))
    val state: StateFlow<WorkProfileUiState> = _state.asStateFlow()

    /** The conductor read the check and it is fine: remembered, so the screen is not shown again while all is well. */
    fun continueApp() {
        if (!_state.value.check.canContinue) return
        ack.acknowledged = true
        _state.value = compute(gate.check())
    }

    /** Debug builds only: carry on with no work profile on a development phone. A release build's check never offers it. */
    fun continueWithoutProfile() {
        if (!_state.value.check.canContinueWithoutProfile) return
        ack.debugContinueWithoutProfile = true
        _state.value = compute(gate.check())
    }

    /** Reads everything again, after the researcher finished the setup steps and came back to the app. */
    fun recheck() {
        _state.value = compute(gate.checkAndApply())
    }

    private fun compute(check: WorkProfileCheck): WorkProfileUiState {
        val intended = check.status == ProfileStatus.OWNER_IN_WORK_PROFILE
        val passed = when {
            check.canContinueWithoutProfile -> ack.debugContinueWithoutProfile
            // Not required (a debug build): shown once, then out of the way.
            !check.required -> ack.acknowledged
            // Required: a phone running as intended is shown the picture once; anything else is stopped here every time.
            else -> intended && ack.acknowledged
        }
        return WorkProfileUiState(check, passed)
    }
}
