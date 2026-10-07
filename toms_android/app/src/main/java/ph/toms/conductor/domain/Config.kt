package ph.toms.conductor.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Stop(val id: String, val name: String, val lat: Double, val lon: Double)

data class DiscountCategory(
    val id: String,
    val name: String,
    val percent: Int,
    val active: Boolean = true,
) {
    init {
        require(percent in 0..100) { "discount percent must be 0..100" }
    }
}

/**
 * Fare parameters as delivered by server settings. Money is in centavos.
 * [roundingStepCentavos] of 100 rounds the charged fare to the nearest whole peso (half up).
 */
data class FareConfig(
    val version: Int,
    val baseFareCentavos: Int,
    val baseDistanceKm: Double,
    val perKmCentavos: Int,
    val roundingStepCentavos: Int,
)

/** Maps the NFC UID read from a card to the printed QR card UUID. Set from the card registry. */
class ApprovedCards(entries: Map<String, String>) {
    private val byUid = entries.mapKeys { normalizeUid(it.key) }

    fun cardUuidFor(uid: String): String? = byUid[normalizeUid(uid)]

    val size: Int get() = byUid.size

    companion object {
        fun normalizeUid(uid: String): String =
            uid.filterNot { it == ':' || it == ' ' || it == '-' }.uppercase()
    }
}

data class TomsConfig(
    val version: Int,
    val stops: List<Stop>,
    val fare: FareConfig,
    val discountCategories: List<DiscountCategory>,
    val approvedCards: ApprovedCards,
) {
    fun stop(id: String): Stop? = stops.firstOrNull { it.id == id }
    fun category(id: String): DiscountCategory? = discountCategories.firstOrNull { it.id == id }
}

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
