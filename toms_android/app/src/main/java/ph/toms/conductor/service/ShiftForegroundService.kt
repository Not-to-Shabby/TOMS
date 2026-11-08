package ph.toms.conductor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import ph.toms.conductor.MainActivity
import ph.toms.conductor.R
import javax.inject.Inject
/**
 * Keeps the process foreground while a shift is open, so GPS fixes, the Return Machine BLE link and the
 * outbox upload kick keep working with the screen off. The service owns the notification only: location
 * collection, BLE and uploads stay owned by the parts that already have them (LocationTracker,
 * AndroidBleReturnMachineLink, WorkManager), which simply keep running while the process is foreground.
 *
 * Types are `location` (GPS fixes with the screen off) and `connectedDevice` (BLE link to the Return
 * Machine). Deliberately NOT `dataSync`: on Android 15 that type is capped at 6 hours, shorter than a
 * shift, and uploads go through WorkManager, which needs no foreground service.
 *
 * Started when a shift opens and stopped when it ends (see [ShiftServiceController]). Started only from
 * the foreground UI, so the Android background-start limits never apply.
 */
@AndroidEntryPoint
class ShiftForegroundService : Service() {

    @Inject
    lateinit var notificationFactory: ShiftNotificationFactory

    override fun onCreate() {
        super.onCreate()
        notificationFactory.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val vehicle = intent?.getStringExtra(EXTRA_VEHICLE).orEmpty()
                val route = intent?.getStringExtra(EXTRA_ROUTE).orEmpty()
                val notification = notificationFactory.shiftNotification(vehicle, route)
                // Both types are declared in the manifest; passing them here is required on Android 14+.
                val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_STOP = "ph.toms.conductor.service.STOP"
        const val EXTRA_VEHICLE = "vehicle"
        const val EXTRA_ROUTE = "route"
        const val CHANNEL_ID = "shift"
        const val NOTIFICATION_ID = 41
    }
}

/** Builds the ongoing shift notification and its channel, kept separate so tests can use it without the service. */
class ShiftNotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                ShiftForegroundService.CHANNEL_ID,
                context.getString(R.string.notification_channel_shift),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = context.getString(R.string.notification_channel_shift_description)
            manager.createNotificationChannel(channel)
        }
    }

    fun shiftNotification(vehicleId: String, routeId: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val subtitle = listOf(vehicleId, routeId).filter { it.isNotBlank() }.joinToString(" · ")
        return NotificationCompat.Builder(context, ShiftForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.notification_shift_title))
            .setContentText(
                if (subtitle.isBlank()) context.getString(R.string.notification_shift_text) else subtitle,
            )
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
