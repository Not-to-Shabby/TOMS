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

    /**
     * Why the last attempt to write a key to the machine did not work, in plain words, until the next attempt or until the machine's
     * messages verify. Null when the last attempt worked or none was made. A mismatch has its own text from the trust state.
     */
    @Volatile private var failureNote: RmKeyStatus? = null

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
        failureNote = null
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
                            failureNote = null
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
            failureNote = noteFor(result)
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

    /** What the conductor reads when a write of the key did not work. Never a key, a status byte or a command name. */
    private fun noteFor(result: RmProvisionResult): RmKeyStatus? = when (result) {
        RmProvisionResult.FLASH_ERROR ->
            RmKeyStatus(
                "The Return Machine could not save the new key. Try again; if it keeps happening, the machine needs an administrator.",
                RmKeyStatus.Severity.BAD,
            )
        RmProvisionResult.REFUSED ->
            RmKeyStatus(
                "The Return Machine refused the new key. Check that this phone is paired with it securely (hold its button while " +
                    "switching it on, then pair again), and try again.",
                RmKeyStatus.Severity.BAD,
            )
        RmProvisionResult.NO_REPLY ->
            RmKeyStatus("The Return Machine did not answer about the new key yet. The phone will ask again.", RmKeyStatus.Severity.WARN)
        else -> null
    }

    /** The one place the status text is composed, from the trust state and whether a machine is connected. */
    private fun publishStatus() {
        val connected = machineId != null
        val note = failureNote
        if (note != null && connected && trust.state.value !is RmTrustState.Mismatch) {
            _status.value = note
            return
        }
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
            "The Return Machine keeps a different key than the one this phone holds (it was reset or set up from somewhere else). " +
                "The new key could not be sent because it must be signed under the machine's current key. " +
                "An administrator must clear its key storage."
        "rotation_needs_reflash" ->
            "The key was changed on the server, but this Return Machine could not take the new one (its software is too old to " +
                "replace a key). An administrator must update it or clear its key storage."
        else -> "The Return Machine's messages are not arriving signed with the key the server issued."
    }

    private companion object {
        /** 15 s, 30 s, 1 min, 2 min, 5 min, then every 5 min. */
        val BACKOFF_MS = longArrayOf(15_000, 30_000, 60_000, 120_000, 300_000)
    }
}
