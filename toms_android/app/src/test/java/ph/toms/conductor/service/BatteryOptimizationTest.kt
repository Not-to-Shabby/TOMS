package ph.toms.conductor.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The battery-optimization exemption is asked once; marking it asked never nags again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatteryOptimizationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("toms_battery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `asks by default, once, and never again after it was asked`() {
        assertTrue(BatteryOptimization.shouldAsk(context))
        BatteryOptimization.markAsked(context)
        assertFalse(BatteryOptimization.shouldAsk(context))
    }

    @Test
    fun `the request intent targets this app`() {
        val intent = BatteryOptimization.requestIntent(context)
        assertTrue(intent.data.toString().endsWith("package:${context.packageName}"))
    }

    @Test
    fun `robolectric reports the app is not exempt by default`() {
        assertFalse(BatteryOptimization.isIgnoring(context))
    }
}
