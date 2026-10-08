package ph.toms.conductor.domain

/** A position fix. [accuracyMeters] is the receiver's 68% horizontal radius; null when unknown. */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float?,
    val fixAtMillis: Long,
)

data class StopMatch(val stop: Stop, val distanceMeters: Double)

object StopMatcher {

    const val DEFAULT_MAX_METERS = 300.0
    const val DEFAULT_MAX_AGE_MILLIS = 30_000L

    /** True when the fix is too old to say where the vehicle is now. */
    fun isStale(fix: GeoFix, nowMillis: Long, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) =
        nowMillis - fix.fixAtMillis > maxAgeMillis

    /**
     * Nearest stop within [maxMeters], or null. A poor fix widens what "near" can mean, so a fix
     * whose accuracy radius is larger than [maxMeters] is not trusted to pick a stop at all.
     */
    fun nearest(
        fix: GeoFix,
        stops: List<Stop>,
        nowMillis: Long,
        maxMeters: Double = DEFAULT_MAX_METERS,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): StopMatch? {
        if (isStale(fix, nowMillis, maxAgeMillis)) return null
        if (fix.accuracyMeters != null && fix.accuracyMeters > maxMeters) return null
        return stops
            .map { StopMatch(it, haversineMeters(fix.lat, fix.lon, it.lat, it.lon)) }
            .filter { it.distanceMeters <= maxMeters }
            .minByOrNull { it.distanceMeters }
    }
}
