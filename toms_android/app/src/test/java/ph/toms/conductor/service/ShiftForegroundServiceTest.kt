package ph.toms.conductor.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/** The service is started with a shift and stopped at End Shift; the notification is posted and removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShiftForegroundServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

    private fun startIntent() = Intent(context, ShiftForegroundService::class.java)
        .putExtra(ShiftForegroundService.EXTRA_VEHICLE, "DONSAL ILIGAN")
        .putExtra(ShiftForegroundService.EXTRA_ROUTE, "Route 1")
        .putExtra(ShiftForegroundService.EXTRA_TYPES, ShiftForegroundService.allowedTypes(context))

    private fun startAndRun(): ServiceController<ShiftForegroundService> {
        val controller = Robolectric.buildService(ShiftForegroundService::class.java, startIntent()).create()
        controller.startCommand(0, 0)
        return controller
    }

    @Test
    fun `start posts the ongoing notification labeled with the shift`() {
        val controller = startAndRun()
        val shadow = shadowOf(controller.get())

        val notification = shadow.lastForegroundNotification
        assertNotNull("the shift notification is posted", notification)
        assertEquals(
            context.getString(ph.toms.conductor.R.string.notification_shift_title),
            notification!!.extras.getString("android.title"),
        )
        assertEquals("DONSAL ILIGAN · Route 1", notification.extras.getString("android.text"))
        controller.destroy()
    }

    // Stopping is done by the controller with plain Context.stopService (never a stop action inside the
    // service, which would trip the startForeground contract); that translation is covered in
    // AndroidShiftServiceControllerTest. The system removes a foreground notification when the service
    // is destroyed, so there is nothing in-service to assert here.

    @Test
    fun `the notification factory labels the shift with the vehicle and route`() {
        val factory = ShiftNotificationFactory(context)
        val text = factory.shiftNotification("DONSAL ILIGAN", "Route 1").extras.getString("android.text") ?: ""
        assertEquals("DONSAL ILIGAN · Route 1", text)
    }

    @Test
    fun `allowed types follow the granted permissions`() {
        // Both granted in setUp
        assertEquals(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ShiftForegroundService.allowedTypes(context),
        )

        // Only location: the Bluetooth type is dropped
        val locationOnly = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(locationOnly).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ShiftForegroundService.allowedTypes(locationOnly),
        )

        // Neither: zero, which is what makes the controller skip the service
        val none = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(none).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(0, ShiftForegroundService.allowedTypes(none))
    }
}
