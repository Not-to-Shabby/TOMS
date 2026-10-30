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
import ph.toms.conductor.data.db.ShiftEntity
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.data.db.TripEntity
import ph.toms.conductor.domain.ApprovedCardList
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.data.db.PassengerJson
import ph.toms.conductor.data.db.PassengerLineRecord
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.domain.LineQuote
import ph.toms.conductor.domain.Shift
import ph.toms.conductor.domain.ShiftSummary
import ph.toms.conductor.domain.ShiftTrip
import ph.toms.conductor.domain.Trip
import ph.toms.conductor.domain.TripEventRecord
import ph.toms.conductor.domain.TripFact

object EventTypes {
    const val TRIP_CREATED = "trip_created"
    const val CARD_STATE_CHANGED = "card_state_changed"
    const val SHIFT_STARTED = "shift_started"
    const val SHIFT_ENDED = "shift_ended"
    const val FARE_WRITTEN_OFF = "fare_written_off"
    const val CONDUCTOR_RELEASED_UNPAID = "conductor_released_unpaid"
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
    val shiftId: String? = null,
)

@Serializable
data class CardStateChangedPayload(
    val nfcUid: String,
    val cardUuid: String,
    val tripId: String?,
    val state: String,
    val shiftId: String? = null,
)

@Serializable
data class ShiftStartedPayload(val shiftId: String, val conductor: String, val vehicleId: String, val routeId: String)

/** Shared by the two events written when an unpaid fare is written off, so both carry the same audit fields. */
@Serializable
data class WriteOffPayload(
    val shiftId: String,
    val tripId: String,
    val cardUuid: String,
    val nfcUid: String,
    val amountCentavos: Int,
    val reason: String,
    val shiftConductor: String,
    val actingConductor: String,
)

@Serializable
data class ShiftEndedPayload(
    val shiftId: String,
    val conductor: String,
    val vehicleId: String,
    val routeId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val endedBy: String,
    val summary: ShiftSummary,
)

data class SavedTrip(val trip: Trip, val cardState: CardState)

sealed interface StartShiftResult {
    data class Started(val shift: Shift) : StartShiftResult
    data class Resumed(val shift: Shift) : StartShiftResult

    /** An open shift exists for someone or something else. It must be ended before a new one can start. */
    data class NeedsEnding(val open: Shift) : StartShiftResult
}

