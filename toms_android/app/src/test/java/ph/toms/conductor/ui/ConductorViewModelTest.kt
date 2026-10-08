package ph.toms.conductor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private class FakeUploader : EventUploader {
        override val channel = "data_a"
        var failWith: String? = null
        override suspend fun upload(batch: List<PendingEvent>): UploadResult =
            failWith?.let { UploadResult.Failed(it) } ?: UploadResult.Acked(batch.map { it.eventId }.toSet())
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

    private val uploader = FakeUploader()

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
        vm = ConductorViewModel(repo, location, feedback, settings, deviceConfig, flusher)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun read(uidHex: String = "6F:F1:AD:39", time: Long = 10_000L) =
        CardRead(uidHex, listOf("NfcA"), time)

    @Test
    fun `adding, undoing and clearing passengers updates tally and preview`() = runTest {
        advanceUntilIdle()
        // Starts with 1 regular passenger
        assertEquals(1, vm.state.value.tally.total)
        assertNotNull(vm.state.value.preview)

        vm.addPassenger("student")
        assertEquals(2, vm.state.value.tally.total)

        vm.undoPassenger()
        assertEquals(1, vm.state.value.tally.total)

        vm.clearPassengers()
        assertEquals(0, vm.state.value.tally.total)
        assertNull(vm.state.value.preview)
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

        vm.flushNow()
        // Wait briefly for background thread query to complete
        var tries = 0
        while (vm.state.value.isUploading && tries < 20) {
            advanceUntilIdle()
            Thread.sleep(20)
            tries++
        }
        assertNotNull(vm.state.value.uploadMessage)
        assertTrue(vm.state.value.uploadMessage!!.startsWith("Uploaded"))
        assertEquals(0, db.outbox().pendingCountNow())
    }

    @Test
    fun `updating server URL and device token updates state and enrollment`() = runTest {
        advanceUntilIdle()
        vm.updateServerUrl("http://192.168.1.100:3000")
        vm.updateDeviceToken("dev-1.mytoken123")
        advanceUntilIdle()

        assertEquals("http://192.168.1.100:3000", vm.state.value.serverUrl)
        assertTrue(vm.state.value.isEnrolled)

        vm.updateDeviceToken(null)
        advanceUntilIdle()
        assertTrue(!vm.state.value.isEnrolled)
    }
}
