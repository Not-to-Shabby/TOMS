package ph.toms.conductor.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.security.SecretCipher

/** What the token store does when the Keystore, or the storage under it, fails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceConfigFailureTest {

    /** Reversible like a real cipher, and able to fail on either call. */
    private class FlakyCipher : SecretCipher {
        var failEncrypt = false
        var failDecrypt = false
        override fun encrypt(plain: ByteArray): ByteArray {
            if (failEncrypt) throw java.security.GeneralSecurityException("Keystore is not available")
            return ByteArray(12) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun decrypt(blob: ByteArray): ByteArray? {
            if (failDecrypt) throw java.security.GeneralSecurityException("Keystore is not available")
            return blob.copyOfRange(12, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    /** Preferences whose `commit()` can report that nothing was written, as a full disk does. */
    private class FailingCommitContext(base: Context, private val failCommit: () -> Boolean) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val e = real.edit()
                    return object : SharedPreferences.Editor by e {
                        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
                        override fun remove(key: String) = apply { e.remove(key) }
                        override fun commit(): Boolean = if (failCommit()) false else e.commit()
                    }
                }
            }
        }
    }

    private lateinit var context: Context
    private val cipher = FlakyCipher()
    private var failCommit = false

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("toms_device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("toms_device", Context.MODE_PRIVATE)
    private fun everythingStored() = prefs().all.values.joinToString("|")
    private fun config() = PrefsDeviceConfig(FailingCommitContext(context) { failCommit }, cipher)

    // ---- setting a token

    @Test fun `if encrypting fails the token works for this run, is warned about and is never written`() {
        val c = config()
        cipher.failEncrypt = true

        c.setDeviceToken("dev-1.secret")

        assertEquals("dev-1.secret", c.deviceToken.value)
        assertEquals(PrefsDeviceConfig.STORAGE_WARNING, c.storageWarning.value)
        assertEquals("Could not store the token securely. Re-enter it after restarting.", c.storageWarning.value)
        assertFalse("not in plain text", everythingStored().contains("secret"))
        assertTrue("nothing stored at all", prefs().all.keys.none { it.startsWith("device_token") })
    }

    @Test fun `after a failed store the token is gone on restart, so the owner has to enter it again`() {
        val c = config()
        cipher.failEncrypt = true
        c.setDeviceToken("dev-1.secret")
        cipher.failEncrypt = false

        val restarted = config()

        assertNull(restarted.deviceToken.value)
        assertNull(restarted.storageWarning.value)
    }

    @Test fun `a failed store drops an older stored token so it cannot come back after a restart`() {
        config().setDeviceToken("dev-1.OLD")
        val c = config()
        cipher.failEncrypt = true

        c.setDeviceToken("dev-1.NEW")

        assertEquals("dev-1.NEW", c.deviceToken.value)
        cipher.failEncrypt = false
        assertNull("the old token must not return", config().deviceToken.value)
    }

    @Test fun `if the write fails (commit returns false) it counts as a failed store`() {
        val c = config()
        failCommit = true

        c.setDeviceToken("dev-1.secret")

        assertEquals("dev-1.secret", c.deviceToken.value)
        assertEquals(PrefsDeviceConfig.STORAGE_WARNING, c.storageWarning.value)
        failCommit = false
        assertNull("nothing was stored", config().deviceToken.value)
    }

    @Test fun `a later successful store clears the warning and keeps the token across a restart`() {
        val c = config()
        cipher.failEncrypt = true
        c.setDeviceToken("dev-1.secret")
        assertEquals(PrefsDeviceConfig.STORAGE_WARNING, c.storageWarning.value)
        cipher.failEncrypt = false

        c.setDeviceToken("dev-1.secret")

        assertNull(c.storageWarning.value)
        assertEquals("dev-1.secret", config().deviceToken.value)
    }

    @Test fun `clearing the token clears the warning`() {
        val c = config()
        cipher.failEncrypt = true
        c.setDeviceToken("dev-1.secret")
        c.setDeviceToken(null)
        assertNull(c.deviceToken.value)
        assertNull(c.storageWarning.value)
    }

    // ---- reading a token

    @Test fun `if decrypting throws, the phone reads as not enrolled instead of crashing`() {
        config().setDeviceToken("dev-1.secret")
        cipher.failDecrypt = true

        val restarted = config()

        assertNull(restarted.deviceToken.value)
    }

    // ---- the plain-text token of earlier versions

    @Test fun `a plain token that cannot be encrypted yet stays, still works, and is not deleted`() {
        prefs().edit().putString("device_token", "old-dev.plain").commit()
        cipher.failEncrypt = true

        val c = config()

        assertEquals("old-dev.plain", c.deviceToken.value)
        assertEquals("old-dev.plain", prefs().getString("device_token", null))
        assertNull("no half-written encrypted copy", prefs().getString("device_token_enc", null))
        assertEquals(PrefsDeviceConfig.MIGRATION_WARNING, c.storageWarning.value)
    }

    @Test fun `the plain token is encrypted on a later start once the Keystore works, then the plain copy goes`() {
        prefs().edit().putString("device_token", "old-dev.plain").commit()
        cipher.failEncrypt = true
        config()
        cipher.failEncrypt = false

        val c = config()

        assertEquals("old-dev.plain", c.deviceToken.value)
        assertFalse(prefs().contains("device_token"))
        assertFalse(everythingStored().contains("plain"))
        assertNull(c.storageWarning.value)
        assertEquals("old-dev.plain", config().deviceToken.value)
    }

    @Test fun `a plain token whose write fails (commit returns false) is not deleted`() {
        prefs().edit().putString("device_token", "old-dev.plain").commit()
        failCommit = true

        val c = config()

        assertEquals("old-dev.plain", c.deviceToken.value)
        assertTrue(prefs().contains("device_token"))
        failCommit = false
        assertEquals("old-dev.plain", config().deviceToken.value)
    }

    @Test fun `entering a new token replaces a plain one that could not be migrated, so the old one cannot return`() {
        prefs().edit().putString("device_token", "old-dev.plain").commit()
        cipher.failEncrypt = true
        val c = config()
        cipher.failEncrypt = false

        c.setDeviceToken("new-dev.fresh")

        assertFalse(prefs().contains("device_token"))
        assertEquals("new-dev.fresh", config().deviceToken.value)
    }

    @Test fun `a blank legacy token is just removed`() {
        prefs().edit().putString("device_token", "   ").commit()
        val c = config()
        assertNull(c.deviceToken.value)
        assertFalse(prefs().contains("device_token"))
        assertNull(c.storageWarning.value)
    }
}
