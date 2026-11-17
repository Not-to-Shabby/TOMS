package ph.toms.conductor.ble

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the conductor reads about the Return Machine's key, in plain words. Never a key, a hash or a byte of one.
 */
data class RmKeyStatus(val text: String, val severity: Severity) {
    enum class Severity { NEUTRAL, GOOD, WARN, BAD }

    companion object {
        val NONE = RmKeyStatus("Return Machine messages are not signed yet.", Severity.NEUTRAL)
    }
}

/**
 * The enrolment of the Return Machine's signing key on this phone. It runs the loop the server's design
 * lays out (the server issues the key, the phone writes it to the machine, the machine's first signed
 * message verifies it) and keeps going when the link or the server does not cooperate:
 *
 *  - on a connection, fetches the key for this phone's vehicle's machine and writes it to the machine
 *    with diag 06 (once-only: see [RmProvisioner]);
 *  - when a notify verifies under the pending key, the key is active ([onVerified]), and the server is told
 *    (the loop closes; a mismatch flag the dashboard held for this key version clears);
 *  - when the machine keeps a key the phone does not hold, or signs with one that verifies nowhere, it flags
 *    a mismatch ([RmMachineTrust.markMismatch]) and tells the server, so the dashboard shows it;
 *  - when the server cannot be reached, retries with a capped, growing delay (the shift keeps working
 *    unsigned meanwhile): 15 s, 30 s, 1 min, 2 min, 5 min, then every 5 min.
 *
 * A single instance serves the whole app; [ensureProvisioned] is safe to call from several places.
 */
interface RmKeyEnrolment {
    val status: StateFlow<RmKeyStatus>

    /** The connected machine, once a link is up. Starts the provisioning loop. */
    fun onConnected(machineId: String)

    fun onDisconnected()

    /** A signed notify verified under [match]. A pending key becomes active and the server hears about it. */
    fun onVerified(machineId: String, match: RmKeyMatch)

    /** The machine signs with a key the phone does not hold (or with one that verifies nowhere). */
    fun onUnknownSignature(machineId: String)

    /** Gives up the retry loop (shift ended, app stopping). */
    fun stop()
}

@Singleton
class RealRmKeyEnrolment @Inject constructor(
    private val provisioner: RmProvisioner,
    private val trust: RmMachineTrust,
    private val keys: RmKeyService,
) : RmKeyEnrolment {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loop: Job? = null

    /** Replaced by a test to run the backoff without waiting. */
    internal var delayMillis: suspend (Long) -> Unit = { delay(it) }

    private val _status = MutableStateFlow(RmKeyStatus.NONE)
    override val status: StateFlow<RmKeyStatus> = _status.asStateFlow()

    @Volatile private var machineId: String? = null

    /** Whether the server has been told the current key verified, so a stream of verified notifies does not repeat it. */
    private var reportedVerifiedVersion = 0

    override fun onConnected(machineId: String) {
        this.machineId = machineId
        publishStatus()
        loop?.cancel()
        loop = scope.launch { provisionWithBackoff(machineId) }
    }

    override fun onDisconnected() {
        loop?.cancel()
        loop = null
        machineId = null
        // The trust state still tells the truth about the key; only the connection-dependent text changes.
        publishStatus()
    }

    override fun stop() {
        loop?.cancel()
        loop = null
    }

    override fun onVerified(machineId: String, match: RmKeyMatch) {
        scope.launch {
            mutex.withLock {
                when (match) {
                    RmKeyMatch.PENDING -> {
                        // The machine took the new key: pending becomes active, and the old key is dropped.
                        val version = trust.pendingVersion(machineId) ?: return@withLock
                        if (trust.activate(machineId)) {
                            reportedVerifiedVersion = version
                            keys.reportVerified(version)
                        }
                    }
                    RmKeyMatch.ACTIVE -> {
                        // Steady state: closes the loop once per key version, and clears a stale mismatch flag.
                        val version = trust.activeVersion(machineId) ?: return@withLock
                        if (trust.mismatchReason(machineId) != null) trust.clearMismatch(machineId)
                        if (version != reportedVerifiedVersion) {
                            reportedVerifiedVersion = version
                            keys.reportVerified(version)
                        }
                    }
                    RmKeyMatch.PREVIOUS -> {
                        // Still on the old key: the machine has not taken the new one yet. Keep trying; do not flag.
                    }
                }
                publishStatus()
            }
        }
    }

    override fun onUnknownSignature(machineId: String) {
        scope.launch {
            mutex.withLock {
                // The phone has no key for this machine yet, or the key it has does not verify: the machine
                // holds a key from somewhere else (or lost its own). Flag it once; the dashboard and the phone say so.
                val reason = if (trust.hasAnyKey(machineId)) "no_signed_messages" else "machine_has_other_key"
                if (trust.mismatchReason(machineId) != reason) {
                    trust.markMismatch(machineId, reason)
                    keys.reportMismatch(reason)
                }
                publishStatus()
            }
        }
    }

    private suspend fun provisionWithBackoff(machineId: String) {
        var attempt = 0
        while (true) {
            val result = mutex.withLock { provisioner.provisionConnectedMachine() }
            publishStatus()
            when (result) {
                RmProvisionResult.WRITTEN -> return
                RmProvisionResult.MACHINE_KEPT_ITS_KEY -> return // a mismatch: only a person can fix it, retrying would not
                RmProvisionResult.REFUSED, RmProvisionResult.FLASH_ERROR -> return // the machine said no; not a fault to retry in a loop
                RmProvisionResult.NOT_POSSIBLE, RmProvisionResult.NO_REPLY -> {
                    // Offline, not registered yet, or the machine has not answered: ask again, but not quickly.
                    delayMillis(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)])
                    attempt++
                    if (this.machineId != machineId) return // the machine changed or disconnected meanwhile
                }
            }
        }
    }

    /** The one place the status text is composed, from the trust state and whether a machine is connected. */
    private fun publishStatus() {
        val connected = machineId != null
        _status.value = when (val s = trust.state.value) {
            RmTrustState.NoKey ->
                if (connected) RmKeyStatus("Waiting for a key for this Return Machine from the server.", RmKeyStatus.Severity.WARN)
                else RmKeyStatus.NONE
            is RmTrustState.Pending ->
                RmKeyStatus("Key v${s.keyVersion} written to the Return Machine; waiting for its first signed message.", RmKeyStatus.Severity.WARN)
            is RmTrustState.Active ->
                RmKeyStatus("Return Machine messages are verified (key v${s.keyVersion}).", RmKeyStatus.Severity.GOOD)
            is RmTrustState.Mismatch -> RmKeyStatus(mismatchText(s.reason), RmKeyStatus.Severity.BAD)
        }
    }

    private fun mismatchText(reason: String) = when (reason) {
        "machine_has_other_key" ->
            "The Return Machine keeps a different key than the server issued (it was reset or re-flashed). " +
                "Its messages cannot be verified. An administrator must clear its key storage."
        "rotation_needs_reflash" ->
            "The key was rotated but the Return Machine still holds the old one and cannot replace it over the air. " +
                "An administrator must clear its key storage."
        else -> "The Return Machine's messages are not arriving signed with the key the server issued."
    }

    private companion object {
        /** 15 s, 30 s, 1 min, 2 min, 5 min, then every 5 min. */
        val BACKOFF_MS = longArrayOf(15_000, 30_000, 60_000, 120_000, 300_000)
    }
}
