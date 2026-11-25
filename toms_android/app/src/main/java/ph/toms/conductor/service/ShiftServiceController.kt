package ph.toms.conductor.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and stops [ShiftForegroundService] with the shift. The service only shows the ongoing
 * notification and holds the process foreground; it never changes how shifts work.
 */
interface ShiftServiceController {
    /**
     * Called when a shift opens (fresh or resumed). [vehicleId] and [routeId] only label the notification.
     * Returns false when the service was skipped because neither the location nor the Bluetooth permission
     * is granted (Android 14+ would throw on startForeground); the caller shows a note on screen.
     */
    fun startForShift(vehicleId: String, routeId: String): Boolean

    /** Called when the shift ends. The service is stopped; nothing else is touched. */
    fun stop()

    /** Stands in where no service is wanted (unit tests). */
    object None : ShiftServiceController {
        override fun startForShift(vehicleId: String, routeId: String) = true
        override fun stop() {}
    }
}

@Singleton
class AndroidShiftServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : ShiftServiceController {

    override fun startForShift(vehicleId: String, routeId: String): Boolean {
        val types = ShiftForegroundService.allowedTypes(context)
        if (types == 0) return false
        val intent = Intent(context, ShiftForegroundService::class.java)
            .putExtra(ShiftForegroundService.EXTRA_VEHICLE, vehicleId)
            .putExtra(ShiftForegroundService.EXTRA_ROUTE, routeId)
            .putExtra(ShiftForegroundService.EXTRA_TYPES, types)
        // Shift start always comes from a tap on the screen, so this is a foreground start and the
        // Android background-start limits never apply.
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    /**
     * plain stopService, never startForegroundService: an ACTION_STOP branch inside the service would
     * have to call startForeground to satisfy the 5-second contract, and crashes on Android 12+ when
     * the service was not running (End Shift after a restart, or a skipped start). stopService is a
     * harmless no-op then, and removes the notification when the service is running.
     */
    override fun stop() {
        context.stopService(Intent(context, ShiftForegroundService::class.java))
    }
}

/**
 * The system's exemption from battery optimization, asked once with the system prompt. The conductor is
 * told in plain words first, and a refusal is handled by simply carrying on: the periodic upload worker
 * and the notification still do their job, just with less protection from Doze.
 */
object BatteryOptimization {

    private const val PREFS = "toms_battery"
    private const val KEY_ASKED = "asked"

    /** True until the system prompt has been shown once (whether the conductor allowed it or not). */
    fun shouldAsk(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).commit()
    }

    /** True when the system already ignores battery optimization for this app. */
    fun isIgnoring(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The intent behind the system prompt. Callers show their own explanation first. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
