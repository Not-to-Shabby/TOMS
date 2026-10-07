package ph.toms.conductor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LEGACY_OLD_APP parity: these cases only confirm the Kotlin port reproduces what
 * toms_mobile/lib/services/session_service.dart outputs (ceil per started km beyond 4 km,
 * flat 20% for non-regular passengers, nearest whole peso). They are NOT statements that these
 * are the correct fares. Real fares and discount rates come from server settings (Phase 1.6) and
 * the government source cited in Chapter IV 4.9.
 */
class LegacyOldAppParityTest {

    private val legacy = FareConfig(
        version = 0,
        baseFareCentavos = 1500,
        baseDistanceKm = 4.0,
        perKmCentavos = 250,
        roundingStepCentavos = 100,
    )
    private val legacyDiscount = DiscountCategory("legacy20", "Legacy flat 20%", 20)

    private fun regular(meters: Double) = FareCalculator.quote(meters, null, legacy).fareCentavos
    private fun discounted(meters: Double) = FareCalculator.quote(meters, legacyDiscount, legacy).fareCentavos

    @Test fun `LEGACY_OLD_APP within base distance`() {
        assertEquals(1500, regular(3000.0))
        assertEquals(1200, discounted(3000.0))
    }

    @Test fun `LEGACY_OLD_APP exactly base distance adds nothing`() {
        assertEquals(1500, regular(4000.0))
    }

    @Test fun `LEGACY_OLD_APP any fraction beyond 4km charges a full extra km`() {
        assertEquals(1800, regular(4001.0)) // 17.50 rounds half up to 18
        assertEquals(1400, discounted(4001.0))
        assertEquals(1800, regular(5000.0))
    }

    @Test fun `LEGACY_OLD_APP ceil per started km`() {
        assertEquals(2300, regular(6500.0)) // 22.50 -> 23
        assertEquals(1800, discounted(6500.0))
        assertEquals(3000, regular(10000.0))
        assertEquals(2400, discounted(10000.0))
    }

    @Test fun `LEGACY_OLD_APP rounding applies to regular fares too`() {
        val q = FareCalculator.quote(4001.0, null, legacy)
        assertEquals(1750, q.baseCentavos)
        assertEquals(1800, q.fareCentavos)
    }
}

class FareCalculatorTest {

    private fun config(
        base: Int = 1300,
        baseKm: Double = 5.0,
        perKm: Int = 200,
        step: Int = 50,
        version: Int = 7,
    ) = FareConfig(version, base, baseKm, perKm, step)

    @Test fun `base distance and rates come from config`() {
        val c = config()
        assertEquals(1300, FareCalculator.quote(5000.0, null, c).fareCentavos)
        assertEquals(1500, FareCalculator.quote(5001.0, null, c).fareCentavos)
        assertEquals(1700, FareCalculator.quote(6200.0, null, c).fareCentavos)
    }

    @Test fun `discount percent comes from the category not a constant`() {
        val c = config(step = 1)
        val pwd = DiscountCategory("pwd", "PWD", 30)
        val student = DiscountCategory("student", "Student", 10)
        assertEquals(910, FareCalculator.quote(1000.0, pwd, c).fareCentavos)
        assertEquals(1170, FareCalculator.quote(1000.0, student, c).fareCentavos)
        assertEquals(390, FareCalculator.quote(1000.0, pwd, c).discountCentavos)
    }

    @Test fun `rounding step is honoured`() {
        val c = config(base = 1325, step = 25)
        assertEquals(1325, FareCalculator.quote(100.0, null, c).fareCentavos)
        val c50 = config(base = 1325, step = 50)
        assertEquals(1350, FareCalculator.quote(100.0, null, c50).fareCentavos)
    }

    @Test fun `rounding after discount sends exact half up`() {
        val c = config(base = 1250, step = 100)
        assertEquals(1300, FareCalculator.quote(100.0, null, c).fareCentavos)
    }

    @Test fun `rounding a regular fare up is not reported as a discount`() {
        val c = config(base = 1750, baseKm = 4.0, step = 100)
        val q = FareCalculator.quote(100.0, null, c)
        assertEquals(1800, q.fareCentavos)
        assertEquals(0, q.discountCentavos)
    }

    @Test fun `quote records the fare version used`() {
        assertEquals(7, FareCalculator.quote(100.0, null, config()).fareVersion)
    }

    @Test fun `haversine one degree of latitude is about 111 km`() {
        val m = haversineMeters(8.0, 124.0, 9.0, 124.0)
        assertTrue(m in 111_000.0..111_400.0)
    }

    @Test fun `discount percent out of range is rejected`() {
        val failed = runCatching { DiscountCategory("x", "x", 101) }.isFailure
        assertTrue(failed)
    }
}