class TripRepository(
    private val db: TomsDatabase,
    private val deviceId: String,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
    private val newShiftId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun pendingCount(): Flow<Int> = db.outbox().pendingCount()

    // ---- shifts

    suspend fun openShift(): Shift? = db.shifts().open()?.toDomain()

    /**
     * Starts a shift, or resumes the open one when it is the same conductor on the same vehicle and route.
     * Any other open shift blocks the start: it has to be ended first, so its unpaid trips are always asked about.
     */
    suspend fun startShift(conductor: String, vehicleId: String, routeId: String): StartShiftResult =
        db.withTransaction {
            val open = db.shifts().open()?.toDomain()
            when {
                open != null && open.sameRunAs(conductor, vehicleId, routeId) -> StartShiftResult.Resumed(open)
                open != null -> StartShiftResult.NeedsEnding(open)
                else -> {
                    val now = clock()
                    val shift = Shift(newShiftId(), conductor.trim(), vehicleId, routeId, now)
                    db.shifts().insert(shift.toEntity())
                    appendEvent(
                        EventTypes.SHIFT_STARTED,
                        json.encodeToString(ShiftStartedPayload(shift.id, shift.conductor, vehicleId, routeId)),
                        now,
                        shiftId = shift.id,
                    )
                    StartShiftResult.Started(shift)
                }
            }
        }

    /** The summary of [shiftId] as it stands now, from the recorded trips and events. */
    suspend fun summaryFor(shiftId: String): ShiftSummary {
        val trips = db.trips().all().map { ShiftTrip(it.toDomain(), it.shiftId) }
        val facts = db.events().tripFacts().flatMap { it.toFacts() }
        val states = db.cards().all().mapNotNull { c -> c.currentTripId?.let { it to CardState.valueOf(c.state) } }.toMap()
        return ShiftSummary.of(shiftId, trips, facts, states)
    }

    /**
     * Ends the shift: closes the row with its frozen summary and writes the shift_ended event, in one transaction.
     * Returns the summary, or null when the shift was already ended or does not exist (nothing is written then).
     */
    suspend fun endShift(shiftId: String, endedBy: String): ShiftSummary? =
        db.withTransaction {
            val shift = db.shifts().byId(shiftId)?.toDomain() ?: return@withTransaction null
            if (!shift.isOpen) return@withTransaction null
            val now = clock()
            val summary = summaryFor(shiftId)
            val closed = db.shifts().close(shiftId, now, endedBy.trim(), json.encodeToString(summary))
            if (closed == 0) return@withTransaction null
            appendEvent(
                EventTypes.SHIFT_ENDED,
                json.encodeToString(
                    ShiftEndedPayload(shiftId, shift.conductor, shift.vehicleId, shift.routeId, shift.startedAtMillis, now, endedBy.trim(), summary),
                ),
                now,
                shiftId = shiftId,
            )
            summary
        }

    suspend fun frozenSummary(shiftId: String): ShiftSummary? =
        db.shifts().byId(shiftId)?.summaryJson?.let { runCatching { json.decodeFromString<ShiftSummary>(it) }.getOrNull() }

    // ---- trips and cards

    /**
     * Writes the trip, the card state, the event and its outbox row in one transaction, so a crash
     * can never leave a trip without an event to upload or an event without a trip.
     */
    suspend fun recordTrip(trip: Trip, cardState: CardState, nowMillis: Long, shiftId: String? = null) {
        db.withTransaction {
            db.trips().insert(trip.toEntity(shiftId))
            db.cards().upsert(CardEntity(trip.nfcUid, trip.cardUuid, cardState.name, trip.id, nowMillis))
            appendEvent(
                EventTypes.TRIP_CREATED,
                json.encodeToString(trip.toPayload(shiftId)),
                nowMillis,
                shiftId = shiftId,
                tripId = trip.id,
            )
        }
    }

    suspend fun recordCardState(
        nfcUid: String,
        cardUuid: String,
        tripId: String?,
        state: CardState,
        nowMillis: Long,
        shiftId: String? = null,
    ) {
        db.withTransaction {
            db.cards().upsert(CardEntity(nfcUid, cardUuid, state.name, tripId, nowMillis))
            appendEvent(
                EventTypes.CARD_STATE_CHANGED,
                json.encodeToString(CardStateChangedPayload(nfcUid, cardUuid, tripId, state.name, shiftId)),
                nowMillis,
                shiftId = shiftId,
                tripId = tripId,
                cardState = state.name,
            )
        }
    }

    /** The trips of [shiftId] whose card still holds them, with the card's state. A freed card no longer holds its trip. */
    suspend fun openTripsOf(shiftId: String): List<SavedTrip> {
        val cardByTrip = db.cards().all().filter { it.currentTripId != null }.associateBy { it.currentTripId }
        return db.trips().all().filter { it.shiftId == shiftId }.mapNotNull { t ->
            val state = cardByTrip[t.id]?.let { CardState.valueOf(it.state) } ?: return@mapNotNull null
            if (state == CardState.AVAILABLE) null else SavedTrip(t.toDomain(), state)
        }
    }

    /**
     * End-of-shift write-off of an unpaid fare, in one transaction. Two honest cases:
     *
     * - The card is still with the passenger (ASSIGNED_UNPAID): the write-off, the conductor's release of the
     *   card, then the card becoming available. The card never reached the terminal, so no terminal state is written.
     * - The card really came back unpaid (RETURNED_UNPAID): the write-off, then the card becoming available, which
     *   is the conductor clearing the alarm. The terminal return already in its history is true, so it is kept, and
     *   no "release" is recorded because the card was never released from the vehicle.
     *
     * Any other state is left exactly as it is: a paid card, or one already written off and freed.
     */
    suspend fun writeOffAndRelease(shiftId: String, tripId: String, actingConductor: String): Boolean =
        db.withTransaction {
            val shift = db.shifts().byId(shiftId) ?: return@withTransaction false
            val trip = db.trips().byId(tripId) ?: return@withTransaction false
            val card = db.cards().byTrip(tripId) ?: return@withTransaction false
            val before = CardState.valueOf(card.state)
            if (before != CardState.ASSIGNED_UNPAID && before != CardState.RETURNED_UNPAID) return@withTransaction false

            val now = clock()
            val payload = json.encodeToString(
                WriteOffPayload(
                    shiftId = shiftId, tripId = tripId, cardUuid = trip.cardUuid, nfcUid = trip.nfcUid,
                    amountCentavos = trip.fareCentavos, reason = "uncollected at end of shift",
                    shiftConductor = shift.conductor, actingConductor = actingConductor.trim(),
                ),
            )
            appendEvent(EventTypes.FARE_WRITTEN_OFF, payload, now, shiftId = shiftId, tripId = tripId)
            if (before == CardState.ASSIGNED_UNPAID) {
                appendEvent(EventTypes.CONDUCTOR_RELEASED_UNPAID, payload, now, shiftId = shiftId, tripId = tripId)
            }
            db.cards().upsert(CardEntity(trip.nfcUid, trip.cardUuid, CardState.AVAILABLE.name, tripId, now))
            appendEvent(
                EventTypes.CARD_STATE_CHANGED,
                json.encodeToString(CardStateChangedPayload(trip.nfcUid, trip.cardUuid, tripId, CardState.AVAILABLE.name, shiftId)),
                now,
                shiftId = shiftId,
                tripId = tripId,
                cardState = CardState.AVAILABLE.name,
            )
            true
        }

    suspend fun loadCardStates(): Map<String, CardState> =
        db.cards().all().associate { it.nfcUid to CardState.valueOf(it.state) }

    suspend fun loadTrips(): List<Trip> = db.trips().all().map { it.toDomain() }

    /** Trips that have been written off, by trip id. Used to keep them off the Collect list and the owed total. */
    suspend fun writtenOffTripIds(): Set<String> =
        db.events().ofType(EventTypes.FARE_WRITTEN_OFF).mapNotNull { it.tripId }.toSet()

    /**
     * The saved card list, or null if there is none yet. The statuses live under their own key, so a phone that
     * saved only the card map before this existed still loads it (with no statuses) instead of losing it.
     */
    suspend fun loadApprovedCardList(): ApprovedCardList? {
        val cards = db.config().get("approved_cards")
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it.json) }.getOrNull() }
            ?: return null
        val statuses = db.config().get("approved_card_statuses")
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it.json) }.getOrNull() }
            ?: emptyMap()
        return ApprovedCardList(cards, statuses)
    }

    suspend fun loadApprovedCards(): Map<String, String>? = loadApprovedCardList()?.cards

    /** Saves the list exactly as the server sent it, including an empty one: when the server has no cards left, the phone must too. */
    suspend fun saveApprovedCardList(list: ApprovedCardList) {
        val now = System.currentTimeMillis()
        db.config().put(ConfigCacheEntity(key = "approved_cards", version = 1, json = json.encodeToString(list.cards), fetchedAtMillis = now))
        db.config().put(ConfigCacheEntity(key = "approved_card_statuses", version = 1, json = json.encodeToString(list.statuses), fetchedAtMillis = now))
    }

    suspend fun saveApprovedCards(cards: Map<String, String>) = saveApprovedCardList(ApprovedCardList(cards))

    private suspend fun appendEvent(
        type: String,
        payload: String,
        nowMillis: Long,
        shiftId: String? = null,
        tripId: String? = null,
        cardState: String? = null,
    ) {
        val eventId = newEventId()
        db.events().insert(
            EventEntity(
                eventId = eventId, deviceId = deviceId, type = type, payload = payload, createdAtMillis = nowMillis,
                shiftId = shiftId, tripId = tripId, cardState = cardState,
            ),
        )
        db.outbox().insert(OutboxEntity(eventId = eventId))
    }
}

