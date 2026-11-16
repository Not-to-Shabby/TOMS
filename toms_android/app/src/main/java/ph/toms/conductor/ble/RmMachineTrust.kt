package ph.toms.conductor.ble

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ph.toms.conductor.BuildConfig
import ph.toms.conductor.security.SecretCipher

/** Where the phone stands with a Return Machine's signing key (firmware/PROTOCOL.md section 6). */
sealed interface RmTrustState {
    /** No key known for any machine: unsigned notifies are accepted, signed ones cannot be verified. */
    data object NoKey : RmTrustState

    /** A key was stored for [machineId] but the machine has not confirmed it yet (diag 06 reply pending). */
    data class Pending(val machineId: String) : RmTrustState

    /** The machine confirmed the key: its notifies are verified and unsigned ones are refused. */
    data class Active(val machineId: String) : RmTrustState
}

/**
 * Per-machine signing keys and the trust policy around them.
 *
 * A key is per physical machine, identified on the phone by the machine's Bluetooth address (the only
 * stable id the link exposes). `provision` stores a key as pending; the machine confirms it by answering
 * the diag 06 exchange (see [RmProvisioner]), which is when [activate] turns it on. The key bytes are
 * encrypted at rest with the existing Keystore-bound [SecretCipher] and never logged.
 *
 * Policy once a key is active: signed notifies are verified, and unsigned or unverifiable notifies are
 * dropped and counted. [allowUnverified] can relax that to accept-and-count — but only in a debug build;
 * in release it reads as false no matter what was set.
 */
interface RmMachineTrust {
    val state: StateFlow<RmTrustState>

    /** The stored key for [machineId], or null. Never logged. */
    fun activeKey(machineId: String): ByteArray?

    /** Stores [key] as the pending key for [machineId]. True when stored. */
    fun provision(machineId: String, key: ByteArray): Boolean

    /** The machine confirmed the key: pending becomes active. True when there was a pending key for it. */
    fun activate(machineId: String): Boolean

    /** Forget everything known about machines (a provisioning mistake, or a machine taken out of service). */
    fun clear()

    /** Debug-only escape hatch: accept (and count) notifies that fail verification. Release: always false. */
    var allowUnverified: Boolean

    fun verifyAllowed(machineId: String, suffix: Int, payload: ByteArray, trailer: ByteArray): Boolean =
        activeKey(machineId)?.let { RmSignature.verify(it, suffix, payload, trailer) } == true
}

@Singleton
class PrefsRmMachineTrust @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) : RmMachineTrust {

    /** Whether this build may relax verification at all (the debug switch is a bench tool). A test
     *  can set this directly; production never does. */
    @VisibleForTesting
    internal var debugBuilds: Boolean = BuildConfig.DEBUG

    private val prefs = context.getSharedPreferences("toms_rm_trust", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<RmTrustState>(readState())
    override val state: StateFlow<RmTrustState> = _state.asStateFlow()

    override fun activeKey(machineId: String): ByteArray? {
        val blob = prefs.getString(keyOf(machineId), null) ?: return null
        return runCatching { cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull()
    }

    override fun provision(machineId: String, key: ByteArray): Boolean {
        if (key.size != RmSignature.KEY_LEN) return false
        val blob = try {
            Base64.encodeToString(cipher.encrypt(key), Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        val ok = prefs.edit().putString(KEY_PENDING_MACHINE, machineId).putString(KEY_PENDING_KEY, blob).commit()
        refresh()
        return ok
    }

    override fun activate(machineId: String): Boolean {
        val pendingMachine = prefs.getString(KEY_PENDING_MACHINE, null) ?: return false
        if (pendingMachine != machineId) return false
        val blob = prefs.getString(KEY_PENDING_KEY, null) ?: return false
        val ok = prefs.edit().putString(keyOf(machineId), blob).remove(KEY_PENDING_MACHINE).remove(KEY_PENDING_KEY).commit()
        refresh()
        return ok
    }

    override fun clear() {
        prefs.edit().clear().commit()
        refresh()
    }

    @VisibleForTesting
    override var allowUnverified: Boolean = false
        set(value) {
            // The switch exists for bench work only; a release build never relaxes verification.
            field = value && debugBuilds
        }

    private fun refresh() = _state.update { readState() }

    private fun readState(): RmTrustState {
        val pendingMachine = prefs.getString(KEY_PENDING_MACHINE, null)
        if (pendingMachine != null && prefs.getString(KEY_PENDING_KEY, null) != null) {
            return RmTrustState.Pending(pendingMachine)
        }
        // Any activated machine counts as active; the phone talks to one machine at a time, so the
        // most recently written key is the one in use.
        val last = prefs.all.keys.filter { it.startsWith(PREFIX_ACTIVE) }.maxOrNull()
        return if (last != null) RmTrustState.Active(last.removePrefix(PREFIX_ACTIVE)) else RmTrustState.NoKey
    }

    private fun keyOf(machineId: String) = "$PREFIX_ACTIVE$machineId"

    private companion object {
        const val PREFIX_ACTIVE = "active_"
        const val KEY_PENDING_MACHINE = "pending_machine"
        const val KEY_PENDING_KEY = "pending_key_enc"
    }
}
