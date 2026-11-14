package ph.toms.conductor.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.domain.StopMatcher

/** How long a tap-time fresh fix may take to arrive before the trip is recorded without GPS. */
const val TAP_FIX_TIMEOUT_MILLIS = 3_000L

/**
 * GNSS fixes from the platform LocationManager only, so it works without Google Play services.
 * The caller must hold ACCESS_FINE_LOCATION and collect only while the screen is in use, because
 * continuous GNSS is a large share of the battery budget.
 */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun gnssEnabled(): Boolean = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun fixes(minIntervalMillis: Long = 2_000, minDistanceMeters: Float = 5f): Flow<GeoFix> = callbackFlow {
        val lm = manager
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            close()
            return@callbackFlow
        }
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { trySend(it.toFix()) }
        val listener = LocationListener { location -> trySend(location.toFix()) }
        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, minIntervalMillis, minDistanceMeters, listener, Looper.getMainLooper(),
        )
        awaitClose { lm.removeUpdates(listener) }
    }

    /**
     * One fresh fix at tap time, for when the stream has gone quiet. While the vehicle waits at a stop
     * it does not move, so no fix passes the [minDistanceMeters] trigger and the last fix ages past the
     * staleness allowance — a trip made then would be recorded without GPS. If the vehicle *had* moved,
     * a fix would already have arrived and been fresh, so reaching here means the receiver is the thing
     * that went quiet, not the vehicle that moved away.
     *
     * Returns the cached fix when it is still fresh enough, without waking the receiver. Otherwise
     * waits up to [timeoutMillis] for a single fix, and null on timeout or with GNSS off — the caller
     * then falls back to the stale fix, which [ph.toms.conductor.domain.TripFactory] still drops.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshFix(
        staleAfterMillis: Long = StopMatcher.DEFAULT_MAX_AGE_MILLIS,
        timeoutMillis: Long = TAP_FIX_TIMEOUT_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ): GeoFix? {
        val lm = manager
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return null
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last ->
            if (nowMillis - last.time <= staleAfterMillis) return last.toFix()
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                val listener = LocationListener { location -> if (cont.isActive) cont.resume(location.toFix()) }
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                // The timeout (or any cancellation) must not leave the one-shot listener registered.
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
    }
}

private fun Location.toFix() = GeoFix(
    lat = latitude,
    lon = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    fixAtMillis = time,
)
