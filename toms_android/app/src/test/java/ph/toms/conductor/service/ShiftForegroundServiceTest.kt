package ph.toms.conductor.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** The service is started with a shift and stopped at End Shift; the notification is posted and removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShiftForegroundServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun startIntent() = Intent(context, ShiftForegroundService::class.java)
        .putExtra(ShiftForegroundService.EXTRA_VEHICLE, "DONSAL ILIGAN")
        .putExtra(ShiftForegroundService.EXTRA_ROUTE, "Route 1")

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

    @Test
    fun `the stop action removes the notification and stops the service`() {
        val controller = startAndRun()
        assertTrue(shadowOf(controller.get()).isLastForegroundNotificationAttached)

        val stop = Intent(context, ShiftForegroundService::class.java).setAction(ShiftForegroundService.ACTION_STOP)
        controller.get().onStartCommand(stop, 0, 1)

        val shadow = shadowOf(controller.get())
        assertTrue("the notification is removed", shadow.getNotificationShouldRemoved())
        assertTrue("the service stops itself", shadow.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `the notification factory labels the shift with the vehicle and route`() {
        val factory = ShiftNotificationFactory(context)
        val text = factory.shiftNotification("DONSAL ILIGAN", "Route 1").extras.getString("android.text") ?: ""
        assertEquals("DONSAL ILIGAN · Route 1", text)
    }
}
