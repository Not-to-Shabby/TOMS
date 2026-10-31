package ph.toms.conductor.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One conductor's run on one vehicle and route. Open while [endedAtMillis] is null. [summaryJson] is
 * written only when the shift is ended, and is then the frozen record of what the shift came to.
 */
@Entity(tableName = "shifts")
data class ShiftEntity(
    @PrimaryKey val id: String,
    val conductor: String,
    val vehicleId: String,
    val routeId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val endedBy: String? = null,
    val summaryJson: String? = null,
)

@Entity(tableName = "trips", indices = [Index(value = ["shiftId"])])
data class TripEntity(
    @PrimaryKey val id: String,
    val cardUuid: String,
    val nfcUid: String,
    val boardingStopId: String,
    val declaredDestinationStopId: String,
    val actualDestinationStopId: String?,
    val discountCategoryId: String?,
    val computedFareCentavos: Int,
    val fareCentavos: Int,
    val discountCentavos: Int,
    val fareVersion: Int,
    val overrideReason: String?,
    val createdAtMillis: Long,
    /** JSON list of passenger lines (type, count, per-person and line fare). Added in schema version 2. */
    @ColumnInfo(defaultValue = "[]")
    val passengersJson: String = "[]",
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val gpsAccuracyMeters: Float? = null,
    val gpsFixAtMillis: Long? = null,
    /** The shift open when the trip was created. Null for trips saved before shifts existed. */
    val shiftId: String? = null,
)

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val nfcUid: String,
    val cardUuid: String,
    val state: String,
    val currentTripId: String?,
    val updatedAtMillis: Long,
)

/** Append-only. Corrections are new events. [seq] is the local sequence number for this device. */
@Entity(tableName = "events", indices = [Index(value = ["eventId"], unique = true), Index(value = ["shiftId"])])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val eventId: String,
    val deviceId: String,
    val type: String,
    val payload: String,
    val createdAtMillis: Long,
    /** The shift open when the event was recorded. A tag, so the summary never depends on the clock. */
    val shiftId: String? = null,
    /** The trip the event is about, when it is about one. Lets the summary read facts without parsing payloads. */
    val tripId: String? = null,
    /** For card_state_changed: the state reached. */
    val cardState: String? = null,
)

/**
 * One row per event awaiting or having completed delivery. [deliveredAtMillis] null means pending, unless
 * [parkedAtMillis] is set: the server refused the event with [parkedReason], so it is kept but not retried.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val eventId: String,
    val deliveryChannel: String? = null,
    val deliveredAtMillis: Long? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
    val parkedAtMillis: Long? = null,
    val parkedReason: String? = null,
)

/**
 * The last good copy of what `GET /api/device/config` sent, so the phone works offline. One row (id 1):
 * a new copy replaces it. [json] is the response as the server sent it; [version] and [routeName] are
 * kept beside it so the More screen can show them without reading the JSON.
 */
@Entity(tableName = "server_config")
data class ServerConfigEntity(
    @PrimaryKey val id: Int = 1,
    val version: Int,
    val routeName: String,
    val json: String,
    val fetchedAtMillis: Long,
)

@Entity(tableName = "config_cache")
data class ConfigCacheEntity(
    @PrimaryKey val key: String,
    val version: Int,
    val json: String,
    val fetchedAtMillis: Long,
)

data class PendingEvent(
    val seq: Long,
    val eventId: String,
    val deviceId: String,
    val type: String,
    val payload: String,
    val createdAtMillis: Long,
    val attemptCount: Int,
)
