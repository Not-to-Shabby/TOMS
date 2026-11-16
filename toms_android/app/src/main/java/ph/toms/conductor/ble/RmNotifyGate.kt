package ph.toms.conductor.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The verification pipeline every card_read, heartbeat and rm_ack notify passes through before the
 * phone believes it (firmware/PROTOCOL.md section 6). Both the real link and the in-app fake run
 * their bytes through one of these, so the policy has exactly one implementation.
 *
 * Policy, for a machine with the signing key active:
 *  - exact payload + valid trailer: accepted;
 *  - unsigned or bad trailer: dropped and counted — or accepted-and-counted while the debug-only
 *    [RmMachineTrust.allowUnverified] switch is on;
 *  - anything whose length is neither the payload nor payload+trailer: dropped and counted, always.
 * Without an active key, unsigned notifies pass (the machine does not sign yet) and signed ones are
 * dropped: bytes the phone cannot verify must not reach the app.
 *
 * Drops are logged by the owner with the reason and the characteristic only — never the key, the
 * payload or the trailer — and every drop is counted in [droppedCount], whatever the policy decided.
 */
class RmNotifyGate(
    private val trust: RmMachineTrust,
    private val machineId: () -> String?,
    private val onDrop: (reason: String, suffix: Int) -> Unit,
) {

    private val _droppedCount = MutableStateFlow(0)
    val droppedCount: StateFlow<Int> = _droppedCount.asStateFlow()

    /** The notify's payload to decode further, or null when it must be dropped. */
    fun accept(suffix: Int, value: ByteArray): ByteArray? {
        val framed = RmProtocol.frame(suffix, value) ?: return counted("bad size", suffix)

        val id = machineId() ?: return counted("no machine id", suffix)
        val key = trust.activeKey(id)
        val trailer = framed.trailer

        if (trailer == null) {
            return when {
                key != null && !trust.allowUnverified -> counted("unsigned while key active", suffix)
                key != null -> counted("unsigned accepted by the debug switch", suffix, framed.payload)
                else -> framed.payload
            }
        }

        val verified = key != null && trust.verifyAllowed(id, suffix, framed.payload, trailer)
        return when {
            verified -> framed.payload
            trust.allowUnverified -> counted("bad signature accepted by the debug switch", suffix, framed.payload)
            else -> counted(if (key == null) "signed but no key" else "bad signature", suffix)
        }
    }

    private fun counted(reason: String, suffix: Int, accept: ByteArray? = null): ByteArray? {
        _droppedCount.value += 1
        onDrop(reason, suffix)
        return accept
    }
}
