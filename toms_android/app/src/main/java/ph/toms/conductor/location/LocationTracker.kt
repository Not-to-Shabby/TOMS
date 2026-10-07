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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ph.toms.conductor.domain.GeoFix

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
}

private fun Location.toFix() = GeoFix(
    lat = latitude,
    lon = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    fixAtMillis = time,
)
