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
import ph.toms.conductor.security.SecretCipher

interface DeviceConfigStore {
    val deviceId: String
    val serverUrl: StateFlow<String>
    val deviceToken: StateFlow<String?>
    fun setServerUrl(url: String)
    fun setDeviceToken(token: String?)
}

/**
 * The upload token is stored encrypted ([SecretCipher]); the server URL and device id are not secret.
 * A token saved in plain text by an earlier version is encrypted on first start and the plain copy removed.
 * A token that cannot be decrypted is treated as missing, so the phone shows "Not enrolled" instead of failing.
 */
@Singleton
class PrefsDeviceConfig @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) : DeviceConfigStore {
    private val prefs = context.getSharedPreferences("toms_device", Context.MODE_PRIVATE)

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
            // Encrypt first, remove the plain copy only after the encrypted one is saved.
            val clean = legacy.trim().ifEmpty { null }
            if (clean != null) saveEncrypted(clean)
            prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
            return clean
        }
        val blob = prefs.getString(KEY_DEVICE_TOKEN_ENC, null) ?: return null
        val plain = runCatching { cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull() ?: return null
        return plain.toString(Charsets.UTF_8)
    }

    private fun saveEncrypted(token: String) {
        val blob = Base64.encodeToString(cipher.encrypt(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(KEY_DEVICE_TOKEN_ENC, blob).commit()
    }

    override fun setServerUrl(url: String) {
        val clean = url.trim().removeSuffix("/")
        prefs.edit().putString(KEY_SERVER_URL, clean).apply()
        _serverUrl.value = clean
    }

    override fun setDeviceToken(token: String?) {
        val clean = token?.trim()?.ifEmpty { null }
        if (clean == null) prefs.edit().remove(KEY_DEVICE_TOKEN_ENC).apply() else saveEncrypted(clean)
        _deviceToken.value = clean
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        /** The plain-text key of earlier versions. Only read to migrate, then deleted. */
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_TOKEN_ENC = "device_token_enc"

        /** Default points to local port 3000 (accessible via `adb reverse tcp:3000 tcp:3000` or emulator 10.0.2.2). */
        const val DEFAULT_URL = "http://127.0.0.1:3000"
    }
}
