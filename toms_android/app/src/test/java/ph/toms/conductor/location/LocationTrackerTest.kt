package ph.toms.conductor.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationTrackerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tracker = LocationTracker(context)
    private val shadow
        get() = Shadows.shadowOf(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)

    /** A fix from the GNSS provider at [atMillis], like the platform would hand us. */
    private fun gnssFix(atMillis: Long, lat: Double = 8.0) = Location("gps").apply {
        latitude = lat
        longitude = 124.0
        accuracy = 5f
        time = atMillis
    }

    @Before
    fun setUp() {
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @Test
    fun `a cached fix that is still fresh is handed back without waking the receiver`() = runBlocking {
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gnssFix(atMillis = 1_000))
        val got = tracker.freshFix(staleAfterMillis = 30_000, timeoutMillis = 200, nowMillis = 10_000)
        assertEquals(8.0, got!!.lat, 0.0)
        assertEquals(1_000, got.fixAtMillis)
    }

    @Test
    fun `a stale cache is replaced by one fresh fix when the receiver answers in time`() = runTest {
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gnssFix(atMillis = 1_000))
        var got: ph.toms.conductor.domain.GeoFix? = null
        val job = launch { got = tracker.freshFix(staleAfterMillis = 30_000, timeoutMillis = 2_000, nowMillis = 40_000) }
        // Let freshFix run as far as its one-shot request, then answer it.
        testScheduler.runCurrent()
        shadow.simulateLocation(gnssFix(atMillis = 39_000, lat = 8.5))
        ShadowLooper.idleMainLooper()
        testScheduler.advanceUntilIdle()
        job.join()

        assertEquals(8.5, got!!.lat, 0.0)
        assertEquals(39_000, got!!.fixAtMillis)
    }

    @Test
    fun `a silent receiver returns null after the timeout`() = runBlocking {
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gnssFix(atMillis = 1_000))
        val got = tracker.freshFix(staleAfterMillis = 30_000, timeoutMillis = 200, nowMillis = 40_000)
        assertNull(got)
    }

    @Test
    fun `gnss switched off returns null without waiting`() = runBlocking {
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gnssFix(atMillis = 1_000))
        val got = tracker.freshFix(staleAfterMillis = 30_000, timeoutMillis = 200, nowMillis = 40_000)
        assertNull(got)
    }

    @Test
    fun `a fresh cache beats the timeout even when it is close to the limit`() = runBlocking {
        // Exactly at the allowance: 30_000 old is still fresh (the check is strictly greater).
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gnssFix(atMillis = 10_000))
        val got = tracker.freshFix(staleAfterMillis = 30_000, timeoutMillis = 200, nowMillis = 40_000)
        assertTrue(got != null)
    }
}
