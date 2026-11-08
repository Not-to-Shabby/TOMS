package ph.toms.conductor.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.ble.RmConnectionState
import ph.toms.conductor.sync.UplinkState

/**
 * Periodic shift telemetry, written to the outbox like any other event. No personal data and no
 * passenger card UIDs: only counters, timings and device state, so the researcher can answer the
 * delivery-reliability question (RQ4) even for events that never left a phone in a dead zone.
 */
@Serializable
data class DeviceStatusPayload(
    /** Events waiting in the outbox at write time. */
    val outboxPending: Int,
    /** Events the server refused and that are parked (kept, not retried). */
    val outboxParked: Int,
    /** Upload attempts (events offered to the server) since the previous status. */
    val uploadAttempts: Long,
    /** Events whose upload batch failed since the previous status. */
    val uploadFailures: Long,
    /** When an upload last reached the server, or null when none ever did. */
    val lastUploadAtMillis: Long?,
    /** Battery percent, or null when the device does not report one. */
    val batteryPct: Int?,
    /** True when the device is plugged in or charging. */
    val charging: Boolean,
    /** Age of the last GPS fix in seconds, or null without a fix. The position itself is never sent. */
    val gpsFixAgeSeconds: Int?,
    /** GPS accuracy radius in meters, or null without a fix. */
    val gpsAccuracyMeters: Double?,
    /** none, wifi or mobile at write time. */
    val network: String,
    /** Return Machine link state: disconnected, scanning, connecting or connected. */
    val rmLinkState: String,
    /** The app's version name, so the researcher knows which build produced the row. */
    val appVersion: String,
    /** The open shift the status belongs to, or null outside a shift. */
    val shiftId: String?,
    /** The phone's event sequence number at write time; gaps against the server mean lost events. */
    val localSeq: Long,
) {
    companion object {
        fun networkOf(state: UplinkState): String = when (state) {
            is UplinkState.Online -> if (state.isCellular) "mobile" else "wifi"
            else -> "none"
        }

        fun rmLinkStateOf(state: RmConnectionState): String = when (state) {
            is RmConnectionState.Connected -> "connected"
            is RmConnectionState.Connecting -> "connecting"
            RmConnectionState.Scanning -> "scanning"
            RmConnectionState.Disconnected -> "disconnected"
        }

        fun gpsAgeSeconds(fix: GeoFix?, nowMillis: Long): Int? = fix?.let {
            ((nowMillis - it.fixAtMillis) / 1000).coerceAtLeast(0).toInt()
        }
    }
}
