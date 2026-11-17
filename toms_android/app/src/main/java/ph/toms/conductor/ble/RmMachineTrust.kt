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

    /** A key was stored for [machineId] but has not been confirmed: written to the machine, awaiting its first verified message. */
    data class Pending(val machineId: String, val keyVersion: Int) : RmTrustState

    /** The machine's messages verify under [keyVersion]: its notifies are checked and unsigned ones are refused. */
    data class Active(val machineId: String, val keyVersion: Int) : RmTrustState

    /** The machine does not accept or has lost the key; [reason] is one of the server's mismatch reasons. */
    data class Mismatch(val machineId: String, val keyVersion: Int, val reason: String) : RmTrustState
}

/**
 * Per-machine signing keys and the trust policy around them.
 *
 * A key is per physical machine, identified on the phone by the machine's Bluetooth address (the only
 * stable id the link exposes). The server issues the key; the phone keeps it here encrypted with the
 * Keystore-bound [SecretCipher], never logs it, and walks it through:
 *
 *   NoKey ──provision──▶ Pending ──(machine's first signed message verifies)──▶ Active
 *                           │                                                    │
 *                           └──────────── machine rejects or lacks the key ──▶ Mismatch
 *   Active ──rotate (server issues key N+1)──▶ Pending(N+1), old key N verifies for the grace window
 *
 * While a new key is pending, messages verify under it first and then under the previous key, so the
 * link keeps working through a rotation; the first message that verifies under the new key activates it
 * and drops the old one. A pending key never counts as active: until a message verifies, unsigned
 * notifies are still accepted and signed ones are checked against both keys.
 *
 * Once a key is active, signed notifies are verified and unsigned or unverifiable ones are dropped and
 * counted. [allowUnverified] can relax that to accept-and-count — but only in a debug build; in release
 * it reads as false no matter what was set.
 */
interface RmMachineTrust {
    val state: StateFlow<RmTrustState>

    /** The active key for [machineId], or null. Never logged. */
    fun activeKey(machineId: String): ByteArray?

    /**
     * Stores [key] (server key version [keyVersion]) as the pending key for [machineId]. A key that is
     * already active stays valid as the previous key until the new one verifies or [previousValidUntilMillis]
     * passes. True when stored.
     */
    fun provision(machineId: String, key: ByteArray, keyVersion: Int, previousValidUntilMillis: Long? = null): Boolean

    /** The pending key's version for [machineId], or null. */
    fun pendingVersion(machineId: String): Int?

    /** The key version currently active for [machineId], or null. */
    fun activeVersion(machineId: String): Int?

    /** A message verified under the pending key: pending becomes active and the old key is dropped. True when there was one. */
    fun activate(machineId: String): Boolean

    /**
     * The machine does not accept the key, keeps another one, or signs with a key the phone does not hold. The state
     * shows [RmTrustState.Mismatch] — also when the phone holds no key at all (version 0), so the conductor is
     * never left reading "waiting" while the dashboard shows a problem — until [provision] or [clearMismatch].
     */
    fun markMismatch(machineId: String, reason: String)

    /** A message verified under the active key: whatever mismatch was flagged for [machineId] is over. */
    fun clearMismatch(machineId: String)

    /** The current mismatch reason for [machineId], or null. */
    fun mismatchReason(machineId: String): String?

    /** Forget everything known about machines (a provisioning mistake, or a machine taken out of service). */
    fun clear()

    /** Debug-only escape hatch: accept (and count) notifies that fail verification. Release: always false. */
    var allowUnverified: Boolean

    /**
     * Verifies a trailer for [machineId] under every key that may be valid right now: the active key,
     * the pending key, and the previous key during its grace window. Returns which one matched, or null.
     */
    fun verifyUnder(machineId: String, suffix: Int, payload: ByteArray, trailer: ByteArray): RmKeyMatch?

    /** True while a key (active or pending) exists for [machineId]: unsigned notifies are then suspicious. */
    fun hasAnyKey(machineId: String): Boolean
}

/** Which of the phone's keys a message verified under. */
enum class RmKeyMatch { ACTIVE, PENDING, PREVIOUS }

@Singleton
class PrefsRmMachineTrust @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) : RmMachineTrust {

    /** Whether this build may relax verification at all (the debug switch is a bench tool). A test
     *  can set this directly; production never does. */
    @VisibleForTesting
    internal var debugBuilds: Boolean = BuildConfig.DEBUG

