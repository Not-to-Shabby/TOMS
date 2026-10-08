package ph.toms.conductor.domain

import kotlin.math.ceil

/**
 * [baseCentavos] is the pre-discount, pre-rounding fare; [fareCentavos] is what is charged.
 * [discountCentavos] is never negative: rounding up a regular fare is not a discount.
 */
data class FareQuote(
    val baseCentavos: Int,
    val fareCentavos: Int,
    val discountCentavos: Int,
    val fareVersion: Int,
)

object FareCalculator {

    fun quote(
        distanceMeters: Double,
        category: DiscountCategory?,
        config: FareConfig,
    ): FareQuote {
        val distKm = distanceMeters / 1000.0
        var base = config.baseFareCentavos.toLong()
        if (distKm > config.baseDistanceKm) {
            val startedKm = ceil(distKm - config.baseDistanceKm).toLong()
            base += startedKm * config.perKmCentavos
        }
        val percent = category?.percent ?: 0
        val fare = roundToStep(base * (100 - percent), config.roundingStepCentavos)
        return FareQuote(
            baseCentavos = base.toInt(),
            fareCentavos = fare.toInt(),
            discountCentavos = (base - fare).coerceAtLeast(0).toInt(),
            fareVersion = config.version,
        )
    }

    fun quote(origin: Stop, destination: Stop, category: DiscountCategory?, config: FareConfig): FareQuote =
        quote(haversineMeters(origin.lat, origin.lon, destination.lat, destination.lon), category, config)

    /** [scaledCentavos] is centavos multiplied by 100 (so a percentage can be applied exactly). */
    private fun roundToStep(scaledCentavos: Long, stepCentavos: Int): Long {
        val stepScaled = stepCentavos.toLong() * 100
        return ((scaledCentavos + stepScaled / 2) / stepScaled) * stepCentavos
    }
}
