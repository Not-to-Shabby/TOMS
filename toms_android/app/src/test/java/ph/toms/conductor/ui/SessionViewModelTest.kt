package ph.toms.conductor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.StubSessionRepository
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.Vehicle
import ph.toms.conductor.data.VehicleDto
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.data.serverConfigResponse
import ph.toms.conductor.location.LocationTracker

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TomsDatabase
    private lateinit var trips: TripRepository
    private lateinit var location: LocationTracker
    private val stubRepo = StubSessionRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        trips = TripRepository(db, "test-dev-1")
        location = LocationTracker(context)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        org.junit.Assert.assertTrue("timed out waiting", condition())
    }

    @Test
    fun `when no server config is saved login falls back to stub vehicles and routes`() = runTest {
        val vm = SessionViewModel(stubRepo, trips, location)
        vm.login("conductor1", "password")
        waitUntil { vm.state.value.conductor != null }

        assertEquals("conductor1", vm.state.value.conductor)
        assertEquals(stubRepo.vehicles(), vm.state.value.vehicles)
        assertEquals(stubRepo.routes(), vm.state.value.routes)
    }

    @Test
    fun `when server config has assigned vehicle and route login uses them`() = runTest {
        runBlocking {
            trips.saveServerConfig(
                serverConfigResponse(
                    version = 1234,
                    vehicle = VehicleDto("DONSAL ILIGAN", "1234"),
                    routeName = "Route 1",
                ),
            )
        }

        val vm = SessionViewModel(stubRepo, trips, location)
        vm.login("conductor1", "password")
        waitUntil { vm.state.value.conductor != null }

        assertEquals("conductor1", vm.state.value.conductor)
        assertEquals(listOf(Vehicle("DONSAL ILIGAN", "1234")), vm.state.value.vehicles)
        assertEquals("7", vm.state.value.routes.single().id)
        assertEquals("Route 1", vm.state.value.routes.single().name)
    }

    @Test
    fun `observing server config updates available vehicles and routes dynamically`() = runTest {
        val vm = SessionViewModel(stubRepo, trips, location)
        vm.login("conductor1", "password")
        waitUntil { vm.state.value.conductor != null }
        assertEquals(stubRepo.vehicles(), vm.state.value.vehicles)

        runBlocking {
            trips.saveServerConfig(
                serverConfigResponse(
                    version = 5678,
                    vehicle = VehicleDto("BUS-99", "ABC-999"),
                    routeName = "Highway Express",
                ),
            )
        }
        waitUntil { vm.state.value.vehicles == listOf(Vehicle("BUS-99", "ABC-999")) }

        assertEquals(listOf(Vehicle("BUS-99", "ABC-999")), vm.state.value.vehicles)
        assertEquals(listOf("Highway Express"), vm.state.value.routes.map { it.name })
    }
}
