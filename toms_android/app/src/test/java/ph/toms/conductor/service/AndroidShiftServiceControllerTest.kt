package ph.toms.conductor.service

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The controller starts the service only when a foreground-service type's permission is granted,
 * and stops it with plain stopService (never startForegroundService with a stop action, which
 * crashes on Android 12+ when the service is not running).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidShiftServiceControllerTest {

    private lateinit var context: Context
    private lateinit var controller: AndroidShiftServiceController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = AndroidShiftServiceController(context)
    }

    @Test
    fun `start launches the service with the shift labels and the allowed types`() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        assertTrue(controller.startForShift("v1", "r1"))

        val started = shadowOf(context as Application).getNextStartedService()
        assertNotNull(started)
        assertEquals(ShiftForegroundService::class.java.name, started.component?.className)
        assertEquals("v1", started.getStringExtra(ShiftForegroundService.EXTRA_VEHICLE))
        assertEquals("r1", started.getStringExtra(ShiftForegroundService.EXTRA_ROUTE))
        assertEquals(
            ShiftForegroundService.allowedTypes(context),
            started.getIntExtra(ShiftForegroundService.EXTRA_TYPES, 0),
        )
    }

    @Test
    fun `start is skipped when neither the location nor the Bluetooth permission is granted`() {
        // Robolectric grants nothing by default; make that explicit.
        shadowOf(context as Application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        assertFalse(controller.startForShift("v1", "r1"))
        assertNull(shadowOf(context as Application).getNextStartedService())
    }

    @Test
    fun `stop is a plain stopService call and never starts the service`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        controller.stop()

        assertNull("no service is started to stop", shadowOf(context as Application).getNextStartedService())
        assertNotNull(shadowOf(context as Application).getNextStoppedService())
    }
}
