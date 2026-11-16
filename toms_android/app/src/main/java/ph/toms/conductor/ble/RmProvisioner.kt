package ph.toms.conductor.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a per-machine signing key comes from. The machine receives it over the diag 06 exchange
 * (firmware/PROTOCOL.md section 3.7); the phone's copy belongs to the enrolment flow, which gives a
 * device its token and — once that flow grows it — its Return Machine key. Until that exists, this
 * boundary is the whole contract: whatever enrols a machine implements [keyFor], and nothing else in
 * the app needs to know where the bytes came from.
 */
interface RmKeySource {
    suspend fun keyFor(machineId: String): ByteArray?
}

/** No enrolment-side source is wired yet: provisioning reports that honestly instead of guessing. */
object NoRmKeySource : RmKeySource {
    override suspend fun keyFor(machineId: String): ByteArray? = null
}

enum class RmProvisionResult {
    /** The machine stored the key; the phone activated it. */
    STORED,

    /** The machine already had a key (possibly a different one): the phone did not activate anything. */
    ALREADY_SET,

    /** The machine could not store the key in its settings partition. */
    FLASH_ERROR,

    /** The machine refused: no encrypted link, wrong frame size, or not once-only. */
    REFUSED,

    /** No machine is connected, or no key source has a key for it. */
    NOT_POSSIBLE,

    /** The machine did not answer in time; the key stays pending on the phone. */
    NO_REPLY,
}

/**
 * The phone side of key provisioning: store the key pending, send it over the diag exchange, and
 * activate it only when the machine confirms it stored the same key. Everything behind this boundary
 * is exactly what the protocol document defines — the frame, the encrypted-link requirement, the
 * status reply — so a future enrolment flow calls one function.
 */
interface RmProvisioner {
    suspend fun provisionConnectedMachine(): RmProvisionResult
}

@Singleton
class RealRmProvisioner @Inject constructor(
    private val link: ReturnMachineLink,
    private val trust: RmMachineTrust,
    private val keySource: RmKeySource,
) : RmProvisioner {

    override suspend fun provisionConnectedMachine(): RmProvisionResult {
        val machineId = (link.connectionState.value as? RmConnectionState.Connected)?.deviceAddress
            ?: return RmProvisionResult.NOT_POSSIBLE
        val key = keySource.keyFor(machineId) ?: return RmProvisionResult.NOT_POSSIBLE
        if (!trust.provision(machineId, key)) return RmProvisionResult.NOT_POSSIBLE

        return when (link.sendProvisionKey(key)) {
            RmProtocol.PROVISION_STORED -> {
                trust.activate(machineId)
                RmProvisionResult.STORED
            }
            RmProtocol.PROVISION_ALREADY_SET -> RmProvisionResult.ALREADY_SET
            RmProtocol.PROVISION_FLASH_ERROR -> RmProvisionResult.FLASH_ERROR
            RmProtocol.PROVISION_REFUSED -> RmProvisionResult.REFUSED
            null -> RmProvisionResult.NO_REPLY
            else -> RmProvisionResult.REFUSED
        }
    }
}
