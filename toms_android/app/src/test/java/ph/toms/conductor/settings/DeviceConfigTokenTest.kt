package ph.toms.conductor.settings

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceConfigTokenTest {

    /** Robolectric has no Android Keystore, so this stands in for it: reversible, and never the plain bytes. */
    private class FakeCipher : SecretCipher {
        var broken = false
        override fun encrypt(plain: ByteArray) = ByteArray(12) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray? =
            if (broken) null else blob.copyOfRange(12, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private lateinit var context: Context
    private val cipher = FakeCipher()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("toms_device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("toms_device", Context.MODE_PRIVATE)
    private fun config() = PrefsDeviceConfig(context, cipher)

    @Test fun `a saved token is not stored in plain text and survives a restart`() {
        config().setDeviceToken("dev-1.secret")
        val stored = prefs().all.values.joinToString("|")
        assertFalse("the token must not appear in storage", stored.contains("secret"))
        assertEquals("dev-1.secret", config().deviceToken.value)
    }

    @Test fun `a plain text token from an earlier version is encrypted and the plain copy is removed`() {
        prefs().edit().putString("device_token", "  old-dev.plain  ").commit()
        assertEquals("old-dev.plain", config().deviceToken.value)
        assertFalse(prefs().contains("device_token"))
        assertFalse(prefs().all.values.joinToString("|").contains("plain"))
        assertEquals("old-dev.plain", config().deviceToken.value)
    }

    @Test fun `a token that cannot be decrypted reads as not enrolled instead of failing`() {
        config().setDeviceToken("dev-1.secret")
        cipher.broken = true
        assertNull(config().deviceToken.value)
    }

    @Test fun `clearing the token removes it from storage`() {
        val c = config()
        c.setDeviceToken("dev-1.secret")
        c.setDeviceToken(null)
        assertNull(c.deviceToken.value)
        assertTrue(prefs().all.keys.none { it.startsWith("device_token") })
        assertNull(config().deviceToken.value)
    }

    @Test fun `a blank token counts as no token`() {
        val c = config()
        c.setDeviceToken("   ")
        assertNull(c.deviceToken.value)
    }
}
