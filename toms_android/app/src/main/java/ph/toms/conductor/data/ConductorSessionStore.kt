package ph.toms.conductor.data

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ph.toms.conductor.security.SecretCipher

/** A conductor sign-in kept across restarts. There is no password here, only the server's token. */
data class StoredSession(val name: String, val token: String, val expiresAtMillis: Long)

interface ConductorSessionStore {
    fun load(): StoredSession?

    /** True only when the session is saved. A failure is a false, never an exception, and leaves nothing behind. */
    fun save(session: StoredSession): Boolean
    fun clear()
}

/**
 * The token is stored encrypted ([SecretCipher], Keystore-backed). The name and the expiry are not secret. A token
 * that cannot be decrypted is treated as no sign-in, so the conductor is asked to sign in again.
 */
@Singleton
class PrefsSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) : ConductorSessionStore {
    private val prefs = context.getSharedPreferences("toms_session", Context.MODE_PRIVATE)

    override fun load(): StoredSession? {
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val blob = prefs.getString(KEY_TOKEN_ENC, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        val token = runCatching { cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull()
        if (token == null) {
            clear()
            return null
        }
        return StoredSession(name, token.toString(Charsets.UTF_8), expires)
    }

    override fun save(session: StoredSession): Boolean = try {
        val blob = Base64.encodeToString(cipher.encrypt(session.token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val ok = prefs.edit()
            .putString(KEY_NAME, session.name)
            .putString(KEY_TOKEN_ENC, blob)
            .putLong(KEY_EXPIRES, session.expiresAtMillis)
            .commit()
        if (!ok) clear()
        ok
    } catch (_: Exception) {
        clear()
        false
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_TOKEN_ENC = "token_enc"
        const val KEY_EXPIRES = "expires_at"
    }
}

/** Reads the `exp` claim of a JWT without checking the signature: only to know when to stop trusting a saved sign-in. */
object JwtExpiry {
    fun millis(token: String): Long? = runCatching {
        val payload = token.split('.')[1]
        val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        Json.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull?.times(1000)
    }.getOrNull()
}
