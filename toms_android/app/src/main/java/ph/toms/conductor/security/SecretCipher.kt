package ph.toms.conductor.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the upload token, later the Return Machine key) so they can sit in app storage.
 * [decrypt] returns null when the blob cannot be read, for example because the Keystore key is gone after a
 * restore or a lock-screen reset. The caller treats that as "no secret" and asks for it again.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray?
}

/** AES-GCM with a key that never leaves the Android Keystore. The blob is the 12-byte IV followed by the ciphertext. */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    @Synchronized
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    @Synchronized
    override fun decrypt(blob: ByteArray): ByteArray? {
        if (blob.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
            }
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "toms_secrets_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
