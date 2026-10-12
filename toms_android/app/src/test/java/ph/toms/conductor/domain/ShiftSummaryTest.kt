package ph.toms.conductor.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftSummaryTest {

    private fun trip(
        id: String,
        fare: Int,
        passengers: List<LineQuote> = listOf(LineQuote(null, 1, fare, fare, 0)),
        computed: Int = fare,
        override: String? = null,
        discount: Int = 0,
        createdAt: Long = 1_000,
    ) = Trip(
        id = id, cardUuid = "uuid-$id", nfcUid = "04AA$id", boardingStopId = "a",
        declaredDestinationStopId = "b", actualDestinationStopId = null,
        discountCategoryId = null, computedFareCentavos = computed, fareCentavos = fare,
        discountCentavos = discount, fareVersion = 1, overrideReason = override,
        createdAtMillis = createdAt, passengers = passengers,
    )

    private fun paid(id: String, shift: String?) = TripEventRecord.fromState(id, shift, CardState.ASSIGNED_PAID)
    private fun state(id: String, shift: String?, s: CardState) = TripEventRecord.fromState(id, shift, s)
    private fun writtenOff(id: String, shift: String?) = listOf(TripEventRecord(id, shift, TripFact.WRITTEN_OFF))

    private fun summary(
        shift: String,
        trips: List<ShiftTrip>,
        events: List<TripEventRecord> = emptyList(),
        states: Map<String, CardState> = emptyMap(),
    ) = ShiftSummary.of(shift, trips, events, states)

    private fun mine(vararg t: Trip, shift: String = "s1") = t.map { ShiftTrip(it, shift) }

    // 3
    @Test fun `an empty shift is all zeros`() {
        val s = summary("s1", emptyList())
        assertEquals(0, s.tripCount)
        assertEquals(0, s.passengers)
        assertEquals(0L, s.collectedCashCentavos)
        assertEquals(0L, s.faresChargedCentavos)
        assertTrue(s.byType.isEmpty())
        assertTrue(s.reconciles)
    }

    // 4
    @Test fun `one paid trip is collected`() {
        val t = trip("1", 1500)
        val s = summary("s1", mine(t), paid("1", "s1"))
        assertEquals(1500L, s.collectedCashCentavos)
        assertEquals(1500L, s.faresChargedCentavos)
        assertEquals(0L, s.outstandingCentavos)
        assertTrue(s.reconciles)
    }

    @Test fun `a group counts every person and splits by passenger type`() {
        val lines = listOf(LineQuote(null, 2, 1500, 3000, 0), LineQuote("student", 1, 1200, 1200, 300))
        val t = trip("1", 4200, passengers = lines, computed = 4200, discount = 300)
        val s = summary("s1", mine(t), paid("1", "s1"))
        assertEquals(1, s.tripCount)
        assertEquals(3, s.passengers)
        assertEquals(300L, s.discountCentavos)
        assertEquals(listOf(TypeLine(null, 2, 3000L), TypeLine("student", 1, 1200L)), s.byType)
    }

    // 5
    @Test fun `an unpaid open trip is outstanding and not collected`() {
        val t = trip("1", 1500)
        val s = summary("s1", mine(t), states = mapOf("1" to CardState.ASSIGNED_UNPAID))
        assertEquals(0L, s.collectedCashCentavos)
        assertEquals(1500L, s.outstandingCentavos)
        assertEquals(listOf("1"), s.outstandingTripIds)
        assertTrue(s.reconciles)
    }

    // 6
    @Test fun `a paid trip stays collected after the card is returned and freed`() {
        val t = trip("1", 1500)
        val events = paid("1", "s1") +
            state("1", "s1", CardState.RETURNED) + state("1", "s1", CardState.AVAILABLE)
        val s = summary("s1", mine(t), events)
        assertEquals(1500L, s.collectedCashCentavos)
        assertEquals(0L, s.outstandingCentavos)
        assertEquals(0L, s.unknownCentavos)
    }

    // 7
    @Test fun `a written off and released trip is written off, not collected and not outstanding`() {
        val t = trip("1", 1500)
        val events = writtenOff("1", "s1") + state("1", "s1", CardState.AVAILABLE)
        val s = summary("s1", mine(t), events)
        assertEquals(0L, s.collectedCashCentavos)
        assertEquals(1500L, s.writtenOffCentavos)
        assertEquals(0L, s.outstandingCentavos)
        assertEquals(listOf("1"), s.writtenOffTripIds)
        assertTrue(s.reconciles)
    }

    // 8
    @Test fun `a carried in trip paid here counts in this shift's cash once and not in its fares`() {
        val t = trip("1", 1500)
        val trips = listOf(ShiftTrip(t, "A"))
        val events = paid("1", "B")

        val a = summary("A", trips, events)
        val b = summary("B", trips, events)

        // Shift A charged it and it was still open when A ended.
        assertEquals(1500L, a.faresChargedCentavos)
        assertEquals(1500L, a.outstandingCentavos)
        assertEquals(0L, a.collectedCashCentavos)
        assertTrue(a.reconciles)

        // Shift B did not charge it, but the cash is B's.
        assertEquals(0L, b.faresChargedCentavos)
        assertEquals(0L, b.outstandingCentavos)
        assertEquals(0L, b.collectedOwnCentavos)
        assertEquals(1500L, b.collectedFromCarriedInCentavos)
        assertEquals(1500L, b.collectedCashCentavos)
        assertEquals(1, b.carriedIn.paidHereCount)
        assertTrue(b.reconciles)

        // Over both shifts the fare is counted once.
        assertEquals(1500L, a.collectedCashCentavos + b.collectedCashCentavos)
    }

    // 9
    @Test fun `a carried in trip still unpaid is information only and in no total`() {
        val t = trip("1", 1500)
        val b = summary("B", listOf(ShiftTrip(t, "A")), states = mapOf("1" to CardState.ASSIGNED_UNPAID))
        assertEquals(1, b.carriedIn.stillUnpaidCount)
        assertEquals(1500L, b.carriedIn.stillUnpaidCentavos)
        assertEquals(0L, b.faresChargedCentavos)
        assertEquals(0L, b.outstandingCentavos)
        assertEquals(0L, b.collectedCashCentavos)
        assertTrue(b.reconciles)
    }

    // 10
    @Test fun `a waived fare and an increased fare are counted as overrides with the net difference`() {
        val waived = trip("1", fare = 0, computed = 1500, override = "Dispatcher waived")
        val more = trip("2", fare = 2000, computed = 1500, override = "Extra bag")
        val plain = trip("3", fare = 1500)
        val events = paid("1", "s1") + paid("2", "s1") + paid("3", "s1")
        val s = summary("s1", mine(waived, more, plain), events)
        assertEquals(2, s.overrideCount)
        assertEquals(-1500L + 500L, s.overrideNetCentavos)
        assertEquals(3500L, s.collectedCashCentavos)
    }

    // 11
    @Test fun `a trip with no recorded facts and no known state is unknown and flagged`() {
        val t = trip("1", 1500)
        val s = summary("s1", mine(t))
        assertEquals(1500L, s.unknownCentavos)
        assertEquals(listOf("1"), s.unknownTripIds)
        assertEquals(0L, s.outstandingCentavos)
        assertTrue(s.reconciles)
    }

    @Test fun `an unpaid return whose alarm was cleared is flagged as unknown, not hidden`() {
        val t = trip("1", 1500)
        val events = state("1", "s1", CardState.RETURNED_UNPAID) + state("1", "s1", CardState.AVAILABLE)
        val s = summary("s1", mine(t), events)
        assertEquals(1500L, s.unknownCentavos)
        assertEquals(0L, s.collectedCashCentavos)
    }

    // 12
    @Test fun `own trip reconciliation balances over many generated shifts`() {
        val rnd = Random(42)
        repeat(300) { round ->
            val trips = (1..rnd.nextInt(0, 12)).map { i -> trip("t$round-$i", rnd.nextInt(0, 5000)) }
            val events = ArrayList<TripEventRecord>()
            val states = HashMap<String, CardState>()
            for (t in trips) {
                when (rnd.nextInt(5)) {
                    0 -> events += paid(t.id, "s1")
                    1 -> { events += writtenOff(t.id, "s1"); events += state(t.id, "s1", CardState.AVAILABLE) }
                    2 -> states[t.id] = CardState.ASSIGNED_UNPAID
                    3 -> states[t.id] = CardState.RETURNED_UNPAID
                    else -> Unit
                }
            }
            val s = summary("s1", trips.map { ShiftTrip(it, "s1") }, events, states)
            assertTrue("round $round", s.reconciles)
            assertEquals(trips.sumOf { it.fareCentavos.toLong() }, s.faresChargedCentavos)
        }
    }

    // 13
    @Test fun `across any split of events every paid trip is in exactly one shift's cash`() {
        val rnd = Random(7)
        val shifts = listOf("A", "B", "C")
        repeat(200) { round ->
            val trips = (1..rnd.nextInt(1, 10)).map { i ->
                ShiftTrip(trip("t$round-$i", rnd.nextInt(1, 4000)), shifts.random(rnd))
            }
            val events = ArrayList<TripEventRecord>()
            var expectedCash = 0L
            for (st in trips) {
                if (rnd.nextBoolean()) {
                    // Paid in any shift from the one it was created in onwards.
                    val from = shifts.indexOf(st.shiftId)
                    events += paid(st.trip.id, shifts[rnd.nextInt(from, shifts.size)])
                    expectedCash += st.trip.fareCentavos
                }
            }
            val total = shifts.sumOf { summary(it, trips, events).collectedCashCentavos }
            assertEquals("round $round", expectedCash, total)
        }
    }

    // 14
    @Test fun `a phone clock that jumps does not move a payment between shifts`() {
        // Created "in the future" and "in the past" relative to each other: only the shift tag matters.
        val early = trip("1", 1000, createdAt = 9_000_000_000L)
        val late = trip("2", 2000, createdAt = 5L)
        val trips = listOf(ShiftTrip(early, "A"), ShiftTrip(late, "B"))
        val events = paid("1", "A") + paid("2", "B")
        assertEquals(1000L, summary("A", trips, events).collectedCashCentavos)
        assertEquals(2000L, summary("B", trips, events).collectedCashCentavos)
    }

    // 15
    @Test fun `large sums do not overflow an int`() {
        val big = (1..3).map { trip("b$it", fare = 2_000_000_000) }
        val events = big.flatMap { paid(it.id, "s1") }
        val s = summary("s1", big.map { ShiftTrip(it, "s1") }, events)
        assertEquals(6_000_000_000L, s.collectedCashCentavos)
        assertEquals(6_000_000_000L, s.faresChargedCentavos)
    }

    @Test fun `a full group of thirty over a long distance sums correctly`() {
        val lines = listOf(LineQuote(null, MAX_GROUP_SIZE, 98_000, 98_000 * MAX_GROUP_SIZE, 0))
        val t = trip("1", 98_000 * MAX_GROUP_SIZE, passengers = lines)
        val s = summary("s1", mine(t), paid("1", "s1"))
        assertEquals(MAX_GROUP_SIZE, s.passengers)
        assertEquals(98_000L * MAX_GROUP_SIZE, s.collectedCashCentavos)
    }

    // 16
    @Test fun `duration reads naturally and never goes negative`() {
        val min = 60_000L
        assertEquals("under 1 min", ShiftTime.formatDuration(0, 59_999))
        assertEquals("1 min", ShiftTime.formatDuration(0, min))
        assertEquals("59 min", ShiftTime.formatDuration(0, 59 * min))
        assertEquals("1 h 00 min", ShiftTime.formatDuration(0, 60 * min))
        assertEquals("1 h 05 min", ShiftTime.formatDuration(0, 65 * min))
        assertEquals("25 h 30 min", ShiftTime.formatDuration(0, (25 * 60 + 30) * min))
        assertEquals("under 1 min", ShiftTime.formatDuration(10 * min, 5 * min))
    }

    @Test fun `trips from before shifts existed belong to no shift`() {
        val old = ShiftTrip(trip("old", 900), shiftId = null)
        val s = summary("s1", listOf(old) + mine(trip("1", 1500)), paid("1", "s1") + paid("old", null))
        assertEquals(1, s.tripCount)
        assertEquals(1500L, s.collectedCashCentavos)
        assertEquals(0, s.carriedIn.paidHereCount)
    }
}
