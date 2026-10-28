package ph.toms.conductor.domain

/** One conductor's run on one vehicle and route. Open while [endedAtMillis] is null. */
data class Shift(
    val id: String,
    val conductor: String,
    val vehicleId: String,
    val routeId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val endedBy: String? = null,
) {
    val isOpen: Boolean get() = endedAtMillis == null

    /** The same person on the same vehicle and route. Names are compared ignoring case and outer spaces. */
    fun sameRunAs(conductor: String, vehicleId: String, routeId: String): Boolean =
        normalizeName(this.conductor) == normalizeName(conductor) && this.vehicleId == vehicleId && this.routeId == routeId

    companion object {
        fun normalizeName(name: String): String = name.trim().lowercase(java.util.Locale.ROOT)
    }
}

/** What happened to a trip, as recorded. Only these facts feed the shift summary. */
enum class TripFact { PAID, WRITTEN_OFF, STATE_CHANGE }

/**
 * One recorded fact about a trip, tagged with the shift that was open when it was recorded.
 * [shiftId] is a tag, not a timestamp, so a phone clock that jumps cannot move money between shifts.
 * [state] is only set for [TripFact.STATE_CHANGE].
 */
data class TripEventRecord(
    val tripId: String,
    val shiftId: String?,
    val fact: TripFact,
    val state: CardState? = null,
) {
    companion object {
        /**
         * What a recorded card state means for the money. Reaching ASSIGNED_PAID is the payment; every
         * state is also kept as a state change so the trip's last known state can be read back.
         */
        fun fromState(tripId: String, shiftId: String?, state: CardState): List<TripEventRecord> =
            buildList {
                if (state == CardState.ASSIGNED_PAID) add(TripEventRecord(tripId, shiftId, TripFact.PAID))
                add(TripEventRecord(tripId, shiftId, TripFact.STATE_CHANGE, state))
            }
    }
}

/** A trip together with the shift it was created in (null for trips from before shifts existed). */
data class ShiftTrip(val trip: Trip, val shiftId: String?)

@kotlinx.serialization.Serializable
data class TypeLine(val categoryId: String?, val passengers: Int, val fareCentavos: Long)

/** Trips from an earlier shift that are still open here. Information only: never part of a total. */
@kotlinx.serialization.Serializable
data class CarriedIn(
    val paidHereCount: Int,
    val paidHereCentavos: Long,
    val stillUnpaidCount: Int,
    val stillUnpaidCentavos: Long,
    /** Earlier-shift trips the conductor wrote off at the end of this shift. Information only. */
    val writtenOffHereCount: Int = 0,
    val writtenOffHereCentavos: Long = 0,
)

