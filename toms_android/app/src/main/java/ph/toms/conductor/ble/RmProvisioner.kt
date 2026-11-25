package ph.toms.conductor.ble

import javax.inject.Inject
import javax.inject.Singleton

/** What the server answered when the phone asked for its vehicle's Return Machine key. */
sealed interface RmKeyFetch {
    /** The key (and, inside a rotation's grace window, the previous one). [key] and [previousKey] are 32 bytes. */
    class Issued(
        val machineId: String,
        val keyVersion: Int,
        val key: ByteArray,
        val previousKey: ByteArray?,
        val previousValidUntilMillis: Long?,
    ) : RmKeyFetch

    /** No Return Machine is registered for this vehicle (or the phone has no vehicle): nothing to provision, not a fault. */
    data class NotRegistered(val reason: String) : RmKeyFetch

    /** The server is unreachable, refused the token, or is not set up: try again later. */
    data class Failed(val reason: String) : RmKeyFetch
}

/**
 * The phone's side of the server's Return Machine key routes: fetch the key for this phone's vehicle's
 * machine, and report back what the phone saw. Implemented by the Retrofit uploader; faked in tests.
 */
interface RmKeyService {
    suspend fun fetchKey(): RmKeyFetch
    suspend fun reportMismatch(reason: String): Boolean
    suspend fun reportVerified(keyVersion: Int): Boolean
}

/** No server configured (tests, or a build with no address): every call reports honestly that nothing happened. */
object NoRmKeyService : RmKeyService {
    override suspend fun fetchKey(): RmKeyFetch = RmKeyFetch.Failed("No server is configured.")
    override suspend fun reportMismatch(reason: String): Boolean = false
    override suspend fun reportVerified(keyVersion: Int): Boolean = false
}

enum class RmProvisionResult {
    /** The machine stored the key; it waits as pending until the machine's first signed message verifies. */
    WRITTEN,

    /**
     * The machine keeps a key the phone cannot work with: it holds one the phone does not have (it answered a replace with "not signed
     * under the current key"), or it is an older firmware that cannot replace its key at all.
     */
    MACHINE_KEPT_ITS_KEY,

    /** The machine could not store the key in its settings partition. */
    FLASH_ERROR,

    /** The machine refused: no encrypted link, wrong frame size. */
    REFUSED,

    /** No machine is connected, or the server issued nothing (not registered, offline). */
    NOT_POSSIBLE,

    /** The machine did not answer in time; the key stays pending on the phone. */
    NO_REPLY,
}

/**
 * The phone side of key provisioning: fetch the key from the server, store it pending, write it to the
 * connected machine over the authenticated diag link, and let the machine's first verified signed message
 * activate it.
 *
 * **First key:** diag `06`, once only. **A newer key than the one the phone already trusts (a rotation):** diag `08`
 * (firmware/PROTOCOL.md section 8.4), the new key signed under the key the machine holds, which is the phone's active key. The
 * machine keeps the old key for one step, so the phone keeps verifying and signing with the old key until the first message that
 * verifies under the new one activates it ([RmKeyEnrolment.onVerified]). A replace whose reply was lost is simply sent again: the
 * machine accepts the old key during that step.
 *
 * What each answer to `08` ends in:
 *  - ok: written, waiting for the machine's first message under the new key;
 *  - no key on the machine (it was reset): the new key is written with `06` instead;
 *  - not signed under the current key: the machine holds a key the phone does not have, flagged as a mismatch;
 *  - an answer that is not a real status (a firmware without command 08): flagged as needing a re-flash, never read as success;
 *  - could not be saved / refused / malformed: the pending key is dropped so the next attempt starts from the active key;
 *  - no answer: the pending key stays, and the replace is sent again.
 */
interface RmProvisioner {
    /**
     * Runs one provisioning attempt for the connected machine. Idempotent: calling it again while a key is
     * pending, or after the machine took it, writes nothing twice.
     */
    suspend fun provisionConnectedMachine(): RmProvisionResult
}

