package ph.toms.conductor.domain

/** What happened when the conductor tapped a card on the Board screen. */
sealed interface TapResult {
    /** A new trip was started for the group on the screen. */
    data class Started(val trip: Trip, val cardState: CardState) : TapResult

    /** The card was already out: show its trip (who is on it, the fare, whether it is paid). Nothing was created. */
    data class Existing(val trip: Trip, val cardState: CardState) : TapResult

    data class UnknownCard(val nfcUid: String) : TapResult

    /** The card is lost or in an alarm state: the conductor must deal with it first. */
    data class NeedsAttention(val trip: Trip?, val cardState: CardState) : TapResult

    /** The group on screen cannot become a trip (no passengers, too many, an unknown stop...). */
    data class Invalid(val reason: TripRejection) : TapResult
}

object BoardFlow {

    /**
     * Turns a tap into a result. Pure: the caller stores the trip and the new card state, and keeps
     * the screen's group, so this can be tested without a phone.
     */
    fun handleTap(
        nfcUid: String,
        passengers: PassengerTally,
        boardingStopId: String,
        destinationStopId: String,
        config: TomsConfig,
        stateOf: (String) -> CardState,
        tripFor: (String) -> Trip?,
        newTripId: () -> String,
        nowMillis: Long,
        fix: GeoFix? = null,
    ): TapResult {
        return when (val outcome = TapResolver.resolve(nfcUid, config, stateOf, tripFor)) {
            is TapOutcome.UnknownCard -> TapResult.UnknownCard(outcome.nfcUid)
            is TapOutcome.ShowTrip -> TapResult.Existing(outcome.trip, outcome.state)
            is TapOutcome.NeedsAttention -> TapResult.NeedsAttention(outcome.trip, outcome.state)
            is TapOutcome.StartTrip -> {
                when (
                    val created = TripFactory.create(
                        tripId = newTripId(),
                        nfcUid = outcome.nfcUid,
                        cardState = stateOf(outcome.nfcUid),
                        boardingStopId = boardingStopId,
                        declaredDestinationStopId = destinationStopId,
                        passengers = passengers.toLines(),
                        override = null,
                        config = config,
                        nowMillis = nowMillis,
                        fix = fix,
                    )
                ) {
                    is TripResult.Created -> TapResult.Started(created.trip, created.cardState)
                    is TripResult.Rejected -> TapResult.Invalid(created.reason)
                }
            }
        }
    }

    /** The total shown before the tap. Null when the group cannot be priced (no stop yet, no passengers). */
    fun previewTotal(
        passengers: PassengerTally,
        boardingStopId: String,
        destinationStopId: String,
        config: TomsConfig,
    ): GroupQuote? {
        val from = config.stop(boardingStopId) ?: return null
        val to = config.stop(destinationStopId) ?: return null
        return (GroupFare.quote(from, to, passengers.toLines(), config) as? GroupQuoteResult.Ok)?.quote
    }
}

/** A trip as the Collect list shows it. */
data class OpenTrip(val trip: Trip, val state: CardState)

object CollectList {

    /**
     * Trips the conductor still has to deal with: unpaid first, then alarms, then paid-but-not-returned.
     * Within a group, the oldest trip comes first, because it has waited longest. Returned and released
     * cards are not listed.
     */
    fun of(trips: List<OpenTrip>): List<OpenTrip> {
        fun rank(s: CardState) = when (s) {
            CardState.RETURNED_UNPAID -> 0
            CardState.ASSIGNED_UNPAID -> 1
            CardState.ASSIGNED_PAID -> 2
            else -> null
        }
        return trips
            .mapNotNull { t -> rank(t.state)?.let { it to t } }
            .sortedWith(compareBy({ it.first }, { it.second.trip.createdAtMillis }))
            .map { it.second }
    }

    fun unpaidCount(trips: List<OpenTrip>): Int =
        trips.count { it.state == CardState.ASSIGNED_UNPAID || it.state == CardState.RETURNED_UNPAID }

    /** Total still to collect, in centavos. Unpaid trips only. */
    fun owedCentavos(trips: List<OpenTrip>): Long =
        trips.filter { it.state == CardState.ASSIGNED_UNPAID }.sumOf { it.trip.fareCentavos.toLong() }
}