@kotlinx.serialization.Serializable
data class ShiftSummary(
    val shiftId: String,
    val tripCount: Int,
    val passengers: Int,
    val discountCentavos: Long,
    val overrideCount: Int,
    /** Charged minus computed, over trips with a conductor override. Negative when fares were reduced. */
    val overrideNetCentavos: Long,
    val byType: List<TypeLine>,

    // Own trips: fares charged = collected + writtenOff + outstanding + unknown.
    val faresChargedCentavos: Long,
    val collectedOwnCentavos: Long,
    val writtenOffCentavos: Long,
    val outstandingCentavos: Long,
    /** Charged but with no recorded payment or write-off, and no open card. Flagged, never dropped. */
    val unknownCentavos: Long,
    val writtenOffTripIds: List<String>,
    val outstandingTripIds: List<String>,
    val unknownTripIds: List<String>,

    // Cash: collected from own trips + collected from carried-in trips.
    val collectedFromCarriedInCentavos: Long,
    val carriedIn: CarriedIn,
) {
    /** The money the conductor hands over for this shift. */
    val collectedCashCentavos: Long get() = collectedOwnCentavos + collectedFromCarriedInCentavos

    /** True when every own-trip fare is accounted for. Always true by construction; checked in tests. */
    val reconciles: Boolean
        get() = faresChargedCentavos == collectedOwnCentavos + writtenOffCentavos + outstandingCentavos + unknownCentavos

    companion object {

        /**
         * Builds the summary for [shiftId].
         *
         * - [trips] must include trips from every shift, so carried-in trips can be recognised.
         * - [events] must include every recorded fact for those trips.
         * - [currentStates] is each trip's card state now, used only for trips with no recorded facts of their own.
         *
         * A trip counts as collected in the shift where its PAID fact is tagged, never anywhere else.
         */
        fun of(
            shiftId: String,
            trips: List<ShiftTrip>,
            events: List<TripEventRecord>,
            currentStateByTrip: Map<String, CardState>,
        ): ShiftSummary {
            val byTrip = events.groupBy { it.tripId }

            fun paidIn(tripId: String): Set<String?> =
                byTrip[tripId].orEmpty().filter { it.fact == TripFact.PAID }.map { it.shiftId }.toSet()

            fun writtenOffIn(tripId: String): Set<String?> =
                byTrip[tripId].orEmpty().filter { it.fact == TripFact.WRITTEN_OFF }.map { it.shiftId }.toSet()

            fun everPaid(tripId: String) = paidIn(tripId).isNotEmpty()
            fun everWrittenOff(tripId: String) = writtenOffIn(tripId).isNotEmpty()

            fun stateOf(tripId: String): CardState? =
                byTrip[tripId].orEmpty().lastOrNull { it.fact == TripFact.STATE_CHANGE && it.state != null }?.state
                    ?: currentStateByTrip[tripId]

            fun isOpenUnpaid(tripId: String): Boolean {
                val s = stateOf(tripId)
                return s == CardState.ASSIGNED_UNPAID || s == CardState.RETURNED_UNPAID
            }

            val own = trips.filter { it.shiftId == shiftId }
            val carried = trips.filter { it.shiftId != null && it.shiftId != shiftId }

            var collectedOwn = 0L
            var writtenOff = 0L
            var outstanding = 0L
            var unknown = 0L
            val writtenOffIds = ArrayList<String>()
            val outstandingIds = ArrayList<String>()
            val unknownIds = ArrayList<String>()

            for (st in own) {
                val t = st.trip
                val fare = t.fareCentavos.toLong()
                when {
                    shiftId in paidIn(t.id) -> collectedOwn += fare
                    shiftId in writtenOffIn(t.id) -> { writtenOff += fare; writtenOffIds += t.id }
                    // Settled in a later shift, so it was still open when this one ended. It stays in this
                    // shift's reconciliation as outstanding and is never counted as cash here.
                    everPaid(t.id) || everWrittenOff(t.id) -> { outstanding += fare; outstandingIds += t.id }
                    isOpenUnpaid(t.id) -> { outstanding += fare; outstandingIds += t.id }
                    // No recorded payment, no write-off, and the card is no longer open (for example an unpaid
                    // return whose alarm was cleared). The money is unaccounted for, so it is flagged, never hidden.
                    else -> { unknown += fare; unknownIds += t.id }
                }
            }

            val charged = own.sumOf { it.trip.fareCentavos.toLong() }

            val paidHere = carried.filter { shiftId in paidIn(it.trip.id) }
            val writtenOffHere = carried.filter { shiftId in writtenOffIn(it.trip.id) && shiftId !in paidIn(it.trip.id) }
            val stillUnpaid = carried.filter {
                !everPaid(it.trip.id) && !everWrittenOff(it.trip.id) && isOpenUnpaid(it.trip.id)
            }

            val byType = own.flatMap { it.trip.passengers }
                .groupBy { it.categoryId }
                .map { (id, lines) -> TypeLine(id, lines.sumOf { it.count }, lines.sumOf { it.fareCentavos.toLong() }) }
                .sortedWith(compareBy({ it.categoryId != null }, { it.categoryId }))

            val overridden = own.filter { it.trip.overrideReason != null }

            return ShiftSummary(
                shiftId = shiftId,
                tripCount = own.size,
                passengers = own.sumOf { it.trip.passengerCount },
                discountCentavos = own.sumOf { it.trip.discountCentavos.toLong() },
                overrideCount = overridden.size,
                overrideNetCentavos = overridden.sumOf { (it.trip.fareCentavos - it.trip.computedFareCentavos).toLong() },
                byType = byType,
                faresChargedCentavos = charged,
                collectedOwnCentavos = collectedOwn,
                writtenOffCentavos = writtenOff,
                outstandingCentavos = outstanding,
                unknownCentavos = unknown,
                writtenOffTripIds = writtenOffIds,
                outstandingTripIds = outstandingIds,
                unknownTripIds = unknownIds,
                collectedFromCarriedInCentavos = paidHere.sumOf { it.trip.fareCentavos.toLong() },
                carriedIn = CarriedIn(
                    paidHereCount = paidHere.size,
                    paidHereCentavos = paidHere.sumOf { it.trip.fareCentavos.toLong() },
                    stillUnpaidCount = stillUnpaid.size,
                    stillUnpaidCentavos = stillUnpaid.sumOf { it.trip.fareCentavos.toLong() },
                    writtenOffHereCount = writtenOffHere.size,
                    writtenOffHereCentavos = writtenOffHere.sumOf { it.trip.fareCentavos.toLong() },
                ),
            )
        }
    }
}

object ShiftTime {
    /** "2 h 05 min", "45 min", "under 1 min". A clock that went backwards reads as zero, never negative. */
    fun formatDuration(startMillis: Long, endMillis: Long): String {
        val minutes = (maxOf(0L, endMillis - startMillis)) / 60_000
        if (minutes < 1) return "under 1 min"
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0L) "$m min" else "$h h ${m.toString().padStart(2, '0')} min"
    }
}
