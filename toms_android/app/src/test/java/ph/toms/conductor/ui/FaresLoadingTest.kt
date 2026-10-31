package ph.toms.conductor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import ph.toms.conductor.data.DiscountDto
import ph.toms.conductor.data.EventTypes
import ph.toms.conductor.data.FakeConfigSync
import ph.toms.conductor.data.InitialConfig
import ph.toms.conductor.data.TestConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.data.serverConfigResponse
import ph.toms.conductor.domain.ApprovedCardList
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.TomsConfig
import ph.toms.conductor.feedback.RecordingFeedback
import ph.toms.conductor.location.LocationTracker
import ph.toms.conductor.nfc.CardRead
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.settings.MemorySettings
import ph.toms.conductor.sync.CardRegistrySync
import ph.toms.conductor.sync.ConfigFetch
import ph.toms.conductor.sync.ConfigRefresher

/** The phone as a release build starts: with no fares in it, until the server's have been downloaded. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaresLoadingTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private val configSync = FakeConfigSync()
    private val token = MutableStateFlow<String?>(null)
    private var eventSeq = 0

    private class Device(override val deviceToken: MutableStateFlow<String?>) : DeviceConfigStore {
        override val deviceId = "dev-1"
        override val serverUrl = MutableStateFlow("https://toms.example")
        override fun setServerUrl(url: String) { serverUrl.value = url }
        override fun setDeviceToken(token: String?) { deviceToken.value = token }
    }

    /** The registry knows the four test cards, so a refused trip can only be refused for want of fares. */
    private class Cards : CardRegistrySync {
        override suspend fun fetchApprovedCards() = Result.success(
            ApprovedCardList(TestConfig.config.approvedCards.let { mapOf("6FF1AD39" to "00000000-0000-4000-8000-00000000f1a1", "36AF5E75" to "00000000-0000-4000-8000-00000000f1a2") }),
        )
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "dev-1", newEventId = { "evt-${eventSeq++}" })
    }

    @After fun tearDown() {
        settle()
        db.close()
        Dispatchers.resetMain()
    }

    private fun settle(condition: () -> Boolean = { true }) {
        val end = System.currentTimeMillis() + 5_000
        do {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        } while (!condition() && System.currentTimeMillis() < end)
    }

    private fun newVm(initial: TomsConfig = TomsConfig.EMPTY): ConductorViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = ConductorViewModel(
            repo, LocationTracker(context), RecordingFeedback(), MemorySettings(Handedness.Right),
            Device(token), Cards(), ConfigRefresher(repo, configSync), InitialConfig(initial),
        )
        vm.startShift("juan", "v1", "r1")
        settle { vm.state.value.shift != null }
        return vm
    }

    private fun tap(vm: ConductorViewModel, uid: String = "6F:F1:AD:39", at: Long = 10_000L) {
        vm.onCardRead(CardRead(uid, listOf("NfcA"), at))
        settle()
    }

    private val card = "6FF1AD39"

    // ---- no cache: no paid trip

    @Test fun `with no fares loaded the board says so and a tap starts no trip`() {
        val vm = newVm()
        assertFalse(vm.state.value.faresLoaded)
        assertNull(vm.state.value.configInfo)
        assertNull("nothing to price", vm.state.value.preview)

        tap(vm)

        val result = vm.state.value.result
        assertTrue("expected a refusal, got $result", result is BoardResult.Refused)
        assertEquals("No fares loaded. Connect and sync.", (result as BoardResult.Refused).message)
        assertEquals(0, runBlocking { db.trips().all().size })
        assertEquals("no event was written", 0, runBlocking { db.events().all().count { it.type == EventTypes.TRIP_CREATED } })
        assertTrue(vm.state.value.openTrips.isEmpty())
    }

    @Test fun `a phone upgraded from before fares were downloaded refuses new trips until it syncs`() {
        // An old phone has trips and card states in its database but no saved fares.
        runBlocking { repo.saveApprovedCardList(ApprovedCardList(mapOf(card to "00000000-0000-4000-8000-00000000f1a1"))) }
        val vm = newVm()
        tap(vm)
        assertTrue(vm.state.value.result is BoardResult.Refused)
    }

    @Test fun `the test fares are used only when a debug build asks for them`() {
        val vm = newVm(initial = TestConfig.config)
        assertTrue(vm.state.value.faresLoaded)
        assertEquals("s1", vm.state.value.boardingStopId)
    }

    // ---- syncing makes it usable

    @Test fun `once the fares are downloaded a tap starts a trip priced from them`() {
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 4242, baseFareCentavos = 1800))
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.faresLoaded }

        val s = vm.state.value
        assertTrue(s.faresLoaded)
        assertEquals(4242, s.configInfo!!.version)
        assertEquals("Tibanga - Pala-o", s.configInfo!!.routeName)
        assertEquals("the first stop is boarding, the second the destination", "11" to "12", s.boardingStopId to s.destinationStopId)
        assertNotNull(s.preview)

        tap(vm)

        val started = vm.state.value.result as BoardResult.Started
        assertEquals(4242, started.trip.fareVersion)
        assertEquals("the fare is the server's, not 15.00", 1800, started.trip.fareCentavos)
        assertEquals("11", started.trip.boardingStopId)
    }

    @Test fun `the trip event carries the server's fare version`() {
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 555))
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.faresLoaded }
        tap(vm)

        val payload = runBlocking { db.events().all() }.first { it.type == EventTypes.TRIP_CREATED }.payload
        assertTrue(payload, payload.contains("\"fareVersion\":555"))
    }

    @Test fun `a sync made while the app was closed is picked up when it opens`() {
        runBlocking { repo.saveServerConfig(serverConfigResponse(version = 90, baseFareCentavos = 2200)) }
        val vm = newVm()
        settle { vm.state.value.faresLoaded }

        assertEquals(90, vm.state.value.configInfo!!.version)
        assertEquals(2200, vm.state.value.config.fare.baseFareCentavos)
        assertTrue("it works offline: no fetch was needed", configSync.calls == 0)
    }

    // ---- offline

    @Test fun `offline, the last downloaded fares keep working`() {
        runBlocking { repo.saveServerConfig(serverConfigResponse(version = 90, baseFareCentavos = 2200)) }
        configSync.next = ConfigFetch.Failed("no signal")
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.configMessage != null }

        assertTrue(vm.state.value.faresLoaded)
        assertEquals("no signal", vm.state.value.configMessage)
        tap(vm)
        assertEquals(2200, (vm.state.value.result as BoardResult.Started).trip.fareCentavos)
    }

    @Test fun `a 404 says why on More and keeps the saved fares`() {
        runBlocking { repo.saveServerConfig(serverConfigResponse(version = 90)) }
        configSync.next = ConfigFetch.Unavailable("No active fare matrix. Publish one on the Fare & LTFRB page.")
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.configMessage != null }

        assertEquals("No active fare matrix. Publish one on the Fare & LTFRB page.", vm.state.value.configMessage)
        assertEquals(90, vm.state.value.configInfo!!.version)
        assertTrue(vm.state.value.faresLoaded)
    }

    @Test fun `a 404 with nothing saved leaves the phone with no fares and the reason`() {
        configSync.next = ConfigFetch.Unavailable("This device has no vehicle with an assigned route. Assign one on the dashboard.")
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.configMessage != null }

        assertFalse(vm.state.value.faresLoaded)
        assertTrue(vm.state.value.configMessage!!.contains("assigned route"))
        tap(vm)
        assertTrue(vm.state.value.result is BoardResult.Refused)
    }

    @Test fun `a success clears the old message`() {
        configSync.next = ConfigFetch.Failed("no signal")
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.configMessage != null }

        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 3))
        vm.refreshConfig()
        settle { vm.state.value.faresLoaded }

        assertNull(vm.state.value.configMessage)
        assertEquals(3, vm.state.value.configInfo!!.version)
    }

    @Test fun `the card list the phone already had survives a fares update`() {
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 4242))
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.faresLoaded && vm.state.value.config.approvedCards.size > 0 }
        assertEquals(2, vm.state.value.config.approvedCards.size)
    }

    // ---- a fare change in the middle of a shift

    @Test fun `a fare change mid-shift prices new trips only, and the old trip keeps its fare and version`() {
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 100, baseFareCentavos = 1500))
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.faresLoaded }

        tap(vm, "6F:F1:AD:39", 10_000)
        val first = (vm.state.value.result as BoardResult.Started).trip
        assertEquals(100, first.fareVersion)
        assertEquals(1500, first.fareCentavos)

        // An admin publishes a new fare; the phone downloads it while the shift is still open.
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 101, baseFareCentavos = 2000))
        vm.refreshConfig()
        settle { vm.state.value.configInfo?.version == 101 }

        tap(vm, "36:AF:5E:75", 20_000)
        val second = (vm.state.value.result as BoardResult.Started).trip
        assertEquals(101, second.fareVersion)
        assertEquals(2000, second.fareCentavos)

        val stored = runBlocking { repo.loadTrips() }.associateBy { it.id }
        assertEquals("the first trip is unchanged", 1500, stored.getValue(first.id).fareCentavos)
        assertEquals(100, stored.getValue(first.id).fareVersion)
        assertEquals(101, stored.getValue(second.id).fareVersion)
        assertEquals("the open trip still shows what it was charged", 1500, vm.state.value.openTrips.first { it.trip.id == first.id }.trip.fareCentavos)
    }

    @Test fun `a trip already out can still be tapped when the fares are gone, because that charges nobody`() {
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 100))
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.faresLoaded }
        tap(vm, "6F:F1:AD:39", 10_000)
        assertEquals(CardState.ASSIGNED_UNPAID, vm.state.value.openTrips.single().state)

        runBlocking { db.openHelper.writableDatabase.execSQL("DELETE FROM server_config") }
        // The phone still holds the fares in memory, so the card shows its trip as it always did.
        tap(vm, "6F:F1:AD:39", 20_000)
        assertTrue(vm.state.value.result is BoardResult.Existing)
    }

    @Test fun `the chosen stops are kept when a new config still has them and reset when it does not`() {
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 100))
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.faresLoaded }
        vm.selectBoarding("12")
        vm.selectDestination("13")

        configSync.next = ConfigFetch.Loaded(serverConfigResponse(version = 101, baseFareCentavos = 1900))
        vm.refreshConfig()
        settle { vm.state.value.configInfo?.version == 101 }
        assertEquals("12" to "13", vm.state.value.boardingStopId to vm.state.value.destinationStopId)

        configSync.next = ConfigFetch.Loaded(
            serverConfigResponse(version = 102, stops = listOf(
                ph.toms.conductor.data.StopDto("21", "New A", 8.3, 124.3),
                ph.toms.conductor.data.StopDto("22", "New B", 8.4, 124.3),
            )),
        )
        vm.refreshConfig()
        settle { vm.state.value.configInfo?.version == 102 }
        assertEquals("21" to "22", vm.state.value.boardingStopId to vm.state.value.destinationStopId)
        assertNotNull(vm.state.value.preview)
    }

    @Test fun `the discounts on the board are the downloaded ones`() {
        configSync.next = ConfigFetch.Loaded(serverConfigResponse(discounts = listOf(DiscountDto("senior", "Senior citizen", 30))))
        token.value = "dev-1.secret"
        val vm = newVm()
        settle { vm.state.value.faresLoaded }
        assertEquals(listOf("senior"), vm.state.value.config.discountCategories.map { it.id })
        assertEquals("Senior citizen", vm.state.value.config.category("senior")!!.name)
        assertNull("the test student discount is gone", vm.state.value.config.category("student"))
    }
}
