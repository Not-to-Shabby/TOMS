package ph.toms.conductor.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface DeviceConfigStore {
    val deviceId: String
    val serverUrl: StateFlow<String>
    val deviceToken: StateFlow<String?>
    fun setServerUrl(url: String)
    fun setDeviceToken(token: String?)
}

@Singleton
class PrefsDeviceConfig @Inject constructor(
    @ApplicationContext context: Context,
) : DeviceConfigStore {
    private val prefs = context.getSharedPreferences("toms_device", Context.MODE_PRIVATE)

    override val deviceId: String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL)
    override val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _deviceToken = MutableStateFlow(prefs.getString(KEY_DEVICE_TOKEN, null))
    override val deviceToken: StateFlow<String?> = _deviceToken.asStateFlow()

    override fun setServerUrl(url: String) {
        val clean = url.trim().removeSuffix("/")
        prefs.edit().putString(KEY_SERVER_URL, clean).apply()
        _serverUrl.value = clean
    }

    override fun setDeviceToken(token: String?) {
        val clean = token?.trim()?.ifEmpty { null }
        prefs.edit().putString(KEY_DEVICE_TOKEN, clean).apply()
        _deviceToken.value = clean
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"

        /** Default points to local port 3000 (accessible via `adb reverse tcp:3000 tcp:3000` or emulator 10.0.2.2). */
        const val DEFAULT_URL = "http://127.0.0.1:3000"
    }
}
