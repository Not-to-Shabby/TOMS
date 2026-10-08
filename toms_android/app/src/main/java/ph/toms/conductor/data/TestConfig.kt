package ph.toms.conductor.data

import ph.toms.conductor.domain.ApprovedCards
import ph.toms.conductor.domain.DiscountCategory
import ph.toms.conductor.domain.FareConfig
import ph.toms.conductor.domain.Stop
import ph.toms.conductor.domain.TomsConfig

/**
 * Local seed used until the server config and card registry exist (Phase 1.4/1.6).
 * Stop coordinates, fare numbers and discount rates are placeholders for on-device testing only;
 * they are not verified fares. The card UIDs are the four cards read on the Tecno Pova 4.
 */
object TestConfig {
    val config = TomsConfig(
        version = 0,
        stops = listOf(
            Stop("s1", "Stop 1 (origin)", 8.2280, 124.2450),
            Stop("s2", "Stop 2 (~2 km)", 8.2460, 124.2450),
            Stop("s3", "Stop 3 (~5 km)", 8.2730, 124.2450),
            Stop("s4", "Stop 4 (~9 km)", 8.3090, 124.2450),
        ),
        fare = FareConfig(
            version = 0,
            baseFareCentavos = 1500,
            baseDistanceKm = 4.0,
            perKmCentavos = 250,
            roundingStepCentavos = 100,
        ),
        discountCategories = listOf(
            DiscountCategory("student", "Student", 20),
            DiscountCategory("pwd", "PWD", 20),
            DiscountCategory("senior", "Senior", 20),
        ),
        approvedCards = ApprovedCards(
            mapOf(
                "6F:F1:AD:39" to "00000000-0000-4000-8000-00000000f1a1",
                "36:AF:5E:75" to "00000000-0000-4000-8000-00000000f1a2",
                "56:5E:A4:75" to "00000000-0000-4000-8000-00000000f1a3",
                "14:B8:49:8A" to "00000000-0000-4000-8000-00000000f1a4",
            ),
        ),
    )
}
