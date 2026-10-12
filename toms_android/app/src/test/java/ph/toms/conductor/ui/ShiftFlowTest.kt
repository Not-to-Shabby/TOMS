package ph.toms.conductor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import ph.toms.conductor.data.EventTypes
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.PendingEvent
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.feedback.RecordingFeedback
import ph.toms.conductor.location.LocationTracker
import ph.toms.conductor.nfc.CardRead
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.settings.MemorySettings
import ph.toms.conductor.sync.CardRegistrySync
import ph.toms.conductor.sync.EventUploader
import ph.toms.conductor.sync.OutboxFlusher
import ph.toms.conductor.sync.UploadResult

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShiftFlowTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private lateinit var vm: ConductorViewModel
    private val uploader = FakeUploader()
    private var eventSeq = 0
    private var shiftSeq = 0

    private class FakeUploader : EventUploader {
        override val channel = "data_a"
        var failWith: String? = "offline"
        override suspend fun upload(batch: List<PendingEvent>): UploadResult =
            failWith?.let { UploadResult.Failed(it) } ?: UploadResult.Acked(batch.map { it.eventId }.toSet())
    }

    private class Config : DeviceConfigStore {
        override val deviceId = "dev-1"
        override val serverUrl = MutableStateFlow("http://localhost:3000")
        override val deviceToken = MutableStateFlow<String?>(null)
        override fun setServerUrl(url: String) { serverUrl.value = url }
        override fun setDeviceToken(token: String?) { deviceToken.value = token }
    }

    private class NoCardSync : CardRegistrySync {
        override suspend fun fetchApprovedCards(): Result<Map<String, String>> = Result.success(emptyMap())
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "dev-1", newEventId = { "evt-${eventSeq++}" }, newShiftId = { "shift-${shiftSeq++}" })
        vm = ConductorViewModel(
            repo, LocationTracker(context), RecordingFeedback(), MemorySettings(Handedness.Right),
            Config(), OutboxFlusher(db.outbox(), uploader), NoCardSync(),
        )
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun settle(condition: () -> Boolean = { true }) {
        val end = System.currentTimeMillis() + 5_000
        do {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        } while (!condition() && System.currentTimeMillis() < end)
        assertTrue("timed out waiting", condition())
    }

    private fun startShift(conductor: String = "juan", vehicle: String = "v1", route: String = "r1") {
        vm.startShift(conductor, vehicle, route)
        settle { vm.state.value.shift != null || vm.state.value.blockedBy != null }
    }

    private fun tap(uid: String, at: Long) {
        vm.onCardRead(CardRead(uid, listOf("NfcA"), at))
        settle()
    }

    private fun events() = runBlocking { db.events().all() }
    private fun typeCount(type: String) = events().count { it.type == type }

    private val cardA = "6F:F1:AD:39"
    private val cardB = "36:AF:5E:75"
    private val cardC = "56:5E:A4:75"

    private fun startedTripId() = (vm.state.value.result as BoardResult.Started).trip.id

    private fun openPrompt() {
        vm.requestEndShift()
        settle { vm.state.value.endPrompt != null }
    }

    // 28
    @Test fun `ending a shift with no open trips needs no questions and shows the final figures`() {
        startShift()
        openPrompt()
        val prompt = vm.state.value.endPrompt!!
        assertEquals(0, prompt.unpaidCount)
        assertEquals(0L, prompt.unpaidCentavos)

        vm.confirmEndShift(writeOff = false)
        settle { vm.state.value.endedSummary != null }

        assertNull(vm.state.value.shift)
        assertEquals(0L, vm.state.value.endedSummary!!.collectedCashCentavos)
        assertEquals(1, typeCount(EventTypes.SHIFT_ENDED))
    }

    // 29
    @Test fun `the prompt shows how many are unpaid and how much, and going back changes nothing`() {
        startShift()
        tap(cardA, 10_000); tap(cardB, 20_000)
        val before = events().size

        openPrompt()
        val prompt = vm.state.value.endPrompt!!
        assertEquals(2, prompt.unpaidCount)
        assertTrue(prompt.unpaidCentavos > 0)

        vm.goBackToCollect()
        settle()
        assertNull(vm.state.value.endPrompt)
        assertEquals(Tab.Collect, vm.state.value.tab)
        assertNotNull("the shift is still open", vm.state.value.shift)
        assertEquals("nothing was written", before, events().size)
        assertEquals(2, vm.state.value.openTrips.size)
    }

    // 30
    @Test fun `recording uncollected writes off unpaid trips, frees their cards and reports the shortage`() {
        startShift()
        tap(cardA, 10_000); val a = startedTripId()
        tap(cardB, 20_000)
        vm.markPaid(a); settle()
        openPrompt()
        val owed = vm.state.value.endPrompt!!.unpaidCentavos
        assertEquals(1, vm.state.value.endPrompt!!.unpaidCount)

        vm.confirmEndShift(writeOff = true)
        settle { vm.state.value.endedSummary != null }

        val summary = vm.state.value.endedSummary!!
        assertEquals(owed, summary.writtenOffCentavos)
        assertEquals(1, typeCount(EventTypes.FARE_WRITTEN_OFF))
        assertEquals(1, typeCount(EventTypes.CONDUCTOR_RELEASED_UNPAID))
        assertEquals(1, typeCount(EventTypes.SHIFT_ENDED))
        // The unpaid card was freed; no terminal state was written for it.
        val states = runBlocking { repo.loadCardStates() }
        assertEquals(CardState.AVAILABLE, states[cardB.replace(":", "")])
        assertTrue(events().none { it.cardState == CardState.RETURNED_UNPAID.name || it.cardState == CardState.RETURNED.name && it.tripId != a })
    }

    // 31
    @Test fun `a paid trip whose card is still out stays open and is carried into the next shift`() {
        startShift()
        tap(cardA, 10_000); val a = startedTripId()
        vm.markPaid(a); settle()
        openPrompt()
        assertEquals(1, vm.state.value.endPrompt!!.paidOutCount)
        assertEquals(0, vm.state.value.endPrompt!!.unpaidCount)

        vm.confirmEndShift(writeOff = false)
        settle { vm.state.value.endedSummary != null }
        vm.acknowledgeEnded()

        startShift("maria")
        settle { vm.state.value.openTrips.isNotEmpty() }
        assertEquals("still in Collect", 1, vm.state.value.openTrips.size)
        assertEquals(CardState.ASSIGNED_PAID, vm.state.value.openTrips.single().state)
    }

    // 32
    @Test fun `a double tap on End Shift ends the shift once`() {
        startShift()
        openPrompt()
        vm.confirmEndShift(writeOff = false)
        vm.confirmEndShift(writeOff = false)
        settle { vm.state.value.endedSummary != null }
        assertEquals(1, typeCount(EventTypes.SHIFT_ENDED))
    }

    // 33
    @Test fun `ending a shift offline works, leaves the events pending, and they upload later without duplicates`() {
        startShift()
        tap(cardA, 10_000)
        openPrompt()
        vm.confirmEndShift(writeOff = true)
        settle { vm.state.value.endedSummary != null }

        val pendingBefore = runBlocking { db.outbox().pendingCountNow() }
        assertTrue("events wait in the outbox while offline", pendingBefore > 0)

        uploader.failWith = null
        vm.flushNow()
        settle { runBlocking { db.outbox().pendingCountNow() } == 0 }
        assertEquals(0, runBlocking { db.outbox().pendingCountNow() })
        assertEquals("each event once", events().size, events().map { it.eventId }.toSet().size)
    }

    // 34
    @Test fun `a trip started after the shift ended is not added to it`() {
        startShift()
        tap(cardA, 10_000)
        openPrompt()
        vm.confirmEndShift(writeOff = true)
        settle { vm.state.value.endedSummary != null }
        val firstShift = vm.state.value.endedSummary!!.shiftId
        vm.acknowledgeEnded()

        // With no shift open, a tap cannot start a trip at all.
        tap(cardB, 30_000)
        assertTrue(vm.state.value.result is BoardResult.Refused)
        assertEquals(1, runBlocking { repo.loadTrips() }.size)

        startShift("maria")
        tap(cardB, 40_000)
        val frozen = runBlocking { repo.frozenSummary(firstShift) }!!
        assertEquals(1, frozen.tripCount)
    }

    // 35
    @Test fun `a second conductor on the same vehicle sees the old shift's prompt first and both names are recorded`() {
        startShift("juan")
        tap(cardA, 10_000)

        vm.acknowledgeEnded()
        // The first conductor's app was killed; a second person signs in on the same vehicle and route.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val second = ConductorViewModel(
            repo, LocationTracker(context), RecordingFeedback(), MemorySettings(Handedness.Right),
            Config(), OutboxFlusher(db.outbox(), uploader), NoCardSync(),
        )
        second.startShift("maria", "v1", "r1")
        settle { second.state.value.blockedBy != null }

        assertNull("no shift is started for maria yet", second.state.value.shift)
        assertEquals("juan", second.state.value.blockedBy!!.conductor)
        val prompt = second.state.value.endPrompt!!
        assertEquals("juan", prompt.shiftConductor)
        assertEquals(1, prompt.unpaidCount)

        second.confirmEndShift(writeOff = true)
        settle { second.state.value.shift != null }

        assertEquals("maria", second.state.value.shift!!.conductor)
        val ended = events().single { it.type == EventTypes.SHIFT_ENDED }.payload
        assertTrue(ended.contains("\"conductor\":\"juan\""))
        assertTrue(ended.contains("\"endedBy\":\"maria\""))
        val writeOff = events().single { it.type == EventTypes.FARE_WRITTEN_OFF }.payload
        assertTrue(writeOff.contains("\"shiftConductor\":\"juan\""))
        assertTrue(writeOff.contains("\"actingConductor\":\"maria\""))
    }

    // 36
    @Test fun `nothing releases an unpaid card during a running shift`() {
        startShift()
        tap(cardA, 10_000); val a = startedTripId()
        tap(cardB, 20_000)

        // Open the trip, the calculator and the collect list; none of that may release a card.
        tap(cardA, 25_000)
        assertTrue(vm.state.value.result is BoardResult.Existing)
        vm.selectTab(Tab.Collect); settle()
        vm.openCalcFor(vm.state.value.openTrips.first().trip)
        vm.requestEndShift(); settle { vm.state.value.endPrompt != null }
        vm.goBackToCollect(); settle()

        assertEquals(0, typeCount(EventTypes.CONDUCTOR_RELEASED_UNPAID))
        assertEquals(0, typeCount(EventTypes.FARE_WRITTEN_OFF))
        val states = runBlocking { repo.loadCardStates() }
        assertEquals(CardState.ASSIGNED_UNPAID, states[cardA.replace(":", "")])
        assertEquals(CardState.ASSIGNED_UNPAID, states[cardB.replace(":", "")])

        // A passenger still aboard can be marked paid and returned as before.
        vm.markPaid(a); settle()
        assertEquals(CardState.ASSIGNED_PAID, runBlocking { repo.loadCardStates() }[cardA.replace(":", "")])
        vm.returnCard(a)
        // A paid card that comes back is recorded as returned and then freed, in two writes.
        settle { runBlocking { repo.loadCardStates() }[cardA.replace(":", "")] == CardState.AVAILABLE }
        assertEquals(CardState.AVAILABLE, runBlocking { repo.loadCardStates() }[cardA.replace(":", "")])
        assertEquals(0, typeCount(EventTypes.CONDUCTOR_RELEASED_UNPAID))
    }

    @Test fun `a tap with no open shift is refused and nothing is saved`() {
        vm.onCardRead(CardRead(cardA, listOf("NfcA"), 10_000))
        settle()
        val result = vm.state.value.result
        assertTrue(result is BoardResult.Refused)
        assertEquals(0, runBlocking { repo.loadTrips() }.size)
    }

    @Test fun `a payment is counted in the shift where it happens`() {
        startShift("juan")
        tap(cardA, 10_000); val a = startedTripId()
        openPrompt()
        vm.confirmEndShift(writeOff = false)
        settle { vm.state.value.endedSummary != null }
        vm.acknowledgeEnded()

        startShift("maria")
        settle { vm.state.value.openTrips.isNotEmpty() }
        vm.markPaid(a); settle { events().any { it.cardState == CardState.ASSIGNED_PAID.name } }

        val shifts = runBlocking { db.events().ofType(EventTypes.SHIFT_STARTED) }
        assertEquals(2, shifts.size)
        val paid = events().single { it.cardState == CardState.ASSIGNED_PAID.name }
        assertEquals("the payment is tagged with maria's shift", vm.state.value.shift!!.id, paid.shiftId)
        val mariaSummary = runBlocking { repo.summaryFor(vm.state.value.shift!!.id) }
        assertTrue(mariaSummary.collectedFromCarriedInCentavos > 0)
        assertEquals(mariaSummary.collectedCashCentavos, mariaSummary.collectedFromCarriedInCentavos)
    }
}