/** The recorded facts one stored event stands for. A write-off is a money fact; a card state is a state fact. */
private fun EventEntity.toFacts(): List<TripEventRecord> {
    val id = tripId ?: return emptyList()
    return when (type) {
        EventTypes.FARE_WRITTEN_OFF -> listOf(TripEventRecord(id, shiftId, TripFact.WRITTEN_OFF))
        EventTypes.CARD_STATE_CHANGED -> {
            val state = cardState?.let { runCatching { CardState.valueOf(it) }.getOrNull() } ?: return emptyList()
            TripEventRecord.fromState(id, shiftId, state)
        }
        else -> emptyList()
    }
}

private fun ShiftEntity.toDomain() = Shift(id, conductor, vehicleId, routeId, startedAtMillis, endedAtMillis, endedBy)

private fun Shift.toEntity() = ShiftEntity(id, conductor, vehicleId, routeId, startedAtMillis, endedAtMillis, endedBy)

private fun LineQuote.toRecord() = PassengerLineRecord(categoryId, count, perPersonCentavos, fareCentavos, discountCentavos)

private fun PassengerLineRecord.toQuote() = LineQuote(categoryId, count, perPersonCentavos, fareCentavos, discountCentavos)

private fun Trip.toEntity(shiftId: String?) = TripEntity(
    id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
    discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion,
    overrideReason, createdAtMillis,
    passengersJson = PassengerJson.encode(passengers.map { it.toRecord() }),
    gpsLat = gps?.lat, gpsLon = gps?.lon, gpsAccuracyMeters = gps?.accuracyMeters, gpsFixAtMillis = gps?.fixAtMillis,
    shiftId = shiftId,
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

private fun Trip.toPayload(shiftId: String?) = TripCreatedPayload(
    id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
    discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion, overrideReason,
    gps?.lat, gps?.lon, gps?.accuracyMeters, gps?.fixAtMillis,
    passengerCount = passengerCount,
    passengers = passengers.map { it.toRecord() },
    shiftId = shiftId,
)
