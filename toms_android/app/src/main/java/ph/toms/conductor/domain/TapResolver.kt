package ph.toms.conductor.domain

/** What a card tap means right now. */
sealed interface TapOutcome {
    /** The card is free: start a new trip with the group on the Board screen. */
    data class StartTrip(val nfcUid: String) : TapOutcome

    /**
     * The card is already out. The conductor needs to see that trip, not an error: who is on it, the
     * fare, and whether it has been paid. Nothing is created, so a card can never carry two trips.
     */
    data class ShowTrip(val trip: Trip, val state: CardState) : TapOutcome

    /** The card is not on the approved list. */
    data class UnknownCard(val nfcUid: String) : TapOutcome

    /** The card is lost or in an alarm state and needs the conductor to resolve it first. */
    data class NeedsAttention(val trip: Trip?, val state: CardState) : TapOutcome
}

object TapResolver {

    /**
     * [tripFor] finds the open trip a card is attached to, or null. A card that the state machine
     * says is out but has no trip on record is reported as NeedsAttention rather than guessed at.
     */
    fun resolve(
        nfcUid: String,
        config: TomsConfig,
        stateOf: (String) -> CardState,
        tripFor: (String) -> Trip?,
    ): TapOutcome {
        val uid = ApprovedCards.normalizeUid(nfcUid)
        if (config.approvedCards.cardUuidFor(uid) == null) return TapOutcome.UnknownCard(uid)
        val state = stateOf(uid)
        return when (state) {
            CardState.AVAILABLE -> TapOutcome.StartTrip(uid)
            CardState.ASSIGNED_UNPAID, CardState.ASSIGNED_PAID, CardState.RETURNED -> {
                val trip = tripFor(uid)
                if (trip == null) TapOutcome.NeedsAttention(null, state) else TapOutcome.ShowTrip(trip, state)
            }
            CardState.RETURNED_UNPAID, CardState.LOST -> TapOutcome.NeedsAttention(tripFor(uid), state)
        }
    }
}
