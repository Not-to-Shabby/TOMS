package ph.toms.conductor.domain

/**
 * One passenger type in a group, and how many of that type. A child is priced as a student, so there
 * is no separate child type. [categoryId] null means a regular passenger.
 */
data class PassengerLine(val categoryId: String?, val count: Int) {
    init {
        require(count >= 1) { "a passenger line needs at least one passenger" }
    }
}

/** The most people one card may cover. A guard against a stuck button, not a business rule. */
const val MAX_GROUP_SIZE = 30

/** Per-line result. [fareCentavos] is for the whole line (per-person fare times count). */
data class LineQuote(
    val categoryId: String?,
    val count: Int,
    val perPersonCentavos: Int,
    val fareCentavos: Int,
    val discountCentavos: Int,
)

data class GroupQuote(
    val lines: List<LineQuote>,
    val totalCentavos: Int,
    val discountCentavos: Int,
    val passengerCount: Int,
    val fareVersion: Int,
)

sealed interface GroupQuoteResult {
    data class Ok(val quote: GroupQuote) : GroupQuoteResult
    data class Invalid(val reason: TripRejection) : GroupQuoteResult
}

object GroupFare {

    /**
     * Fare is worked out per person and rounded per person, then summed, because each passenger pays
     * their own rounded fare. Two students are therefore never priced as one rounded pair.
     * Lines with the same type are merged first, so the order the conductor tapped in does not matter.
     */
    fun quote(
        distanceMeters: Double,
        lines: List<PassengerLine>,
        config: TomsConfig,
    ): GroupQuoteResult {
        if (lines.isEmpty()) return GroupQuoteResult.Invalid(TripRejection.NO_PASSENGERS)
        val merged = lines.groupBy { it.categoryId }.map { (id, group) -> PassengerLine(id, group.sumOf { it.count }) }
        val total = merged.sumOf { it.count }
        if (total > MAX_GROUP_SIZE) return GroupQuoteResult.Invalid(TripRejection.GROUP_TOO_LARGE)

        val quoted = ArrayList<LineQuote>(merged.size)
        for (line in merged.sortedWith(compareBy({ it.categoryId != null }, { it.categoryId }))) {
            val category = if (line.categoryId == null) null else {
                config.category(line.categoryId)?.takeIf { it.active }
                    ?: return GroupQuoteResult.Invalid(TripRejection.UNKNOWN_CATEGORY)
            }
            val one = FareCalculator.quote(distanceMeters, category, config.fare)
            quoted += LineQuote(
                categoryId = line.categoryId,
                count = line.count,
                perPersonCentavos = one.fareCentavos,
                fareCentavos = one.fareCentavos * line.count,
                discountCentavos = one.discountCentavos * line.count,
            )
        }
        return GroupQuoteResult.Ok(
            GroupQuote(
                lines = quoted,
                totalCentavos = quoted.sumOf { it.fareCentavos },
                discountCentavos = quoted.sumOf { it.discountCentavos },
                passengerCount = total,
                fareVersion = config.fare.version,
            ),
        )
    }

    fun quote(origin: Stop, destination: Stop, lines: List<PassengerLine>, config: TomsConfig): GroupQuoteResult =
        quote(haversineMeters(origin.lat, origin.lon, destination.lat, destination.lon), lines, config)
}

/** Add-one, undo and clear for the passenger buttons on the Board screen. Pure, so it is easy to test. */
data class PassengerTally(val counts: Map<String?, Int> = emptyMap(), private val history: List<String?> = emptyList()) {

    val total: Int get() = counts.values.sum()

    /** Adds one passenger of [categoryId]. Ignored once the group is full. */
    fun add(categoryId: String?): PassengerTally {
        if (total >= MAX_GROUP_SIZE) return this
        return PassengerTally(counts + (categoryId to ((counts[categoryId] ?: 0) + 1)), history + categoryId)
    }

    /** Removes the most recently added passenger, whatever type it was. */
    fun undo(): PassengerTally {
        if (history.isEmpty()) return this
        val last = history.last()
        val remaining = (counts[last] ?: 0) - 1
        val next = if (remaining <= 0) counts - last else counts + (last to remaining)
        return PassengerTally(next, history.dropLast(1))
    }

    fun clear() = PassengerTally()

    fun toLines(): List<PassengerLine> = counts.filterValues { it > 0 }.map { (id, n) -> PassengerLine(id, n) }

    companion object {
        /** A fresh tally for the next group: one regular passenger, the common case. */
        fun starting(): PassengerTally = PassengerTally().add(null)
    }
}
