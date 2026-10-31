package ph.toms.conductor.data

import ph.toms.conductor.sync.ConfigFetch
import ph.toms.conductor.sync.ConfigSync

/** A response in the shape `GET /api/device/config` sends. Override a piece to build the case under test. */
fun serverConfigResponse(
    version: Int = 4242,
    routeName: String = "Tibanga - Pala-o",
    stops: List<StopDto> = listOf(
        StopDto("11", "Tibanga", 8.2280, 124.2450, 120),
        StopDto("12", "Mid", 8.2460, 124.2450, 120),
        StopDto("13", "Pala-o", 8.2730, 124.2450, 120),
    ),
    baseFareCentavos: Int = 1800,
    baseDistanceKm: Double = 4.0,
    perKmFareCentavos: Int = 300,
    roundingStepCents: Int = 100,
    discounts: List<DiscountDto> = listOf(DiscountDto("pwd", "PWD", 20), DiscountDto("student", "Student", 20)),
) = DeviceConfigResponse(
    version = version,
    route = RouteDto(7, routeName),
    stops = stops,
    fare = FareDto(9, baseFareCentavos, baseDistanceKm, perKmFareCentavos, roundingStepCents, discounts),
)

/** Answers what a test tells it to. Starts as if the phone had no connection. */
class FakeConfigSync(var next: ConfigFetch = ConfigFetch.Failed("offline")) : ConfigSync {
    var calls = 0
    override suspend fun fetchConfig(): ConfigFetch {
        calls++
        return next
    }
}
