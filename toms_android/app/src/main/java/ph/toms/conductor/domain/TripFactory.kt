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
    val discountCategoryId: String?,
    val computedFareCentavos: Int,
    val fareCentavos: Int,
    val discountCentavos: Int,
    val fareVersion: Int,
    val overrideReason: String?,
    val createdAtMillis: Long,
)

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
}

object TripFactory {

    fun create(
        tripId: String,
        nfcUid: String,
        cardState: CardState,
        boardingStopId: String,
        declaredDestinationStopId: String,
        discountCategoryId: String?,
        override: TripOverride?,
        config: TomsConfig,
        nowMillis: Long,
    ): TripResult {
        val cardUuid = config.approvedCards.cardUuidFor(nfcUid)
            ?: return TripResult.Rejected(TripRejection.UNKNOWN_CARD)

        val next = CardStateMachine.apply(cardState, CardEvent.ASSIGN)
        if (next !is Transition.Ok) return TripResult.Rejected(TripRejection.CARD_NOT_AVAILABLE)

        val boarding = config.stop(boardingStopId)
        val declared = config.stop(declaredDestinationStopId)
        if (boarding == null || declared == null) return TripResult.Rejected(TripRejection.UNKNOWN_STOP)

        val category = if (discountCategoryId == null) null else {
            config.category(discountCategoryId)?.takeIf { it.active }
                ?: return TripResult.Rejected(TripRejection.UNKNOWN_CATEGORY)
        }

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
        val quote = FareCalculator.quote(boarding, priced, category, config.fare)
        val charged = override?.fareCentavos ?: quote.fareCentavos

        return TripResult.Created(
            Trip(
                id = tripId,
                cardUuid = cardUuid,
                nfcUid = ApprovedCards.normalizeUid(nfcUid),
                boardingStopId = boarding.id,
                declaredDestinationStopId = declared.id,
                actualDestinationStopId = actual?.id?.takeIf { it != declared.id },
                discountCategoryId = category?.id,
                computedFareCentavos = quote.fareCentavos,
                fareCentavos = charged,
                discountCentavos = quote.discountCentavos,
                fareVersion = quote.fareVersion,
                overrideReason = override?.reason?.trim(),
                createdAtMillis = nowMillis,
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
