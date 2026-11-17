package ph.toms.conductor.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The verification pipeline every card_read, heartbeat and rm_ack notify passes through before the
 * phone believes it (firmware/PROTOCOL.md section 6). Both the real link and the in-app fake run
 * their bytes through one of these, so the policy has exactly one implementation.
 *
 * Policy, for a machine with a key (pending or active) on the phone:
 *  - exact payload + a trailer that verifies under any currently valid key: accepted. A trailer that
 *    verifies under the pending key is the proof that the machine took the key: [onVerified] fires with
 *    [RmKeyMatch.PENDING] and the owner activates it;
 *  - unsigned or bad trailer: dropped and counted — or accepted-and-counted while the debug-only
 *    [RmMachineTrust.allowUnverified] switch is on. While a key is only pending, unsigned notifies still
 *    pass: the machine may not have taken the key yet, and refusing everything would break a working link;
 *  - anything whose length is neither the payload nor payload+trailer: dropped and counted, always.
 * Without any key, unsigned notifies pass (the machine does not sign yet) and signed ones are dropped:
 * bytes the phone cannot verify must not reach the app. A signed notify with no key known is also the
 * sign of a machine that was provisioned from somewhere else: [onSignedWithoutKey] lets the owner flag it.
 *
 * Drops are logged by the owner with the reason and the characteristic only — never the key, the
 * payload or the trailer — and every drop is counted in [droppedCount], whatever the policy decided.
 */
class RmNotifyGate(
    private val trust: RmMachineTrust,
    private val machineId: () -> String?,
    private val onDrop: (reason: String, suffix: Int) -> Unit,
    /** Called once per message that verified, with which key matched. */
    private val onVerified: (machineId: String, match: RmKeyMatch) -> Unit = { _, _ -> },
    /** Called when a signed notify arrives and the phone holds no key for the machine (it has one the phone does not know). */
    private val onSignedWithoutKey: (machineId: String) -> Unit = {},
    /** Called when a machine with a key keeps sending messages that do not verify under any valid key. */
    private val onBadSignature: (machineId: String) -> Unit = {},
) {

    private val _droppedCount = MutableStateFlow(0)
    val droppedCount: StateFlow<Int> = _droppedCount.asStateFlow()

    /** The notify's payload to decode further, or null when it must be dropped. */
    fun accept(suffix: Int, value: ByteArray): ByteArray? {
        val framed = RmProtocol.frame(suffix, value) ?: return counted("bad size", suffix)

        val id = machineId() ?: return counted("no machine id", suffix)
        val trailer = framed.trailer
        val hasKey = trust.hasAnyKey(id)
        val activeKey = trust.activeVersion(id) != null

        if (trailer == null) {
            return when {
                // Active key and no trailer: a machine that signs always signs. Refuse.
                activeKey && !trust.allowUnverified -> counted("unsigned while key active", suffix)
                activeKey -> counted("unsigned accepted by the debug switch", suffix, framed.payload)
                // Pending key, or none: the machine may not have taken a key yet.
                else -> framed.payload
            }
        }

        val match = if (hasKey) trust.verifyUnder(id, suffix, framed.payload, framed.trailer) else null
        if (match != null) {
            onVerified(id, match)
            return framed.payload
        }

        if (!hasKey) onSignedWithoutKey(id) else onBadSignature(id)
        return when {
            trust.allowUnverified -> counted("bad signature accepted by the debug switch", suffix, framed.payload)
            else -> counted(if (!hasKey) "signed but no key" else "bad signature", suffix)
        }
    }

    private fun counted(reason: String, suffix: Int, accept: ByteArray? = null): ByteArray? {
        _droppedCount.value += 1
        onDrop(reason, suffix)
        return accept
    }
}
