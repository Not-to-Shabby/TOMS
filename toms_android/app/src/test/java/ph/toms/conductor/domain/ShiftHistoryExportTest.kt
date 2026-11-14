package ph.toms.conductor.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftHistoryExportTest {

    private fun trip(id: String, fare: Int, uid: String, actualStop: String? = null) = Trip(
        id = id, cardUuid = "card-$uid", nfcUid = uid, boardingStopId = "s1",
        declaredDestinationStopId = "s2", actualDestinationStopId = actualStop, discountCategoryId = null,
        computedFareCentavos = fare, fareCentavos = fare, discountCentavos = 0, fareVersion = 1,
        overrideReason = null, createdAtMillis = 1_000L,
    )

    private fun summaryOf(shiftId: String, trips: List<Trip>, events: List<TripEventRecord>): ShiftSummary =
        ShiftSummary.of(shiftId, trips.map { ShiftTrip(it, shiftId) }, events, emptyMap())

    private fun endedShift(endedAt: Long = 3_600_000L) =
        Shift("shift-1", "juan", "v1", "r1", startedAtMillis = 1_000L, endedAtMillis = endedAt, endedBy = "juan")

    @Test
    fun `the export carries the shift header the frozen summary and the trip lines`() {
        val shift = endedShift()
        val paid = trip("t1", 1500, "UID1")
        val off = trip("t2", 800, "UID2")
        val trips = listOf(paid, off)
        val events = listOf(
            TripEventRecord("t1", "shift-1", TripFact.PAID),
            TripEventRecord("t2", "shift-1", TripFact.WRITTEN_OFF),
        )
        val summary = summaryOf("shift-1", trips, events)

        val json = Json.parseToJsonElement(ShiftHistoryExport.build(shift, summary, trips)).jsonObject

        assertEquals("toms-shift-export", json["kind"]!!.jsonPrimitive.content)
        assertEquals(1, json["version"]!!.jsonPrimitive.int)

        val header = json["shift"]!!.jsonObject
        assertEquals("juan", header["conductor"]!!.jsonPrimitive.content)
        assertEquals("v1", header["vehicleId"]!!.jsonPrimitive.content)
        assertEquals("r1", header["routeId"]!!.jsonPrimitive.content)
        assertEquals("juan", header["endedBy"]!!.jsonPrimitive.content)
        assertEquals("the exact end time the shift was closed with", 3_600_000L, header["endedAtMillis"]!!.jsonPrimitive.long)

        // The frozen summary rides along exactly as End Shift wrote it.
        val exportedSummary = json["summary"]!!.jsonObject
        assertEquals(
            summary.writtenOffTripIds,
            Json.decodeFromJsonElement<List<String>>(exportedSummary["writtenOffTripIds"]!!),
        )
        // Only constructor properties serialize; collected cash is derived and so is not in the JSON.
        assertEquals(summary.faresChargedCentavos, exportedSummary["faresChargedCentavos"]!!.jsonPrimitive.content.toLong())

        val lines = json["trips"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, lines.size)
        val offLine = lines.single { it["tripId"]!!.jsonPrimitive.content == "t2" }
        assertEquals("UID2", offLine["cardUid"]!!.jsonPrimitive.content)
        assertEquals("written_off", offLine["settlement"]!!.jsonPrimitive.content)
        assertEquals("collected", lines.single { it["tripId"]!!.jsonPrimitive.content == "t1" }["settlement"]!!.jsonPrimitive.content)

        // Late payments were considered and deferred (see documentation/v2_later.md); nothing records them.
        assertFalse("no recoveries are in the export", json.containsKey("recoveries"))
    }

    @Test
    fun `the export refuses a shift that is still open`() {
        val trips = listOf(trip("t1", 1500, "UID1"))
        val events = listOf(TripEventRecord("t1", "shift-1", TripFact.PAID))
        val summary = summaryOf("shift-1", trips, events)
        val open = Shift("shift-1", "juan", "v1", "r1", startedAtMillis = 1_000L, endedAtMillis = null, endedBy = null)

        try {
            ShiftHistoryExport.build(open, summary, trips)
            throw AssertionError("an open shift must not export")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("ended"))
        }
    }

    @Test
    fun `the export holds only the card uids that are part of trip lines`() {
        val shift = endedShift()
        val trips = listOf(trip("t1", 1500, "UID1"))
        val events = listOf(TripEventRecord("t1", "shift-1", TripFact.PAID))
        val summary = summaryOf("shift-1", trips, events)

        val text = ShiftHistoryExport.build(shift, summary, trips)

        // The card UID of the trip line is there; a UID from anywhere else (the approved card list,
        // the cards table) never is, because the builder is never given one.
        assertTrue(text.contains("UID1"))
        assertFalse("a card uid from no trip line must not appear", text.contains("GHOSTUID"))
        // Secrets are not passed in at all, so they cannot be in the output; their key names neither.
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("databaseKey"))
        assertFalse(text.contains("dbKey"))
        assertFalse(text.contains("secret", ignoreCase = true))
    }

    @Test
    fun `a trip exports where the passenger went when the conductor recorded it`() {
        val shift = endedShift()
        val trips = listOf(trip("t1", 1500, "UID1", actualStop = "s9"))
        val events = listOf(TripEventRecord("t1", "shift-1", TripFact.PAID))
        val summary = summaryOf("shift-1", trips, events)

        val json = Json.parseToJsonElement(ShiftHistoryExport.build(shift, summary, trips)).jsonObject
        val line = json["trips"]!!.jsonArray.single().jsonObject
        assertEquals("s9", line["destinationStopId"]!!.jsonPrimitive.content)
    }
}
