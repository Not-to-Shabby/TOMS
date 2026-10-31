package ph.toms.conductor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.PendingEvent
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.CalcMode
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.feedback.Cue
import ph.toms.conductor.feedback.RecordingFeedback
import ph.toms.conductor.location.LocationTracker
import ph.toms.conductor.nfc.CardRead
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.settings.MemorySettings
import ph.toms.conductor.domain.ApprovedCardList
import ph.toms.conductor.sync.CardRegistrySync
import ph.toms.conductor.sync.EventUploader
import ph.toms.conductor.sync.OutboxFlusher
import ph.toms.conductor.sync.UploadResult

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConductorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private lateinit var feedback: RecordingFeedback
    private lateinit var settings: MemorySettings
    private lateinit var location: LocationTracker
    private lateinit var deviceConfig: TestDeviceConfigStore
    private lateinit var flusher: OutboxFlusher
    private lateinit var vm: ConductorViewModel
    private lateinit var syncVm: SyncViewModel

    private class FakeUploader : EventUploader {
        override val channel = "data_a"
        var failWith: String? = null
        /** When set, the server refuses every event with this reason. */
        var rejectAll: String? = null
        override suspend fun upload(batch: List<PendingEvent>): UploadResult {
            failWith?.let { return UploadResult.Failed(it) }
            rejectAll?.let { reason -> return UploadResult.Acked(emptySet(), batch.associate { it.eventId to reason }) }
            return UploadResult.Acked(batch.map { it.eventId }.toSet())
        }
    }

    private class TestDeviceConfigStore(
        override val deviceId: String = "test-dev-1",
        initialUrl: String = "http://localhost:3000",
        initialToken: String? = "test-dev-1.secret",
    ) : DeviceConfigStore {
        override val serverUrl = MutableStateFlow(initialUrl)
        override val deviceToken = MutableStateFlow(initialToken)
        override fun setServerUrl(url: String) { serverUrl.value = url }
        override fun setDeviceToken(token: String?) { deviceToken.value = token }
    }

    private class FakeCardSync : CardRegistrySync {
        /** What the server answers. Null means unreachable, so a test that does not care keeps the built-in test cards. */
        var returnCards: Map<String, String>? = null
        var returnStatuses: Map<String, String> = emptyMap()
        var fail: Boolean = false
        override suspend fun fetchApprovedCards(): Result<ApprovedCardList> {
            val cards = returnCards
            return if (fail || cards == null) Result.failure(java.io.IOException("offline")) else Result.success(ApprovedCardList(cards, returnStatuses))
        }
    }

    private val uploader = FakeUploader()
    private val fakeCardSync = FakeCardSync()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "test-dev-1")
        feedback = RecordingFeedback()
        settings = MemorySettings(Handedness.Right)
        location = LocationTracker(context)
        deviceConfig = TestDeviceConfigStore()
        flusher = OutboxFlusher(db.outbox(), uploader)
        vm = ConductorViewModel(repo, location, feedback, settings, deviceConfig, fakeCardSync)
        syncVm = SyncViewModel(repo, deviceConfig, flusher)
        // Every trip belongs to a shift, so the tests run inside one.
        vm.startShift("juan", "v1", "r1")
        waitUntil { vm.state.value.shift != null }
    }

    /** Room runs on its own thread, so the test scheduler alone cannot tell when a database call has finished. */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        assertTrue("timed out waiting", condition())
    }

    /** Waits until no more database writes are arriving, so a late write cannot hit a closed database. */
    private fun drainWrites() {
        var last = -1
        var stable = 0
        val end = System.currentTimeMillis() + 3_000
        while (stable < 3 && System.currentTimeMillis() < end) {
            testDispatcher.scheduler.advanceUntilIdle()
            val now = runBlocking { db.events().count() }
            stable = if (now == last) stable + 1 else 0
            last = now
            Thread.sleep(15)
        }
    }

    @After
    fun tearDown() {
        drainWrites()
        db.close()
        Dispatchers.resetMain()
    }

    private fun read(uidHex: String = "6F:F1:AD:39", time: Long = 10_000L) =
        CardRead(uidHex, listOf("NfcA"), time)

    @Test
    fun `adding and clearing passengers updates tally and preview`() = runTest {
        advanceUntilIdle()
        // Starts with 1 regular passenger
        assertEquals(1, vm.state.value.tally.total)
        assertNotNull(vm.state.value.preview)

        vm.addPassenger("student")
        assertEquals(2, vm.state.value.tally.total)

        vm.clearPassengers()
        assertEquals(0, vm.state.value.tally.total)
        assertNull(vm.state.value.preview)
    }

    @Test
    fun `starting a trip resets the group to one regular and keeps the destination`() = runTest {
        advanceUntilIdle()
        vm.addPassenger("student")
        vm.addPassenger("student")
        val destination = vm.state.value.destinationStopId

        vm.onCardRead(read("6F:F1:AD:39"))
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.result is BoardResult.Started)
        assertEquals(3, (s.result as BoardResult.Started).trip.passengerCount)
        assertEquals(1, s.tally.total)
        assertEquals(1, s.tally.counts[null])
        assertEquals(destination, s.destinationStopId)
    }

    @Test
    fun `tapping an in-use card leaves the group being built alone`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        vm.addPassenger("pwd")
        val building = vm.state.value.tally

        vm.onCardRead(read("6F:F1:AD:39", 15_000L))
        advanceUntilIdle()

        assertTrue(vm.state.value.result is BoardResult.Existing)
        assertEquals(building, vm.state.value.tally)
    }

    @Test
    fun `tapping a free approved card on Board tab starts a trip and records it`() = runTest {
        advanceUntilIdle()
        vm.addPassenger(null) // 2 regular passengers
        vm.onCardRead(read("6F:F1:AD:39"))
        advanceUntilIdle()

        val res = vm.state.value.result
        assertTrue("Expected Started result, got $res", res is BoardResult.Started)
        val trip = (res as BoardResult.Started).trip
        assertEquals(2, trip.passengerCount)
        assertEquals(Cue.Done, feedback.cues.last())

        // In openTrips list
        assertEquals(1, vm.state.value.openTrips.size)
        assertEquals(trip.id, vm.state.value.openTrips.first().trip.id)
        assertEquals(CardState.ASSIGNED_UNPAID, vm.state.value.openTrips.first().state)

        // Stored in repository
        val stored = repo.loadTrips()
        assertEquals(1, stored.size)
        assertEquals(trip.id, stored.first().id)
    }

    @Test
    fun `tapping an in-use card on Board tab shows its existing trip without creating a new one`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        val firstTrip = (vm.state.value.result as BoardResult.Started).trip

        // Tap the same card again (after debounce window)
        vm.onCardRead(read("6F:F1:AD:39", 15_000L))
        advanceUntilIdle()

        val secondRes = vm.state.value.result
        assertTrue("Expected Existing result, got $secondRes", secondRes is BoardResult.Existing)
        val existing = (secondRes as BoardResult.Existing).trip
        assertEquals(firstTrip.id, existing.id)
        assertEquals(Cue.Opened, feedback.cues.last())

        // Still only 1 trip in repository
        assertEquals(1, repo.loadTrips().size)
    }

    @Test
    fun `tapping an unknown card plays Refused and shows error`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("99:99:99:99"))
        advanceUntilIdle()

        val res = vm.state.value.result
        assertTrue(res is BoardResult.Refused)
        assertEquals(Cue.Refused, feedback.cues.last())
    }

    @Test
    fun `tapping a card on Collect tab focuses that trip and does not create a trip`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        val trip = (vm.state.value.result as BoardResult.Started).trip

        // Switch to Collect tab
        vm.selectTab(Tab.Collect)
        assertEquals(Tab.Collect, vm.state.value.tab)

        // Tap the card while on Collect tab
        vm.onCardRead(read("6F:F1:AD:39", 15_000L))
        advanceUntilIdle()

        assertEquals(trip.id, vm.state.value.focusTripId)
        assertEquals(Cue.Opened, feedback.cues.last())
        assertEquals(1, repo.loadTrips().size)
    }

    @Test
    fun `tapping a free card on Collect tab does not create a trip`() = runTest {
        advanceUntilIdle()
        vm.selectTab(Tab.Collect)
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()

        assertEquals(0, repo.loadTrips().size)
        assertEquals("Open Board to start a trip", vm.state.value.notice)
        assertEquals(Cue.Refused, feedback.cues.last())
    }

    @Test
    fun `marking a trip paid updates state to ASSIGNED_PAID`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        val tripId = (vm.state.value.result as BoardResult.Started).trip.id

        vm.markPaid(tripId)
        advanceUntilIdle()

        val open = vm.state.value.openTrips.first()
        assertEquals(CardState.ASSIGNED_PAID, open.state)
        assertEquals(Cue.Done, feedback.cues.last())
    }

    @Test
    fun `returning a paid card transitions through RETURNED to AVAILABLE and frees the slot`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        val tripId = (vm.state.value.result as BoardResult.Started).trip.id
        vm.markPaid(tripId)
        advanceUntilIdle()

        vm.returnCard(tripId)
        advanceUntilIdle()

        // Card is released to AVAILABLE, so openTrips should now be empty
        assertTrue("openTrips should be empty after card is returned and released", vm.state.value.openTrips.isEmpty())
        assertEquals(Cue.Done, feedback.cues.last())

        // The card can now start a fresh trip
        vm.onCardRead(read("6F:F1:AD:39", 20_000L))
        advanceUntilIdle()
        assertTrue(vm.state.value.result is BoardResult.Started)
    }

    @Test
    fun `openCalcFor pre-populates calculator with trip fare and switches to Calc tab`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        val trip = (vm.state.value.result as BoardResult.Started).trip

        vm.openCalcFor(trip)
        assertEquals(Tab.Calc, vm.state.value.tab)
        assertEquals(CalcMode.CHANGE, vm.state.value.calcMode)
        assertEquals(trip.fareCentavos, vm.state.value.calcDueCentavos)
    }

    @Test
    fun `setting handedness updates state`() = runTest {
        advanceUntilIdle()
        assertEquals(Handedness.Right, vm.state.value.handedness)
        vm.setHandedness(Handedness.Left)
        advanceUntilIdle()
        assertEquals(Handedness.Left, vm.state.value.handedness)
    }

    @Test
    fun `flushNow triggers outbox flusher and updates uploadMessage`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()

        // Pending events present in outbox
        assertTrue(db.outbox().pendingCountNow() > 0)

        syncVm.flushNow()
        // Wait briefly for background thread query to complete
        var tries = 0
        while (syncVm.state.value.isUploading && tries < 20) {
            advanceUntilIdle()
            Thread.sleep(20)
            tries++
        }
        assertNotNull(syncVm.state.value.uploadMessage)
        assertTrue(syncVm.state.value.uploadMessage!!.startsWith("Uploaded"))
        assertEquals(0, db.outbox().pendingCountNow())
    }

    @Test
    fun `updating server URL and device token updates state and enrollment`() = runTest {
        advanceUntilIdle()
        syncVm.updateServerUrl("http://192.168.1.100:3000")
        syncVm.updateDeviceToken("dev-1.mytoken123")
        advanceUntilIdle()

        assertEquals("http://192.168.1.100:3000", syncVm.state.value.serverUrl)
        assertTrue(syncVm.state.value.isEnrolled)

        syncVm.updateDeviceToken(null)
        advanceUntilIdle()
        assertTrue(!syncVm.state.value.isEnrolled)
    }

    @Test
    fun `a refused event is reported as set aside and counted as parked, not pending`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        uploader.rejectAll = "unknown device"

        syncVm.flushNow()
        var tries = 0
        while (syncVm.state.value.isUploading && tries < 20) {
            advanceUntilIdle()
            Thread.sleep(20)
            tries++
        }
        advanceUntilIdle()

        assertTrue(syncVm.state.value.uploadMessage!!.contains("refused by the server"))
        assertEquals(0, db.outbox().pendingCountNow())
        assertTrue(db.outbox().parkedCountNow() > 0)
        var seen = 0
        while (syncVm.state.value.parkedCount == 0 && seen++ < 20) { advanceUntilIdle(); Thread.sleep(20) }
        assertTrue(syncVm.state.value.parkedCount > 0)
        assertEquals(0, syncVm.state.value.pendingCount)
    }

    @Test
    fun `syncApprovedCards updates approvedCards in config and caches them in Room`() = runTest {
        advanceUntilIdle()
        fakeCardSync.returnCards = mapOf("99998888" to "custom-uuid-99")
        vm.syncApprovedCards()
        var tries = 0
        while (vm.state.value.config.approvedCards.cardUuidFor("99998888") == null && tries < 20) {
            advanceUntilIdle()
            Thread.sleep(20)
            tries++
        }

        assertEquals("custom-uuid-99", vm.state.value.config.approvedCards.cardUuidFor("99998888"))
        val cached = repo.loadApprovedCards()
        assertNotNull(cached)
        assertEquals("custom-uuid-99", cached!!["99998888"])
    }

    /** Runs a sync and waits until the fake has been asked and the state has settled. */
    private fun TestScope.syncAndWait(until: () -> Boolean) {
        vm.syncApprovedCards()
        var tries = 0
        while (!until() && tries < 30) {
            advanceUntilIdle()
            Thread.sleep(20)
            tries++
        }
        advanceUntilIdle()
    }

    @Test
    fun `tapping a card the office marked lost says so instead of card not recognised`() = runTest {
        advanceUntilIdle()
        fakeCardSync.returnCards = mapOf("6FF1AD39" to "uuid-ok")
        fakeCardSync.returnStatuses = mapOf("0BAD1111" to "lost")
        syncAndWait { vm.state.value.config.approvedCards.blockedStatusFor("0BAD1111") != null }

        vm.onCardRead(read("0B:AD:11:11"))
        advanceUntilIdle()

        val res = vm.state.value.result
        assertTrue("Expected Refused, got $res", res is BoardResult.Refused)
        assertEquals("Card reported lost. Keep it and tell the office.", (res as BoardResult.Refused).message)
        assertEquals(Cue.Refused, feedback.cues.last())
        assertEquals(0, repo.loadTrips().size)
    }

    @Test
    fun `each reason gets its own message`() = runTest {
        advanceUntilIdle()
        fakeCardSync.returnCards = mapOf("6FF1AD39" to "uuid-ok")
        fakeCardSync.returnStatuses = mapOf("0BAD2222" to "suspended", "0BAD3333" to "retired")
        syncAndWait { vm.state.value.config.approvedCards.blockedStatusFor("0BAD3333") != null }

        vm.onCardRead(read("0BAD2222", 10_000L))
        advanceUntilIdle()
        assertEquals("Card suspended. Do not use.", (vm.state.value.result as BoardResult.Refused).message)

        vm.onCardRead(read("0BAD3333", 20_000L))
        advanceUntilIdle()
        assertEquals("Card retired. Do not use.", (vm.state.value.result as BoardResult.Refused).message)
    }

    @Test
    fun `a card that was never in the registry is still just not recognised`() = runTest {
        advanceUntilIdle()
        fakeCardSync.returnCards = mapOf("6FF1AD39" to "uuid-ok")
        fakeCardSync.returnStatuses = mapOf("0BAD1111" to "lost")
        syncAndWait { vm.state.value.config.approvedCards.blockedStatusFor("0BAD1111") != null }

        vm.onCardRead(read("99:99:99:99"))
        advanceUntilIdle()
        assertEquals("Card not recognised", (vm.state.value.result as BoardResult.Refused).message)
    }

    @Test
    fun `a card that goes missing from the list while a passenger is aboard can still be tapped to collect`() = runTest {
        advanceUntilIdle()
        vm.onCardRead(read("6F:F1:AD:39", 10_000L))
        advanceUntilIdle()
        val trip = (vm.state.value.result as BoardResult.Started).trip

        // The office marks that card lost while the passenger is still aboard.
        fakeCardSync.returnCards = emptyMap()
        fakeCardSync.returnStatuses = mapOf("6FF1AD39" to "lost")
        syncAndWait { vm.state.value.config.approvedCards.blockedStatusFor("6FF1AD39") != null }

        vm.onCardRead(read("6F:F1:AD:39", 30_000L))
        advanceUntilIdle()
        val res = vm.state.value.result
        assertTrue("Expected the open trip, got $res", res is BoardResult.Existing)
        assertEquals(trip.id, (res as BoardResult.Existing).trip.id)
        assertEquals(1, repo.loadTrips().size)
    }

    @Test
    fun `an empty list from the server clears the saved cards, so a card taken out of service stops working`() = runTest {
        advanceUntilIdle()
        fakeCardSync.returnCards = mapOf("99998888" to "custom-uuid-99")
        syncAndWait { vm.state.value.config.approvedCards.cardUuidFor("99998888") != null }
        assertEquals("custom-uuid-99", repo.loadApprovedCards()!!["99998888"])

        fakeCardSync.returnCards = emptyMap()
        fakeCardSync.returnStatuses = mapOf("99998888" to "retired")
        syncAndWait { vm.state.value.config.approvedCards.cardUuidFor("99998888") == null }

        assertEquals(null, vm.state.value.config.approvedCards.cardUuidFor("99998888"))
        assertEquals("retired", vm.state.value.config.approvedCards.blockedStatusFor("99998888"))
        assertTrue(repo.loadApprovedCards()!!.isEmpty())
        assertEquals("retired", repo.loadApprovedCardList()!!.statuses["99998888"])
    }

    @Test
    fun `a failed sync leaves the saved cards alone, so the conductor keeps working offline`() = runTest {
        advanceUntilIdle()
        fakeCardSync.returnCards = mapOf("99998888" to "custom-uuid-99")
        syncAndWait { vm.state.value.config.approvedCards.cardUuidFor("99998888") != null }

        fakeCardSync.fail = true
        syncAndWait { false }

        assertEquals("custom-uuid-99", vm.state.value.config.approvedCards.cardUuidFor("99998888"))
        assertEquals("custom-uuid-99", repo.loadApprovedCards()!!["99998888"])
    }

    @Test
    fun `the saved statuses survive being saved and loaded`() = runTest {
        repo.saveApprovedCardList(ApprovedCardList(mapOf("AA" to "u1"), mapOf("BB" to "lost")))
        val loaded = repo.loadApprovedCardList()!!
        assertEquals(mapOf("AA" to "u1"), loaded.cards)
        assertEquals(mapOf("BB" to "lost"), loaded.statuses)
    }

    @Test
    fun `a list saved by the older app, with no statuses, still loads`() = runTest {
        // Written the way the previous version wrote it: only the card map, under the old key.
        repo.saveApprovedCards(mapOf("AA" to "u1"))
        db.config().put(
            ph.toms.conductor.data.db.ConfigCacheEntity(key = "approved_card_statuses", version = 1, json = "not json", fetchedAtMillis = 0),
        )
        val loaded = repo.loadApprovedCardList()!!
        assertEquals(mapOf("AA" to "u1"), loaded.cards)
        assertTrue(loaded.statuses.isEmpty())
    }

    @Test
    fun `nothing is saved yet means no list, not an empty one`() = runTest {
        assertEquals(null, repo.loadApprovedCardList())
        assertEquals(null, repo.loadApprovedCards())
    }
}