@Singleton
class RealRmProvisioner @Inject constructor(
    private val link: ReturnMachineLink,
    private val trust: RmMachineTrust,
    private val keys: RmKeyService,
) : RmProvisioner {

    override suspend fun provisionConnectedMachine(): RmProvisionResult {
        val machineId = (link.connectionState.value as? RmConnectionState.Connected)?.deviceAddress
            ?: return RmProvisionResult.NOT_POSSIBLE

        val issued = (keys.fetchKey() as? RmKeyFetch.Issued) ?: return RmProvisionResult.NOT_POSSIBLE

        // A key the phone already holds at this version needs no new write: the machine has it or the
        // mismatch path already told the server. A new version, or none yet, is what gets provisioned.
        val activeVersion = trust.activeVersion(machineId)
        val pendingVersion = trust.pendingVersion(machineId)
        if (activeVersion == issued.keyVersion) return RmProvisionResult.WRITTEN

        // A newer key than the one the phone trusts: replace it on the machine (diag 08) instead of provisioning (diag 06).
        if (activeVersion != null && issued.keyVersion > activeVersion) {
            return replaceKey(machineId, issued, activeVersion, alreadyPending = pendingVersion == issued.keyVersion)
        }
        if (pendingVersion == issued.keyVersion) return RmProvisionResult.WRITTEN

        val stored = trust.provision(machineId, issued.key, issued.keyVersion, issued.previousValidUntilMillis)
        if (!stored) return RmProvisionResult.NOT_POSSIBLE

        return writeFirstKey(machineId, issued, activeVersion)
    }

    /** A rotation: diag 08, signed under the active key the machine holds. */
    private suspend fun replaceKey(machineId: String, issued: RmKeyFetch.Issued, activeVersion: Int, alreadyPending: Boolean): RmProvisionResult {
        val current = trust.activeKey(machineId) ?: return RmProvisionResult.NOT_POSSIBLE
        if (!alreadyPending && !trust.provision(machineId, issued.key, issued.keyVersion, issued.previousValidUntilMillis)) {
            current.fill(0)
            return RmProvisionResult.NOT_POSSIBLE
        }
        val status = try {
            link.sendKeyReplace(current, issued.key)
        } finally {
            current.fill(0)
        }
        return when (status) {
            RmProtocol.REPLACE_OK -> RmProvisionResult.WRITTEN
            // The machine has no key (it was reset since): nothing to replace, so the new key is written the first-time way.
            RmProtocol.REPLACE_NO_KEY -> writeFirstKey(machineId, issued, activeVersion)
            RmProtocol.REPLACE_BAD_SIGNATURE -> {
                trust.discardPending(machineId)
                flag(machineId, "machine_has_other_key")
            }
            RmProtocol.REPLACE_UNRECOGNISED -> {
                trust.discardPending(machineId)
                flag(machineId, "rotation_needs_reflash")
            }
            RmProtocol.REPLACE_STORAGE -> {
                trust.discardPending(machineId)
                RmProvisionResult.FLASH_ERROR
            }
            // No answer: keep the pending key. The machine may have applied the replace; its first message under the new key
            // activates it, and a resend is accepted under the one-step grace either way.
            null -> RmProvisionResult.NO_REPLY
            else -> {
                trust.discardPending(machineId)
                RmProvisionResult.REFUSED
            }
        }
    }

    private suspend fun flag(machineId: String, reason: String): RmProvisionResult {
        trust.markMismatch(machineId, reason)
        keys.reportMismatch(reason)
        return RmProvisionResult.MACHINE_KEPT_ITS_KEY
    }

    private suspend fun writeFirstKey(machineId: String, issued: RmKeyFetch.Issued, activeVersion: Int?): RmProvisionResult {
        return when (link.sendProvisionKey(issued.key)) {
            RmProtocol.PROVISION_STORED -> RmProvisionResult.WRITTEN
            RmProtocol.PROVISION_ALREADY_SET -> {
                // The machine holds a key and cannot take another over the air: either it was provisioned
                // from somewhere else, or this is a rotation it cannot apply. The server flags it for the dashboard.
                val reason = if (activeVersion != null) "rotation_needs_reflash" else "machine_has_other_key"
                trust.markMismatch(machineId, reason)
                keys.reportMismatch(reason)
                RmProvisionResult.MACHINE_KEPT_ITS_KEY
            }
            RmProtocol.PROVISION_FLASH_ERROR -> RmProvisionResult.FLASH_ERROR
            RmProtocol.PROVISION_REFUSED -> RmProvisionResult.REFUSED
            null -> RmProvisionResult.NO_REPLY
            else -> RmProvisionResult.REFUSED
        }
    }
}
