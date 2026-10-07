package ph.toms.conductor.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ph.toms.conductor.data.db.CardEntity
import ph.toms.conductor.data.db.EventEntity
import ph.toms.conductor.data.db.OutboxEntity
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.data.db.TripEntity
import ph.toms.conductor.domain.CardState
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

    private suspend fun appendEvent(type: String, payload: String, nowMillis: Long) {
        val eventId = newEventId()
        db.events().insert(EventEntity(eventId = eventId, deviceId = deviceId, type = type, payload = payload, createdAtMillis = nowMillis))
        db.outbox().insert(OutboxEntity(eventId = eventId))
    }
}

private fun Trip.toEntity() = TripEntity(
    id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
    discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion,
    overrideReason, createdAtMillis,
)

private fun TripEntity.toDomain() = Trip(
    id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
    discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion,
    overrideReason, createdAtMillis,
)

private fun Trip.toPayload() = TripCreatedPayload(
    id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
    discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion, overrideReason,
)
