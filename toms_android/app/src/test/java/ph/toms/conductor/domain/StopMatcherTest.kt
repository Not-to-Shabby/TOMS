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
        "t", "04AA", CardState.AVAILABLE, "a", "b", null, null, config, nowMillis = 100_000, fix = fix,
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
