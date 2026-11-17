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

    /** The machine already had a key (this one, or another) and cannot replace it over the air. */
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
 * connected machine over the encrypted diag link, and let the machine's first verified signed message
 * activate it. The firmware keeps a key it already has (diag 06 is once-only), so a rotation, or a
 * machine whose key the server does not know, ends in [RmProvisionResult.MACHINE_KEPT_ITS_KEY] and
 * is reported to the server as a mismatch instead of being retried forever.
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
        if (activeVersion == issued.keyVersion || pendingVersion == issued.keyVersion) {
            return RmProvisionResult.WRITTEN
        }

        val stored = trust.provision(machineId, issued.key, issued.keyVersion, issued.previousValidUntilMillis)
        if (!stored) return RmProvisionResult.NOT_POSSIBLE

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
