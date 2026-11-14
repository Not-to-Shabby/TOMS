package ph.toms.conductor.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A late payment recorded after the shift, against a written-off trip. */
data class RecoveredFare(
    val tripId: String,
    val atMillis: Long,
    val amountCentavos: Int,
    val reason: String,
    val actingConductor: String,
)

/**
 * Builds the shareable JSON for one ended shift: the frozen summary plus the shift's trip lines.
 *
 * What may appear here is fixed by what [build] accepts: the shift header, the frozen summary, the
 * trips made during the shift (each carrying its own card UID, because it is part of that trip line),
 * and the late payments recorded against the shift. A device token, the database key, the approved
 * card list and any card UID that is not part of a trip line are never passed in, so they cannot be
 * in the export. There is deliberately no GPS and no card UUIDs: the export is the conductor's money
 * record, not a device log.
 */
object ShiftHistoryExport {

    const val KIND = "toms-shift-export"
    const val VERSION = 1

    @Serializable
    private data class Line(val category: String?, val passengers: Int, val fareCentavos: Long)

    /** Where the fare stands now, as the frozen summary recorded it. */
    @Serializable
    private data class TripLine(
        val tripId: String,
        val cardUid: String,
        val boardedAtMillis: Long,
        val boardingStopId: String,
        val destinationStopId: String,
        val passengers: List<Line>,
        val fareCentavos: Int,
        val discountCentavos: Int,
        val overrideReason: String? = null,
        val settlement: String,
    )

    @Serializable
    private data class Recovery(
        val tripId: String,
        val atMillis: Long,
        val amountCentavos: Int,
        val reason: String,
        val actingConductor: String,
    )

    @Serializable
    private data class ShiftHeader(
        val shiftId: String,
        val conductor: String,
        val vehicleId: String,
        val routeId: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val endedBy: String?,
    )

    @Serializable
    private data class Export(
        val kind: String,
        val version: Int,
        val shift: ShiftHeader,
        val summary: ShiftSummary,
        val trips: List<TripLine>,
        val recoveries: List<Recovery>,
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * The JSON for [shift]. [summary] must be the frozen summary the shift was ended with, and [trips]
     * the trips recorded during the shift, in boarding order.
     */
    fun build(shift: Shift, summary: ShiftSummary, trips: List<Trip>, recoveries: List<RecoveredFare>): String {
        val writtenOff = summary.writtenOffTripIds.toSet()
        val outstanding = summary.outstandingTripIds.toSet()
        val unknown = summary.unknownTripIds.toSet()
        val lines = trips.map { t ->
            TripLine(
                tripId = t.id,
                cardUid = t.nfcUid,
                boardedAtMillis = t.createdAtMillis,
                boardingStopId = t.boardingStopId,
                destinationStopId = t.actualDestinationStopId ?: t.declaredDestinationStopId,
                passengers = t.passengers.map { Line(it.categoryId, it.count, it.fareCentavos.toLong()) },
                fareCentavos = t.fareCentavos,
                discountCentavos = t.discountCentavos,
                overrideReason = t.overrideReason,
                settlement = when (t.id) {
                    in writtenOff -> "written_off"
                    in outstanding -> "outstanding"
                    in unknown -> "unknown"
                    else -> "collected"
                },
            )
        }
        return json.encodeToString(
            Export(
                kind = KIND,
                version = VERSION,
                shift = ShiftHeader(
                    shiftId = shift.id,
                    conductor = shift.conductor,
                    vehicleId = shift.vehicleId,
                    routeId = shift.routeId,
                    startedAtMillis = shift.startedAtMillis,
                    endedAtMillis = shift.endedAtMillis ?: 0,
                    endedBy = shift.endedBy,
                ),
                summary = summary,
                trips = lines,
                recoveries = recoveries.map {
                    Recovery(it.tripId, it.atMillis, it.amountCentavos, it.reason, it.actingConductor)
                },
            ),
        )
    }
}
