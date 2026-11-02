package ph.toms.conductor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopMatcherTest {

    private val a = Stop("a", "A", 8.2280, 124.2450)
    private val b = Stop("b", "B", 8.2460, 124.2450) // about 2 km north of A
    private val stops = listOf(a, b)
    private val now = 100_000L

    private fun fix(lat: Double, lon: Double = 124.2450, acc: Float? = 10f, at: Long = now) = GeoFix(lat, lon, acc, at)

    @Test fun `picks the nearest stop within range`() {
        val m = StopMatcher.nearest(fix(8.2281), stops, now)
        assertNotNull(m)
        assertEquals("a", m!!.stop.id)
        assertTrue(m.distanceMeters < 50)
    }

    @Test fun `no match when every stop is farther than the limit`() {
        assertNull(StopMatcher.nearest(fix(8.2370), stops, now)) // roughly 1 km from both
    }

    @Test fun `limit is configurable`() {
        val m = StopMatcher.nearest(fix(8.2370), stops, now, maxMeters = 1500.0)
        assertNotNull(m)
    }

    // 0.001 degrees of latitude is about 111 m.
    private val wide = Stop("w", "Wide", 8.2280, 124.2450, radiusMeters = 400.0)
    private val tight = Stop("t", "Tight", 8.2460, 124.2450, radiusMeters = 30.0)

    @Test fun `each stop uses its own radius`() {
        val at300m = fix(8.2280 + 0.0027) // about 300 m north of the wide stop
        assertEquals("w", StopMatcher.nearest(at300m, listOf(wide, tight), now)!!.stop.id)
        assertNull("the default 100 m radius does not reach 300 m", StopMatcher.nearest(at300m, listOf(a, b), now))
    }

    @Test fun `a stop with a small radius is not matched from farther than that`() {
        val at60m = fix(8.2460 + 0.00055)
        assertNull(StopMatcher.nearest(at60m, listOf(tight), now))
        assertNotNull(StopMatcher.nearest(fix(8.2460 + 0.0001), listOf(tight), now))
    }

    @Test fun `a stop with no radius given uses 100 m`() {
        assertEquals(100.0, a.radiusMeters, 0.0)
        assertNotNull(StopMatcher.nearest(fix(8.2280 + 0.0007), listOf(a), now)) // about 78 m
        assertNull(StopMatcher.nearest(fix(8.2280 + 0.0012), listOf(a), now)) // about 133 m
    }

    @Test fun `an accuracy circle wider than the stop's own radius is not trusted for that stop`() {
        assertNull(StopMatcher.nearest(fix(8.2460 + 0.0001, acc = 50f), listOf(tight), now))
        assertNotNull(StopMatcher.nearest(fix(8.2280 + 0.0001, acc = 50f), listOf(wide), now))
    }

    @Test fun `one limit given by the caller overrides every stop's radius`() {
        val at60m = fix(8.2460 + 0.00055)
        assertNotNull(StopMatcher.nearest(at60m, listOf(tight), now, maxMeters = 200.0))
    }

    @Test fun `when two stops both contain the fix the nearer one wins`() {
        val near = Stop("n", "Near", 8.2280, 124.2450, 500.0)
        val far = Stop("f", "Far", 8.2300, 124.2450, 500.0)
        assertEquals("n", StopMatcher.nearest(fix(8.2282), listOf(far, near), now)!!.stop.id)
    }

    @Test fun `stale fix is not used to match`() {
        assertNull(StopMatcher.nearest(fix(8.2281, at = now - 31_000), stops, now))
        assertNotNull(StopMatcher.nearest(fix(8.2281, at = now - 29_000), stops, now))
    }

    @Test fun `fix with a poor accuracy radius is not trusted`() {
        assertNull(StopMatcher.nearest(fix(8.2281, acc = 400f), stops, now))
    }

    @Test fun `unknown accuracy is still allowed`() {
        assertNotNull(StopMatcher.nearest(fix(8.2281, acc = null), stops, now))
    }

    @Test fun `stale check`() {
        assertTrue(StopMatcher.isStale(fix(8.0, at = 0), now))
        assertFalse(StopMatcher.isStale(fix(8.0, at = now), now))
    }
}

class TripGpsTest {

    private val a = Stop("a", "A", 8.2280, 124.2450)
    private val b = Stop("b", "B", 8.2460, 124.2450)
    private val config = TomsConfig(
        version = 1,
        stops = listOf(a, b),
        fare = FareConfig(0, 1500, 4.0, 250, 100),
        discountCategories = emptyList(),
        approvedCards = ApprovedCards(mapOf("04AA" to "uuid-1")),
    )

    private fun create(fix: GeoFix?) = TripFactory.create(
        "t", "04AA", CardState.AVAILABLE, "a", "b", listOf(PassengerLine(null, 1)), null, config, nowMillis = 100_000, fix = fix,
    ) as TripResult.Created

    @Test fun `fresh fix is stored on the trip`() {
        val f = GeoFix(8.2281, 124.2450, 8f, 99_000)
        assertEquals(f, create(f).trip.gps)
    }

    @Test fun `stale fix is not recorded`() {
        assertNull(create(GeoFix(8.2281, 124.2450, 8f, 1_000)).trip.gps)
    }

    @Test fun `no fix leaves gps empty and the trip still succeeds`() {
        assertNull(create(null).trip.gps)
    }
}
