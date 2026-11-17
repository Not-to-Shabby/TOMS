package ph.toms.conductor.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Records the enrolment calls the view model makes, without the real provisioning loop. */
class FakeRmKeyEnrolment : RmKeyEnrolment {
    private val _status = MutableStateFlow(RmKeyStatus.NONE)
    override val status: StateFlow<RmKeyStatus> = _status.asStateFlow()

    val connected = mutableListOf<String>()
    var disconnects = 0
    val verified = mutableListOf<Pair<String, RmKeyMatch>>()
    val unknownSignatures = mutableListOf<String>()

    override fun onConnected(machineId: String) { connected.add(machineId) }
    override fun onDisconnected() { disconnects++ }
    override fun onVerified(machineId: String, match: RmKeyMatch) { verified.add(machineId to match) }
    override fun onUnknownSignature(machineId: String) { unknownSignatures.add(machineId) }
    override fun stop() = Unit

    fun setStatus(status: RmKeyStatus) { _status.value = status }
}
