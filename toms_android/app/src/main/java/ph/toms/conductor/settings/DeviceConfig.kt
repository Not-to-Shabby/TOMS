package ph.toms.conductor.settings

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ph.toms.conductor.BuildConfig
import ph.toms.conductor.security.SecretCipher

private val NO_WARNING: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

interface DeviceConfigStore {
    val deviceId: String
    val serverUrl: StateFlow<String>
    val deviceToken: StateFlow<String?>

    /** Set when the token could not be stored safely, so More can tell the owner. Null when all is well. */
    val storageWarning: StateFlow<String?> get() = NO_WARNING
    fun setServerUrl(url: String)
    fun setDeviceToken(token: String?)
}

/**
 * The upload token is stored encrypted ([SecretCipher]); the server URL and device id are not secret.
 *
 * A Keystore failure never escapes and never loses the token for this run, and the token is never written in
 * plain text:
 * - a token that cannot be encrypted is kept in memory, an older stored one is dropped (so it cannot come back
 *   after a restart) and [storageWarning] says to enter it again after restarting;
 * - a token saved in plain text by an earlier version is encrypted on first start and the plain copy removed. If it
 *   cannot be encrypted yet, the plain copy is left where it is, still in use, and the next start tries again;
 * - a token that cannot be decrypted is treated as missing, so the phone shows "Not enrolled" instead of failing.
 */
@Singleton
class PrefsDeviceConfig @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) : DeviceConfigStore {
    private val prefs = context.getSharedPreferences("toms_device", Context.MODE_PRIVATE)

    // Declared before the token: reading the saved token below may need to set it.
    private val _storageWarning = MutableStateFlow<String?>(null)
    override val storageWarning: StateFlow<String?> = _storageWarning.asStateFlow()

    override val deviceId: String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL)
    override val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _deviceToken = MutableStateFlow(loadToken())
    override val deviceToken: StateFlow<String?> = _deviceToken.asStateFlow()

    private fun loadToken(): String? {
        val legacy = prefs.getString(KEY_DEVICE_TOKEN, null)
        if (legacy != null) {
            val clean = legacy.trim().ifEmpty { null }
            if (clean == null) {
                prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
                return null
            }
            // Encrypt first, remove the plain copy only after the encrypted one is saved. If that fails the plain
            // copy stays, so the token is not lost and the next start tries again.
            if (saveEncrypted(clean)) prefs.edit().remove(KEY_DEVICE_TOKEN).apply() else _storageWarning.value = MIGRATION_WARNING
            return clean
        }
        val blob = prefs.getString(KEY_DEVICE_TOKEN_ENC, null) ?: return null
        val plain = runCatching { cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull() ?: return null
        return plain.toString(Charsets.UTF_8)
    }

    /** True only when the encrypted copy is saved. Any Keystore or storage failure is a false, never an exception. */
    private fun saveEncrypted(token: String): Boolean = try {
        val blob = Base64.encodeToString(cipher.encrypt(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(KEY_DEVICE_TOKEN_ENC, blob).commit()
    } catch (_: Exception) {
        false
    }

    override fun setServerUrl(url: String) {
        val clean = url.trim().removeSuffix("/")
        prefs.edit().putString(KEY_SERVER_URL, clean).apply()
        _serverUrl.value = clean
    }

    /**
     * An explicit change replaces everything stored, including a plain token that could not be migrated yet: it is
     * the old token, and it must not come back after a restart.
     */
    override fun setDeviceToken(token: String?) {
        val clean = token?.trim()?.ifEmpty { null }
        when {
            clean == null -> {
                prefs.edit().remove(KEY_DEVICE_TOKEN_ENC).remove(KEY_DEVICE_TOKEN).apply()
                _storageWarning.value = null
            }
            saveEncrypted(clean) -> {
                prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
                _storageWarning.value = null
            }
            else -> {
                // Not stored. Kept for this run only; an older stored token is dropped so it cannot return after a restart.
                prefs.edit().remove(KEY_DEVICE_TOKEN_ENC).remove(KEY_DEVICE_TOKEN).apply()
                _storageWarning.value = STORAGE_WARNING
            }
        }
        _deviceToken.value = clean
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        /** The plain-text key of earlier versions. Only read to migrate, then deleted. */
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_TOKEN_ENC = "device_token_enc"

        const val STORAGE_WARNING = "Could not store the token securely. Re-enter it after restarting."
        const val MIGRATION_WARNING = "Could not encrypt the saved token yet. It still works, and will be encrypted when the phone allows it."

        /**
         * What a phone points at before anyone sets an address. Debug builds: the local backend on port 3000 (reached
         * with `adb reverse tcp:3000 tcp:3000` or the emulator's 10.0.2.2). Release builds: nothing, so the owner has to
         * set the server on More, and until then the app asks for it instead of trying the network.
         */
        val DEFAULT_URL: String = BuildConfig.DEFAULT_SERVER_URL
    }
}
