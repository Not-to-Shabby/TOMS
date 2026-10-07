package ph.toms.conductor.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the SQLCipher passphrase. A random 32-byte secret is generated once and stored only in
 * wrapped form: encrypted with an AES-GCM key that never leaves the Android Keystore. Reading the
 * app's files off the phone therefore does not reveal the database key.
 */
class DatabaseKeyProvider(context: Context) {

    private val prefs = context.getSharedPreferences("toms_db_key", Context.MODE_PRIVATE)

    @Synchronized
    fun passphrase(): ByteArray {
        val stored = prefs.getString(PREF_WRAPPED, null)
        if (stored != null) return unwrap(Base64.decode(stored, Base64.NO_WRAP))
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_WRAPPED, Base64.encodeToString(wrap(secret), Base64.NO_WRAP)).apply()
        return secret
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return cipher.iv + cipher.doFinal(secret)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "toms_db_wrap_v1"
        const val PREF_WRAPPED = "wrapped_passphrase"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
