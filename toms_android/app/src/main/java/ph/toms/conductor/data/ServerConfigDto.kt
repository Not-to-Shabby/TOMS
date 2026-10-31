package ph.toms.conductor.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ph.toms.conductor.domain.DiscountCategory
import ph.toms.conductor.domain.FareConfig
import ph.toms.conductor.domain.MAX_FARE_VERSION
import ph.toms.conductor.domain.ServerConfig
import ph.toms.conductor.domain.Stop

/** The shape of `GET /api/device/config`. It is also what is saved in Room, so a saved copy is read the same way. */
@Serializable
data class DeviceConfigResponse(
    val version: Int,
    val route: RouteDto,
    val stops: List<StopDto>,
    val fare: FareDto,
)

@Serializable
data class RouteDto(val id: Int, val name: String)

@Serializable
data class StopDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    @SerialName("radius_m") val radiusM: Int = 100,
)

@Serializable
data class FareDto(
    @SerialName("matrix_id") val matrixId: Int,
    @SerialName("base_fare_centavos") val baseFareCentavos: Int,
    @SerialName("base_distance_km") val baseDistanceKm: Double,
    @SerialName("per_km_fare_centavos") val perKmFareCentavos: Int,
    @SerialName("rounding_step_cents") val roundingStepCents: Int,
    val discounts: List<DiscountDto>,
)

@Serializable
data class DiscountDto(val id: String, val name: String, val percent: Int)

/** The server sent (or the phone saved) a config that cannot be used to charge anyone. */
class InvalidServerConfig(message: String) : Exception(message)

// The server's own limits on a fare (fares.ts), so a bad number cannot overflow the phone's Int arithmetic.
private const val MAX_BASE_FARE_CENTAVOS = 10_000_000
private const val MAX_PER_KM_CENTAVOS = 1_000_000

/**
 * Checks everything a fare is worked out from, then builds the config. The phone never trusts the network:
 * a config that fails any check is refused whole, and the config already saved stays in use.
 */
fun DeviceConfigResponse.toServerConfig(fetchedAtMillis: Long): ServerConfig {
    fun bad(why: String): Nothing = throw InvalidServerConfig(why)

    if (version !in 1..MAX_FARE_VERSION) bad("version $version is outside 1 to $MAX_FARE_VERSION")
    if (stops.size < 2) bad("a route needs at least two stops, got ${stops.size}")
    if (stops.any { it.id.isBlank() || it.name.isBlank() }) bad("a stop has no id or no name")
    if (stops.map { it.id }.toSet().size != stops.size) bad("two stops share an id")
    stops.forEach {
        if (!it.lat.isFinite() || it.lat !in -90.0..90.0 || !it.lon.isFinite() || it.lon !in -180.0..180.0) {
            bad("stop \"${it.name}\" has no valid position")
        }
    }

    if (fare.baseFareCentavos !in 0..MAX_BASE_FARE_CENTAVOS) bad("base fare ${fare.baseFareCentavos} is out of range")
    if (fare.perKmFareCentavos !in 0..MAX_PER_KM_CENTAVOS) bad("per-km fare ${fare.perKmFareCentavos} is out of range")
    if (!fare.baseDistanceKm.isFinite() || fare.baseDistanceKm <= 0.0) bad("base distance ${fare.baseDistanceKm} is not positive")
    if (fare.roundingStepCents < 1) bad("rounding step ${fare.roundingStepCents} is not positive")

    val ids = fare.discounts.map { it.id }
    if (ids.any { it.isBlank() }) bad("a discount has no id")
    if (ids.toSet().size != ids.size) bad("two discounts share an id")
    val categories = fare.discounts.map {
        if (it.percent !in 0..100) bad("discount \"${it.id}\" is ${it.percent} percent, not 0 to 100")
        DiscountCategory(it.id, it.name.ifBlank { it.id }, it.percent)
    }

    return ServerConfig(
        version = version,
        routeName = route.name,
        stops = stops.map { Stop(it.id, it.name, it.lat, it.lon) },
        fare = FareConfig(
            version = version,
            baseFareCentavos = fare.baseFareCentavos,
            baseDistanceKm = fare.baseDistanceKm,
            perKmCentavos = fare.perKmFareCentavos,
            roundingStepCentavos = fare.roundingStepCents,
        ),
        discountCategories = categories,
        fetchedAtMillis = fetchedAtMillis,
    )
}
