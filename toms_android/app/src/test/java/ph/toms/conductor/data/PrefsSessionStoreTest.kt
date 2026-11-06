package ph.toms.conductor.data

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
class PrefsSessionStoreTest {

    /** Robolectric has no Android Keystore: reversible, and never the plain bytes. */
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
        context.getSharedPreferences("toms_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("toms_session", Context.MODE_PRIVATE)

    @Test fun `the token is encrypted at rest and survives a new store instance`() {
        val token = fakeJwt(2_000_000_000)
        PrefsSessionStore(context, cipher).save(StoredSession("Juan", token, 2_000_000_000_000))

        assertFalse(prefs().all.values.joinToString("|").contains(token))
        assertEquals(StoredSession("Juan", token, 2_000_000_000_000), PrefsSessionStore(context, cipher).load())
    }

    @Test fun `a token that cannot be decrypted means signed out`() {
        PrefsSessionStore(context, cipher).save(StoredSession("Juan", fakeJwt(2_000_000_000), 2_000_000_000_000))
        cipher.broken = true

        assertNull(PrefsSessionStore(context, cipher).load())
        assertTrue(prefs().all.isEmpty())
    }

    @Test fun `clear removes everything`() {
        val store = PrefsSessionStore(context, cipher)
        store.save(StoredSession("Juan", fakeJwt(2_000_000_000), 2_000_000_000_000))
        store.clear()
        assertNull(store.load())
    }
}