    /** Replaced by a test to control the clock for the grace window. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    private val prefs = context.getSharedPreferences("toms_rm_trust", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<RmTrustState>(readState())
    override val state: StateFlow<RmTrustState> = _state.asStateFlow()

    override fun activeKey(machineId: String): ByteArray? = decrypt(prefs.getString(keyOf(machineId), null))

    override fun activeVersion(machineId: String): Int? =
        prefs.getInt(versionOf(machineId), 0).takeIf { it > 0 && prefs.contains(keyOf(machineId)) }

    override fun pendingVersion(machineId: String): Int? {
        if (prefs.getString(KEY_PENDING_MACHINE, null) != machineId) return null
        return prefs.getInt(KEY_PENDING_VERSION, 0).takeIf { it > 0 }
    }

    override fun hasAnyKey(machineId: String): Boolean =
        prefs.contains(keyOf(machineId)) || prefs.getString(KEY_PENDING_MACHINE, null) == machineId

    @Synchronized
    override fun provision(machineId: String, key: ByteArray, keyVersion: Int, previousValidUntilMillis: Long?): Boolean {
        if (key.size != RmSignature.KEY_LEN || keyVersion < 1) return false
        val blob = try {
            Base64.encodeToString(cipher.encrypt(key), Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        val edit = prefs.edit()
            .putString(KEY_PENDING_MACHINE, machineId)
            .putString(KEY_PENDING_KEY, blob)
            .putInt(KEY_PENDING_VERSION, keyVersion)
            .remove(mismatchOf(machineId))
        // The key being replaced stays valid as the previous key until the server's grace window ends.
        if (prefs.contains(keyOf(machineId)) && previousValidUntilMillis != null) {
            edit.putString(previousOf(machineId), prefs.getString(keyOf(machineId), null))
            edit.putLong(previousUntilOf(machineId), previousValidUntilMillis)
        }
        val ok = edit.commit()
        refresh()
        return ok
    }

    @Synchronized
    override fun activate(machineId: String): Boolean {
        if (prefs.getString(KEY_PENDING_MACHINE, null) != machineId) return false
        val blob = prefs.getString(KEY_PENDING_KEY, null) ?: return false
        val version = prefs.getInt(KEY_PENDING_VERSION, 0)
        val ok = prefs.edit()
            .putString(keyOf(machineId), blob)
            .putInt(versionOf(machineId), version)
            .remove(KEY_PENDING_MACHINE).remove(KEY_PENDING_KEY).remove(KEY_PENDING_VERSION)
            .remove(previousOf(machineId)).remove(previousUntilOf(machineId))
            .remove(mismatchOf(machineId))
            .commit()
        refresh()
        return ok
    }

    @Synchronized
    override fun markMismatch(machineId: String, reason: String) {
        prefs.edit().putString(mismatchOf(machineId), reason).commit()
        refresh()
    }

    override fun mismatchReason(machineId: String): String? = prefs.getString(mismatchOf(machineId), null)

    @Synchronized
    override fun clearMismatch(machineId: String) {
        prefs.edit().remove(mismatchOf(machineId)).commit()
        refresh()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
        refresh()
    }

    override fun verifyUnder(machineId: String, suffix: Int, payload: ByteArray, trailer: ByteArray): RmKeyMatch? {
        // Pending first: during a rotation the machine may already sign with the new key.
        if (prefs.getString(KEY_PENDING_MACHINE, null) == machineId) {
            decrypt(prefs.getString(KEY_PENDING_KEY, null))?.let {
                if (RmSignature.verify(it, suffix, payload, trailer)) return RmKeyMatch.PENDING
            }
        }
        activeKey(machineId)?.let {
            if (RmSignature.verify(it, suffix, payload, trailer)) return RmKeyMatch.ACTIVE
        }
        val until = prefs.getLong(previousUntilOf(machineId), 0L)
        if (until > clock()) {
            decrypt(prefs.getString(previousOf(machineId), null))?.let {
                if (RmSignature.verify(it, suffix, payload, trailer)) return RmKeyMatch.PREVIOUS
            }
        }
        return null
    }

    @VisibleForTesting
    override var allowUnverified: Boolean = false
        set(value) {
            // The switch exists for bench work only; a release build never relaxes verification.
            field = value && debugBuilds
        }

    private fun decrypt(blob: String?): ByteArray? =
        blob?.let { runCatching { cipher.decrypt(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }

    private fun refresh() = _state.update { readState() }

    private fun readState(): RmTrustState {
        val pendingMachine = prefs.getString(KEY_PENDING_MACHINE, null)
        if (pendingMachine != null && prefs.getString(KEY_PENDING_KEY, null) != null) {
            val version = prefs.getInt(KEY_PENDING_VERSION, 1)
            return prefs.getString(mismatchOf(pendingMachine), null)
                ?.let { RmTrustState.Mismatch(pendingMachine, version, it) }
                ?: RmTrustState.Pending(pendingMachine, version)
        }
        // The phone talks to one machine at a time, so the most recently written key is the one in use.
        val last = prefs.all.keys.filter { it.startsWith(PREFIX_ACTIVE) }.maxOrNull()
            ?: return prefs.all.keys.firstOrNull { it.startsWith(PREFIX_MISMATCH) }
                ?.let { RmTrustState.Mismatch(it.removePrefix(PREFIX_MISMATCH), 0, prefs.getString(it, "") ?: "") }
                ?: RmTrustState.NoKey
        val machineId = last.removePrefix(PREFIX_ACTIVE)
        val version = prefs.getInt(versionOf(machineId), 1)
        return prefs.getString(mismatchOf(machineId), null)
            ?.let { RmTrustState.Mismatch(machineId, version, it) }
            ?: RmTrustState.Active(machineId, version)
    }

    private fun keyOf(machineId: String) = "$PREFIX_ACTIVE$machineId"
    private fun versionOf(machineId: String) = "version_$machineId"
    private fun previousOf(machineId: String) = "previous_$machineId"
    private fun previousUntilOf(machineId: String) = "previous_until_$machineId"
    private fun mismatchOf(machineId: String) = "$PREFIX_MISMATCH$machineId"

    private companion object {
        const val PREFIX_ACTIVE = "active_"
        const val PREFIX_MISMATCH = "mismatch_"
        const val KEY_PENDING_MACHINE = "pending_machine"
        const val KEY_PENDING_KEY = "pending_key_enc"
        const val KEY_PENDING_VERSION = "pending_version"
    }
}
