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
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.MemorySessionStore
import ph.toms.conductor.data.ServerSessionRepository
import ph.toms.conductor.data.StoredSession
import ph.toms.conductor.data.StubSessionRepository
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.data.fakeJwt
import ph.toms.conductor.location.LocationTracker
import ph.toms.conductor.sync.ConductorAuthService
import ph.toms.conductor.sync.ConductorLogin

/** What the conductor sees after the app is closed and opened again, with the shift kept in the database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRestoreTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TomsDatabase
    private lateinit var trips: TripRepository
    private lateinit var location: LocationTracker
    private val store = MemorySessionStore()
    private val inOneHour = System.currentTimeMillis() / 1000 + 3600

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

    private fun repo() = ServerSessionRepository(
        object : ConductorAuthService {
            override suspend fun login(username: String, password: String) =
                Result.success(ConductorLogin("Juan", fakeJwt(inOneHour)))
        },
        StubSessionRepository(),
        store,
    )

    /** Starts the view model the way the app does on launch and lets its startup work finish. */
    private fun launch(): SessionViewModel {
        val vm = SessionViewModel(repo(), trips, location)
        val end = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < end) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        return vm
    }

    private fun signIn() = runBlocking { repo().login("juan", "pw") }

    @Test
    fun `restart with an open shift lands the same conductor back on the shift`() {
        signIn()
        runBlocking { trips.startShift("Juan", "v1", "r1") }

        val vm = launch()

        assertEquals("Juan", vm.state.value.conductor)
        assertEquals("v1", vm.state.value.assignment?.vehicle?.id)
        assertEquals("r1", vm.state.value.assignment?.route?.id)
    }

    @Test
    fun `restart without an open shift is signed in but still picks a vehicle`() {
        signIn()

        val vm = launch()

        assertEquals("Juan", vm.state.value.conductor)
        assertNull(vm.state.value.assignment)
    }

    @Test
    fun `another conductor's open shift is not taken over`() {
        signIn()
        runBlocking { trips.startShift("Maria", "v1", "r1") }

        val vm = launch()

        assertEquals("Juan", vm.state.value.conductor)
        assertNull(vm.state.value.assignment)
    }

    @Test
    fun `expired token shows sign-in and leaves the shift open`() {
        store.save(StoredSession("Juan", fakeJwt(1), System.currentTimeMillis() - 1_000))
        runBlocking { trips.startShift("Juan", "v1", "r1") }

        val vm = launch()

        assertNull(vm.state.value.conductor)
        assertNull(vm.state.value.assignment)
        assertNotNull(runBlocking { trips.openShift() })
        assertNull("the expired token is deleted", store.saved)
    }

    @Test
    fun `sign out ends the sign-in but not the shift, and a restart asks for a password`() {
        signIn()
        runBlocking { trips.startShift("Juan", "v1", "r1") }
        val vm = launch()
        assertNotNull(vm.state.value.assignment)

        vm.signOut()

        assertNull(vm.state.value.conductor)
        assertNull(vm.state.value.assignment)
        assertNotNull(runBlocking { trips.openShift() })
        assertNull(launch().state.value.conductor)
    }
}
