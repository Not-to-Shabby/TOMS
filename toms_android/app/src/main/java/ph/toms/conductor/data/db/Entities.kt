package ph.toms.conductor.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
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
@Entity(tableName = "events", indices = [Index(value = ["eventId"], unique = true)])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val eventId: String,
    val deviceId: String,
    val type: String,
    val payload: String,
    val createdAtMillis: Long,
)

/** One row per event awaiting or having completed delivery. [deliveredAtMillis] null means pending. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val eventId: String,
    val deliveryChannel: String? = null,
    val deliveredAtMillis: Long? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
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
