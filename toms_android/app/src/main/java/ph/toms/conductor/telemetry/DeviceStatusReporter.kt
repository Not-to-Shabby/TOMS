package ph.toms.conductor.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ph.toms.conductor.BuildConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.EventDao
import ph.toms.conductor.data.db.OutboxDao
import ph.toms.conductor.ble.ReturnMachineLink
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.sync.UploadStats
import ph.toms.conductor.sync.UplinkManager
import javax.inject.Inject
import javax.inject.Singleton

/** Writes a [DeviceStatusPayload] to the outbox at shift start, every [INTERVAL_MILLIS] while the shift
 * runs, and once more when the shift ends. Written through [TripRepository] like any event, so it waits
 * offline and reaches the server with the rest of the batch.
 *
 * The reporter only records state; it never changes shift, upload or link behavior.
 */
interface DeviceStatusReporter {
    /** Starts the periodic writer for an open shift and writes the shift-start status immediately. */
    fun start(scope: CoroutineScope, openShiftId: String, fixProvider: () -> GeoFix?)

    /** Writes the shift-end status and stops the periodic writer. */
    suspend fun stop(endedShiftId: String)
}

@Singleton
class RealDeviceStatusReporter @Inject constructor(
    private val trips: TripRepository,
    private val outbox: OutboxDao,
    private val events: EventDao,
    private val uplink: UplinkManager,
    private val rmLink: ReturnMachineLink,
    private val stats: UploadStats,
    @ApplicationContext private val context: Context,
) : DeviceStatusReporter {
    private val json = Json { encodeDefaults = true }
    private var job: Job? = null
    private var shiftId: String? = null
    private var fixProvider: () -> GeoFix? = { null }

    /** Starts the periodic writer for an open shift and writes the shift-start status immediately. */
    override fun start(scope: CoroutineScope, openShiftId: String, fixProvider: () -> GeoFix?) {
        shiftId = openShiftId
        this.fixProvider = fixProvider
        if (job?.isActive == true) return
        job = scope.launch {
            writeOnce()
            while (isActive) {
                delay(INTERVAL_MILLIS)
                writeOnce()
            }
        }
    }

    /**
     * Writes the shift-end status for [endedShiftId] and stops the periodic writer. Suspend: the caller
     * runs it inside its own coroutine.
     */
    /** Writes the shift-end status for [endedShiftId] and stops the periodic writer. */
    override suspend fun stop(endedShiftId: String) {
        val running = job
        job = null
        running?.cancel()
        shiftId = null
        writeOnce(endedShiftId)
    }

    /** Visible for tests. [overrideShiftId] replaces the open shift id (the shift-end status uses it). */
    suspend fun writeOnce(overrideShiftId: String? = shiftId) = write(overrideShiftId, fixProvider())

    /** Visible for tests, which pass a fixed fix instead of the provider. */
    internal suspend fun write(overrideShiftId: String?, fix: GeoFix?) {
        val now = System.currentTimeMillis()
        val counters = stats.drain()
        val (pct, charging) = battery()
        val payload = DeviceStatusPayload(
            outboxPending = runCatching { outbox.pendingCountNow() }.getOrDefault(0),
            outboxParked = runCatching { outbox.parkedCountNow() }.getOrDefault(0),
            uploadAttempts = counters.attempts,
            uploadFailures = counters.failures,
            lastUploadAtMillis = runCatching { outbox.lastDeliveredAtMillis() }.getOrNull(),
            batteryPct = pct,
            charging = charging,
            gpsFixAgeSeconds = DeviceStatusPayload.gpsAgeSeconds(fix, now),
            gpsAccuracyMeters = fix?.accuracyMeters?.toDouble(),
            network = DeviceStatusPayload.networkOf(uplink.state.value),
            rmLinkState = DeviceStatusPayload.rmLinkStateOf(rmLink.connectionState.value),
            appVersion = BuildConfig.VERSION_NAME,
            shiftId = overrideShiftId,
            localSeq = runCatching { events.maxSeq() }.getOrDefault(0L),
        )
        trips.recordDeviceStatus(json.encodeToString(payload), overrideShiftId)
    }

    /** Battery percent and charging state from the system battery service. No permission needed. */
    private fun battery(): Pair<Int?, Boolean> {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 1..100 }
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return pct to (plugged != 0)
    }

    companion object {
        const val INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
