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

    const val DEFAULT_MAX_AGE_MILLIS = 30_000L

    /** True when the fix is too old to say where the vehicle is now. */
    fun isStale(fix: GeoFix, nowMillis: Long, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) =
        nowMillis - fix.fixAtMillis > maxAgeMillis

    /**
     * Nearest stop whose own radius ([Stop.radiusMeters]) contains the fix, or null. Pass [maxMeters] to use one
     * limit for every stop instead. A poor fix widens what "near" can mean, so a fix whose accuracy circle is wider
     * than a stop's limit is not trusted to say it is at that stop.
     */
    fun nearest(
        fix: GeoFix,
        stops: List<Stop>,
        nowMillis: Long,
        maxMeters: Double? = null,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): StopMatch? {
        if (isStale(fix, nowMillis, maxAgeMillis)) return null
        return stops
            .mapNotNull { stop ->
                val limit = maxMeters ?: stop.radiusMeters
                if (fix.accuracyMeters != null && fix.accuracyMeters > limit) return@mapNotNull null
                val distance = haversineMeters(fix.lat, fix.lon, stop.lat, stop.lon)
                if (distance <= limit) StopMatch(stop, distance) else null
            }
            .minByOrNull { it.distanceMeters }
    }
}
