package ph.toms.conductor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.db.EventEntity
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.Trip

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShiftRepositoryTest {

    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private var now = 1_000_000L
    private var eventSeq = 0
    private var shiftSeq = 0
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(
            db, deviceId = "dev-1",
            newEventId = { "evt-${eventSeq++}" },
            newShiftId = { "shift-${shiftSeq++}" },
            clock = { now },
        )
    }

    @After fun tearDown() = db.close()

    private fun trip(id: String, fare: Int = 1500, uid: String = "UID$id") = Trip(
        id = id, cardUuid = "card-$uid", nfcUid = uid, boardingStopId = "s1",
        declaredDestinationStopId = "s2", actualDestinationStopId = null, discountCategoryId = null,
        computedFareCentavos = fare, fareCentavos = fare, discountCentavos = 0, fareVersion = 1,
        overrideReason = null, createdAtMillis = now,
    )

    private fun started(r: StartShiftResult) = (r as StartShiftResult.Started).shift
    private fun events() = runBlocking { db.events().all() }
    private fun types() = events().map { it.type }

    private suspend fun open(conductor: String = "juan", vehicle: String = "v1", route: String = "r1") =
        repo.startShift(conductor, vehicle, route)

    private suspend fun board(shiftId: String, t: Trip) = repo.recordTrip(t, CardState.ASSIGNED_UNPAID, now, shiftId)
    private suspend fun pay(shiftId: String?, t: Trip) =
        repo.recordCardState(t.nfcUid, t.cardUuid, t.id, CardState.ASSIGNED_PAID, now, shiftId)

    // 17
    @Test fun `starting a shift creates it and the same conductor vehicle and route resumes it`() = runBlocking {
        val first = started(open())
        assertEquals("juan", first.conductor)
        assertEquals(listOf(EventTypes.SHIFT_STARTED), types())

        val again = open() as StartShiftResult.Resumed
        assertEquals(first.id, again.shift.id)
        assertEquals("resuming must not write a second start", 1, events().size)
    }

    // 18
    @Test fun `a different conductor on the same vehicle and route is blocked and creates nothing`() = runBlocking {
        val first = started(open("juan"))
        val blocked = open("maria") as StartShiftResult.NeedsEnding
        assertEquals(first.id, blocked.open.id)
        assertEquals("the open shift is still the first one, still juan's", first.id, repo.openShift()!!.id)
        assertEquals("juan", repo.openShift()!!.conductor)
        assertEquals("nothing new written", 1, events().size)
    }

    // 19
    @Test fun `the conductor name is compared ignoring case and outer spaces`() = runBlocking {
        val first = started(open("juan"))
        val again = open("  Juan ") as StartShiftResult.Resumed
        assertEquals(first.id, again.shift.id)
    }

    // 20
    @Test fun `a different vehicle or route is blocked until the open shift is ended`() = runBlocking {
        val first = started(open())
        assertTrue(open(vehicle = "v2") is StartShiftResult.NeedsEnding)
        assertTrue(open(route = "r2") is StartShiftResult.NeedsEnding)

        assertNotNull(repo.endShift(first.id, endedBy = "juan"))
        val next = started(open(vehicle = "v2"))
        assertTrue(next.id != first.id)
        assertEquals("v2", next.vehicleId)
    }

    // 21
    @Test fun `trips and events from two shifts stay separate and untagged ones belong to none`() = runBlocking {
        val old = trip("old", 900)
        repo.recordTrip(old, CardState.ASSIGNED_UNPAID, now, shiftId = null)

        val a = started(open())
        val ta = trip("a", 1500)
        board(a.id, ta); pay(a.id, ta)
        repo.endShift(a.id, "juan")

        val b = started(open("maria"))
        val tb = trip("b", 2000)
        board(b.id, tb); pay(b.id, tb)

        val sa = repo.summaryFor(a.id)
        val sb = repo.summaryFor(b.id)
        assertEquals(1500L, sa.collectedCashCentavos)
        assertEquals(2000L, sb.collectedCashCentavos)
        assertEquals(1, sa.tripCount)
        assertEquals(1, sb.tripCount)
        assertEquals("the pre-shift trip is in no shift", 0, sb.carriedIn.stillUnpaidCount)
    }

    // 22
    @Test fun `write off and release writes the three events in order with full audit fields and no terminal return`() = runBlocking {
        val s = started(open("juan"))
        val t = trip("1", 1500)
        board(s.id, t)

        assertTrue(repo.writeOffAndRelease(s.id, t.id, actingConductor = "maria"))

        val forTrip = events().filter { it.tripId == t.id }
        assertEquals(
            listOf(EventTypes.TRIP_CREATED, EventTypes.FARE_WRITTEN_OFF, EventTypes.CONDUCTOR_RELEASED_UNPAID, EventTypes.CARD_STATE_CHANGED),
            forTrip.map { it.type },
        )
        assertEquals(CardState.AVAILABLE.name, forTrip.last().cardState)

        // The history must never contain a terminal state, because the card never reached the terminal.
        val states = forTrip.mapNotNull { it.cardState }
        assertFalse(states.contains(CardState.RETURNED.name))
        assertFalse(states.contains(CardState.RETURNED_UNPAID.name))

        for (type in listOf(EventTypes.FARE_WRITTEN_OFF, EventTypes.CONDUCTOR_RELEASED_UNPAID)) {
            val p = json.parseToJsonElement(forTrip.first { it.type == type }.payload).jsonObject
            assertEquals(s.id, p["shiftId"]!!.jsonPrimitive.content)
            assertEquals("1", p["tripId"]!!.jsonPrimitive.content)
            assertEquals("card-UID1", p["cardUuid"]!!.jsonPrimitive.content)
            assertEquals("UID1", p["nfcUid"]!!.jsonPrimitive.content)
            assertEquals("1500", p["amountCentavos"]!!.jsonPrimitive.content)
            assertEquals("uncollected at end of shift", p["reason"]!!.jsonPrimitive.content)
            assertEquals("juan", p["shiftConductor"]!!.jsonPrimitive.content)
            assertEquals("maria", p["actingConductor"]!!.jsonPrimitive.content)
        }
        assertEquals(CardState.AVAILABLE, repo.loadCardStates()["UID1"])
    }

    @Test fun `write off does nothing for a paid card or a trip already written off`() = runBlocking {
        val s = started(open())
        val paidTrip = trip("p"); board(s.id, paidTrip); pay(s.id, paidTrip)
        assertFalse(repo.writeOffAndRelease(s.id, paidTrip.id, "juan"))
        assertEquals(CardState.ASSIGNED_PAID, repo.loadCardStates()["UIDp"])

        val unpaid = trip("u"); board(s.id, unpaid)
        assertTrue(repo.writeOffAndRelease(s.id, unpaid.id, "juan"))
        val before = events().size
        assertFalse(repo.writeOffAndRelease(s.id, unpaid.id, "juan"))
        assertEquals("a second write off writes nothing", before, events().size)
    }

    // 23
    @Test fun `a released card takes a new trip and the old trip stays written off`() = runBlocking {
        val s = started(open())
        val first = trip("1", 1500, uid = "SAME")
        board(s.id, first)
        assertTrue(repo.writeOffAndRelease(s.id, first.id, "juan"))
        assertEquals(CardState.AVAILABLE, repo.loadCardStates()["SAME"])

        val second = trip("2", 2500, uid = "SAME")
        board(s.id, second)
        assertEquals(CardState.ASSIGNED_UNPAID, repo.loadCardStates()["SAME"])
        assertEquals(setOf("1"), repo.writtenOffTripIds())

        val sum = repo.summaryFor(s.id)
        assertEquals(1500L, sum.writtenOffCentavos)
        assertEquals(listOf("1"), sum.writtenOffTripIds)
        assertEquals(2500L, sum.outstandingCentavos)
        assertEquals(listOf("2"), sum.outstandingTripIds)
        assertTrue(sum.reconciles)
    }

    // 24
    @Test fun `ending a shift writes the row the frozen summary and the event together and only once`() = runBlocking {
        val s = started(open("juan"))
        val t = trip("1", 1500)
        board(s.id, t); pay(s.id, t)
        now += 3_600_000

        val summary = repo.endShift(s.id, endedBy = "maria")
        assertNotNull(summary)
        assertEquals(1500L, summary!!.collectedCashCentavos)

        val row = db.shifts().byId(s.id)!!
        assertEquals(now, row.endedAtMillis)
        assertEquals("maria", row.endedBy)
        assertNotNull(row.summaryJson)
        assertNull(repo.openShift())

        val ended = events().filter { it.type == EventTypes.SHIFT_ENDED }
        assertEquals(1, ended.size)
        val p = json.parseToJsonElement(ended.single().payload).jsonObject
        assertEquals("juan", p["conductor"]!!.jsonPrimitive.content)
        assertEquals("maria", p["endedBy"]!!.jsonPrimitive.content)

        val count = events().size
        assertNull("a second end does nothing", repo.endShift(s.id, "maria"))
        assertEquals(count, events().size)
    }

    @Test fun `ending a shift that does not exist writes nothing`() = runBlocking {
        assertNull(repo.endShift("missing", "juan"))
        assertEquals(0, events().size)
    }

    // 25
    @Test fun `the frozen summary equals the live calculation when read back`() = runBlocking {
        val s = started(open())
        val a = trip("1", 1500); board(s.id, a); pay(s.id, a)
        val b = trip("2", 800); board(s.id, b)
        repo.writeOffAndRelease(s.id, b.id, "juan")
        val c = trip("3", 400); board(s.id, c)

        val live = repo.summaryFor(s.id)
        val returned = repo.endShift(s.id, "juan")
        assertEquals(live, returned)
        assertEquals(live, repo.frozenSummary(s.id))
    }

    // 27
    @Test fun `releasing one card leaves every other open unpaid trip untouched`() = runBlocking {
        val s = started(open())
        val a = trip("a", 1000); val b = trip("b", 2000); val c = trip("c", 3000)
        board(s.id, a); board(s.id, b); board(s.id, c)

        assertTrue(repo.writeOffAndRelease(s.id, b.id, "juan"))

        val states = repo.loadCardStates()
        assertEquals(CardState.ASSIGNED_UNPAID, states["UIDa"])
        assertEquals(CardState.AVAILABLE, states["UIDb"])
        assertEquals(CardState.ASSIGNED_UNPAID, states["UIDc"])
        assertEquals(setOf("b"), repo.writtenOffTripIds())

        val sum = repo.summaryFor(s.id)
        assertEquals(listOf("a", "c"), sum.outstandingTripIds.sorted())
        assertEquals(4000L, sum.outstandingCentavos)
        assertEquals(2000L, sum.writtenOffCentavos)
        assertTrue(events().none { it.type == EventTypes.FARE_WRITTEN_OFF && it.tripId in setOf("a", "c") })
    }

    @Test fun `a trip carried into the next shift is paid there and counted once`() = runBlocking {
        val a = started(open())
        val t = trip("1", 1500)
        board(a.id, t)
        repo.endShift(a.id, "juan")

        val b = started(open("maria"))
        pay(b.id, t)

        assertEquals(0L, repo.frozenSummary(a.id)!!.collectedCashCentavos)
        assertEquals(1500L, repo.summaryFor(b.id).collectedCashCentavos)
        assertEquals("shift A was frozen as outstanding", 1500L, repo.frozenSummary(a.id)!!.outstandingCentavos)
    }

    @Test fun `events carry the shift tag and the trip they are about`() = runBlocking {
        val s = started(open())
        val t = trip("1")
        board(s.id, t); pay(s.id, t)
        val tripEvents: List<EventEntity> = events().filter { it.tripId == "1" }
        assertTrue(tripEvents.isNotEmpty())
        assertTrue(tripEvents.all { it.shiftId == s.id })
        assertEquals(CardState.ASSIGNED_PAID.name, tripEvents.last().cardState)
    }
}
