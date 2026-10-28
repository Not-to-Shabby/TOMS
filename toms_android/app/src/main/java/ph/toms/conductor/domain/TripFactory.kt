package ph.toms.conductor.domain

/**
 * Conductor override of what the card tap would otherwise produce. A reason is mandatory.
 * The declared destination is a suggestion and is never enforced; [destinationStopId] records where
 * the passenger actually goes when the conductor knows it differs.
 */
data class TripOverride(
    val reason: String,
    val destinationStopId: String? = null,
    val fareCentavos: Int? = null,
)

data class Trip(
    val id: String,
    val cardUuid: String,
    val nfcUid: String,
    val boardingStopId: String,
    val declaredDestinationStopId: String,
    val actualDestinationStopId: String?,
    /** The category of a single-category group, or null for regular or mixed. See [passengers]. */
    val discountCategoryId: String?,
    val computedFareCentavos: Int,
    val fareCentavos: Int,
    val discountCentavos: Int,
    val fareVersion: Int,
    val overrideReason: String?,
    val createdAtMillis: Long,
    /** The position fix in force when the card was tapped; null when there was none or it was stale. */
    val gps: GeoFix? = null,
    /** Who the card covers. One card carries a whole group (a family) and returning it closes the trip for all. */
    val passengers: List<LineQuote> = listOf(LineQuote(null, 1, 0, 0, 0)),
) {
    val passengerCount: Int get() = passengers.sumOf { it.count }
}

sealed interface TripResult {
    data class Created(val trip: Trip, val cardState: CardState) : TripResult
    data class Rejected(val reason: TripRejection) : TripResult
}

enum class TripRejection {
    UNKNOWN_CARD,
    CARD_NOT_AVAILABLE,
    UNKNOWN_STOP,
    UNKNOWN_CATEGORY,
    OVERRIDE_NEEDS_REASON,
    INVALID_OVERRIDE_FARE,
    NO_PASSENGERS,
    GROUP_TOO_LARGE,
}

object TripFactory {

    /**
     * Starts a trip for one card and the group it covers. A group of one is the common case.
     * The fare is the sum of each passenger's own rounded fare; an override replaces the total
     * and the computed total is kept beside it.
     */
    fun create(
        tripId: String,
        nfcUid: String,
        cardState: CardState,
        boardingStopId: String,
        declaredDestinationStopId: String,
        passengers: List<PassengerLine>,
        override: TripOverride?,
        config: TomsConfig,
        nowMillis: Long,
        fix: GeoFix? = null,
    ): TripResult {
        val cardUuid = config.approvedCards.cardUuidFor(nfcUid)
            ?: return TripResult.Rejected(TripRejection.UNKNOWN_CARD)

        val next = CardStateMachine.apply(cardState, CardEvent.ASSIGN)
        if (next !is Transition.Ok) return TripResult.Rejected(TripRejection.CARD_NOT_AVAILABLE)

        val boarding = config.stop(boardingStopId)
        val declared = config.stop(declaredDestinationStopId)
        if (boarding == null || declared == null) return TripResult.Rejected(TripRejection.UNKNOWN_STOP)

        if (override != null) {
            if (override.reason.isBlank()) return TripResult.Rejected(TripRejection.OVERRIDE_NEEDS_REASON)
            if (override.fareCentavos != null && override.fareCentavos < 0) {
                return TripResult.Rejected(TripRejection.INVALID_OVERRIDE_FARE)
            }
        }

        val actual = override?.destinationStopId?.let {
            config.stop(it) ?: return TripResult.Rejected(TripRejection.UNKNOWN_STOP)
        }
        val priced = actual ?: declared
        val quote = when (val q = GroupFare.quote(boarding, priced, passengers, config)) {
            is GroupQuoteResult.Ok -> q.quote
            is GroupQuoteResult.Invalid -> return TripResult.Rejected(q.reason)
        }
        val charged = override?.fareCentavos ?: quote.totalCentavos
        val categories = quote.lines.map { it.categoryId }.distinct()

        return TripResult.Created(
            Trip(
                id = tripId,
                cardUuid = cardUuid,
                nfcUid = ApprovedCards.normalizeUid(nfcUid),
                boardingStopId = boarding.id,
                declaredDestinationStopId = declared.id,
                actualDestinationStopId = actual?.id?.takeIf { it != declared.id },
                discountCategoryId = categories.singleOrNull(),
                computedFareCentavos = quote.totalCentavos,
                fareCentavos = charged,
                discountCentavos = quote.discountCentavos,
                fareVersion = quote.fareVersion,
                overrideReason = override?.reason?.trim(),
                createdAtMillis = nowMillis,
                gps = fix?.takeUnless { StopMatcher.isStale(it, nowMillis) },
                passengers = quote.lines,
            ),
            next.state,
        )
    }
}

object ReceiptLinks {
    /** The printed QR target: resolves from the card UUID, never from the NFC UID. */
    fun cardUrl(baseUrl: String, cardUuid: String) = "${baseUrl.trimEnd('/')}/r/$cardUuid"

    /** The unguessable per-trip URL a passenger can bookmark. */
    fun tripUrl(baseUrl: String, tripToken: String) = "${baseUrl.trimEnd('/')}/t/$tripToken"
}
