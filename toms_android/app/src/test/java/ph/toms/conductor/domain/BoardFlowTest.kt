package ph.toms.conductor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardFlowTest {

    private val a = Stop("a", "A", 8.00, 124.0)
    private val c = Stop("c", "C", 8.10, 124.0) // ~11.1 km: 2800 regular, 2200 student
    private val config = TomsConfig(
        version = 1,
        stops = listOf(a, c),
        fare = FareConfig(5, 1200, 4.0, 200, 100),
        discountCategories = listOf(DiscountCategory("student", "Student", 20)),
        approvedCards = ApprovedCards(mapOf("04AA" to "uuid-1", "04BB" to "uuid-2")),
    )

    private val states = HashMap<String, CardState>()
    private val trips = HashMap<String, Trip>()
    private var ids = 0

    private fun tap(uid: String, tally: PassengerTally, dest: String = "c", now: Long = 1000) = BoardFlow.handleTap(
        nfcUid = uid, passengers = tally, boardingStopId = "a", destinationStopId = dest, config = config,
        stateOf = { states[it] ?: CardState.AVAILABLE }, tripFor = { trips[it] }, newTripId = { "t${++ids}" }, nowMillis = now,
    )

    private fun commit(r: TapResult) {
        if (r is TapResult.Started) {
            states[r.trip.nfcUid] = r.cardState
            trips[r.trip.nfcUid] = r.trip
        }
    }

    @Test fun `a free card starts a trip for the whole group on screen`() {
        val r = tap("04aa", PassengerTally().add(null).add(null).add("student")) as TapResult.Started
        assertEquals(3, r.trip.passengerCount)
        assertEquals(2 * 2800 + 2200, r.trip.fareCentavos)
        assertEquals(CardState.ASSIGNED_UNPAID, r.cardState)
    }

    @Test fun `tapping the same card again shows its trip and creates nothing`() {
        commit(tap("04aa", PassengerTally.starting()))
        val again = tap("04aa", PassengerTally().add("student").add("student"))
        assertTrue(again is TapResult.Existing)
        val existing = again as TapResult.Existing
        assertEquals(1, existing.trip.passengerCount) // the first group, not the one now on screen
        assertEquals(CardState.ASSIGNED_UNPAID, existing.cardState)
        assertEquals(1, trips.size)
    }

    @Test fun `the existing trip shows whether it was paid`() {
        commit(tap("04aa", PassengerTally.starting()))
        states["04AA"] = CardState.ASSIGNED_PAID
        assertEquals(CardState.ASSIGNED_PAID, (tap("04aa", PassengerTally.starting()) as TapResult.Existing).cardState)
    }

    @Test fun `two different cards are two separate trips`() {
        commit(tap("04aa", PassengerTally.starting()))
        val second = tap("04bb", PassengerTally().add(null).add(null))
        assertTrue(second is TapResult.Started)
        assertEquals(2, (second as TapResult.Started).trip.passengerCount)
    }

    @Test fun `an unknown card is named, and nothing starts`() {
        assertEquals(TapResult.UnknownCard("DEADBEEF"), tap("de:ad:be:ef", PassengerTally.starting()))
    }

    @Test fun `an empty group is invalid, not a free trip`() {
        assertEquals(TapResult.Invalid(TripRejection.NO_PASSENGERS), tap("04aa", PassengerTally()))
    }

    @Test fun `an unknown destination is invalid`() {
        assertEquals(TapResult.Invalid(TripRejection.UNKNOWN_STOP), tap("04aa", PassengerTally.starting(), dest = "nowhere"))
    }

    @Test fun `a lost or alarmed card needs the conductor`() {
        states["04AA"] = CardState.LOST
        assertTrue(tap("04aa", PassengerTally.starting()) is TapResult.NeedsAttention)
        states["04AA"] = CardState.RETURNED_UNPAID
        assertTrue(tap("04aa", PassengerTally.starting()) is TapResult.NeedsAttention)
    }

    @Test fun `a returned card does not start a trip until it is released`() {
        commit(tap("04aa", PassengerTally.starting()))
        states["04AA"] = CardState.RETURNED
        assertTrue(tap("04aa", PassengerTally.starting()) is TapResult.Existing)
        states["04AA"] = CardState.AVAILABLE
        assertTrue(tap("04aa", PassengerTally.starting()) is TapResult.Started)
    }

    @Test fun `the preview total matches the trip the tap then creates`() {
        val tally = PassengerTally().add(null).add("student").add("student")
        val preview = BoardFlow.previewTotal(tally, "a", "c", config)!!
        val started = tap("04aa", tally) as TapResult.Started
        assertEquals(preview.totalCentavos, started.trip.fareCentavos)
        assertEquals(preview.passengerCount, started.trip.passengerCount)
    }

    @Test fun `no preview without passengers or a known stop`() {
        assertNull(BoardFlow.previewTotal(PassengerTally(), "a", "c", config))
        assertNull(BoardFlow.previewTotal(PassengerTally.starting(), "a", "zzz", config))
        assertNull(BoardFlow.previewTotal(PassengerTally.starting(), "zzz", "c", config))
        assertNotNull(BoardFlow.previewTotal(PassengerTally.starting(), "a", "c", config))
    }
}

