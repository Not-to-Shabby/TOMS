package ph.toms.conductor.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ph.toms.conductor.data.db.CardEntity
import ph.toms.conductor.data.db.ConfigCacheEntity
import ph.toms.conductor.data.db.EventEntity
import ph.toms.conductor.data.db.OutboxEntity
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.data.db.TripEntity
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.data.db.PassengerJson
import ph.toms.conductor.data.db.PassengerLineRecord
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.domain.LineQuote
import ph.toms.conductor.domain.Trip

object EventTypes {
    const val TRIP_CREATED = "trip_created"
    const val CARD_STATE_CHANGED = "card_state_changed"
}

@Serializable
data class TripCreatedPayload(
    val tripId: String,
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
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val gpsAccuracyMeters: Float? = null,
    val gpsFixAtMillis: Long? = null,
    val passengerCount: Int = 1,
    val passengers: List<PassengerLineRecord> = emptyList(),
)

@Serializable
data class CardStateChangedPayload(
    val nfcUid: String,
    val cardUuid: String,
    val tripId: String?,
    val state: String,
)

data class SavedTrip(val trip: Trip, val cardState: CardState)

class TripRepository(
    private val db: TomsDatabase,
    private val deviceId: String,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { encodeDefaults = true }

    fun pendingCount(): Flow<Int> = db.outbox().pendingCount()

    /**
     * Writes the trip, the card state, the event and its outbox row in one transaction, so a crash
     * can never leave a trip without an event to upload or an event without a trip.
     */
    suspend fun recordTrip(trip: Trip, cardState: CardState, nowMillis: Long) {
        db.withTransaction {
            db.trips().insert(trip.toEntity())
            db.cards().upsert(CardEntity(trip.nfcUid, trip.cardUuid, cardState.name, trip.id, nowMillis))
            appendEvent(
                EventTypes.TRIP_CREATED,
                json.encodeToString(trip.toPayload()),
                nowMillis,
            )
        }
    }

    suspend fun recordCardState(nfcUid: String, cardUuid: String, tripId: String?, state: CardState, nowMillis: Long) {
        db.withTransaction {
            db.cards().upsert(CardEntity(nfcUid, cardUuid, state.name, tripId, nowMillis))
            appendEvent(
                EventTypes.CARD_STATE_CHANGED,
                json.encodeToString(CardStateChangedPayload(nfcUid, cardUuid, tripId, state.name)),
                nowMillis,
            )
        }
    }

    suspend fun loadCardStates(): Map<String, CardState> =
        db.cards().all().associate { it.nfcUid to CardState.valueOf(it.state) }

    suspend fun loadTrips(): List<Trip> = db.trips().all().map { it.toDomain() }

    suspend fun loadApprovedCards(): Map<String, String>? {
        val cached = db.config().get("approved_cards") ?: return null
        return runCatching { json.decodeFromString<Map<String, String>>(cached.json) }.getOrNull()
    }

    suspend fun saveApprovedCards(cards: Map<String, String>) {
        db.config().put(
            ConfigCacheEntity(
                key = "approved_cards",
                version = 1,
                json = json.encodeToString(cards),
                fetchedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun appendEvent(type: String, payload: String, nowMillis: Long) {
        val eventId = newEventId()
        db.events().insert(EventEntity(eventId = eventId, deviceId = deviceId, type = type, payload = payload, createdAtMillis = nowMillis))
        db.outbox().insert(OutboxEntity(eventId = eventId))
    }
}

private fun LineQuote.toRecord() = PassengerLineRecord(categoryId, count, perPersonCentavos, fareCentavos, discountCentavos)

private fun PassengerLineRecord.toQuote() = LineQuote(categoryId, count, perPersonCentavos, fareCentavos, discountCentavos)

private fun Trip.toEntity() = TripEntity(
    id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
    discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion,
    overrideReason, createdAtMillis,
    passengersJson = PassengerJson.encode(passengers.map { it.toRecord() }),
    gpsLat = gps?.lat, gpsLon = gps?.lon, gpsAccuracyMeters = gps?.accuracyMeters, gpsFixAtMillis = gps?.fixAtMillis,
)

private fun TripEntity.toDomain(): Trip {
    val lines = PassengerJson.decode(passengersJson).map { it.toQuote() }
    return Trip(
        id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
        discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion,
        overrideReason, createdAtMillis,
        gps = if (gpsLat != null && gpsLon != null && gpsFixAtMillis != null) {
            GeoFix(gpsLat, gpsLon, gpsAccuracyMeters, gpsFixAtMillis)
        } else null,
        passengers = lines.ifEmpty { listOf(LineQuote(discountCategoryId, 1, fareCentavos, fareCentavos, discountCentavos)) },
    )
}

private fun Trip.toPayload() = TripCreatedPayload(
    id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
    discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion, overrideReason,
    gps?.lat, gps?.lon, gps?.accuracyMeters, gps?.fixAtMillis,
    passengerCount = passengerCount,
    passengers = passengers.map { it.toRecord() },
)