class CollectListTest {

    private fun trip(id: String, at: Long, fare: Int = 1000) = Trip(
        id = id, cardUuid = "u$id", nfcUid = "UID$id", boardingStopId = "a", declaredDestinationStopId = "c",
        actualDestinationStopId = null, discountCategoryId = null, computedFareCentavos = fare, fareCentavos = fare,
        discountCentavos = 0, fareVersion = 0, overrideReason = null, createdAtMillis = at,
    )

    private fun open(id: String, at: Long, state: CardState, fare: Int = 1000) = OpenTrip(trip(id, at, fare), state)

    @Test fun `alarms first, then unpaid, then paid, oldest first within each`() {
        val list = CollectList.of(
            listOf(
                open("paid-new", 50, CardState.ASSIGNED_PAID),
                open("unpaid-new", 40, CardState.ASSIGNED_UNPAID),
                open("alarm", 99, CardState.RETURNED_UNPAID),
                open("unpaid-old", 10, CardState.ASSIGNED_UNPAID),
                open("paid-old", 5, CardState.ASSIGNED_PAID),
            ),
        )
        assertEquals(listOf("alarm", "unpaid-old", "unpaid-new", "paid-old", "paid-new"), list.map { it.trip.id })
    }

    @Test fun `returned, released and lost cards are not listed`() {
        val list = CollectList.of(
            listOf(
                open("a", 1, CardState.RETURNED),
                open("b", 2, CardState.AVAILABLE),
                open("c", 3, CardState.LOST),
                open("d", 4, CardState.ASSIGNED_UNPAID),
            ),
        )
        assertEquals(listOf("d"), list.map { it.trip.id })
    }

    @Test fun `unpaid count includes alarms and owed counts only unpaid fares`() {
        val trips = listOf(
            open("a", 1, CardState.ASSIGNED_UNPAID, 4500),
            open("b", 2, CardState.ASSIGNED_UNPAID, 1500),
            open("c", 3, CardState.RETURNED_UNPAID, 9900),
            open("d", 4, CardState.ASSIGNED_PAID, 700),
        )
        assertEquals(3, CollectList.unpaidCount(trips))
        assertEquals(6000L, CollectList.owedCentavos(trips))
    }

    @Test fun `an empty list is empty`() {
        assertEquals(emptyList<OpenTrip>(), CollectList.of(emptyList()))
        assertEquals(0, CollectList.unpaidCount(emptyList()))
        assertEquals(0L, CollectList.owedCentavos(emptyList()))
    }
}
