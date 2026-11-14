package ph.toms.conductor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import ph.toms.conductor.data.StartShiftResult
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.Trip

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShiftHistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private lateinit var vm: ShiftHistoryViewModel
    private var eventSeq = 0
    private var shiftSeq = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "dev-1", newEventId = { "evt-${eventSeq++}" }, newShiftId = { "shift-${shiftSeq++}" })
        vm = ShiftHistoryViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** Room's suspend calls finish on their own thread, so wait rather than trusting the scheduler alone. */
    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(15)
        }
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("condition not met in time", condition())
    }

    /** An ended shift whose one trip was written off, with its card freed. */
    private suspend fun writtenOffTrip(): Pair<String, Trip> {
        val started = repo.startShift("juan", "v1", "r1") as StartShiftResult.Started
        val t = Trip(
            id = "t-1", cardUuid = "card-U1", nfcUid = "U1", boardingStopId = "s1",
            declaredDestinationStopId = "s2", actualDestinationStopId = null, discountCategoryId = null,
            computedFareCentavos = 1500, fareCentavos = 1500, discountCentavos = 0, fareVersion = 1,
            overrideReason = null, createdAtMillis = 1_000L,
        )
        repo.recordTrip(t, CardState.ASSIGNED_UNPAID, 1_000L, started.shift.id)
        repo.writeOffAndRelease(started.shift.id, t.id, "juan")
        repo.endShift(started.shift.id, "juan")
        return started.shift.id to t
    }

    @Test
    fun `the list shows ended shifts and the detail loads the trips and the write-offs`() {
        val (shiftId, _) = runBlocking { writtenOffTrip() }

        vm.open()
        settle { vm.state.value.rows.size == 1 }
        assertEquals("juan", vm.state.value.rows.single().shift.conductor)
        assertNotNull(vm.state.value.rows.single().summary)

        vm.openDetail(shiftId)
        settle { vm.state.value.detail != null && vm.state.value.detail!!.writtenOff.size == 1 }
        val detail = vm.state.value.detail!!
        assertEquals(listOf("t-1"), detail.trips.map { it.id })
        assertEquals("uncollected at end of shift", detail.writtenOff.single().reason)
        assertEquals(1500, detail.writtenOff.single().amountCentavos)

        vm.closeDetail()
        settle { vm.state.value.detail == null }
        vm.close()
        settle { !vm.state.value.visible }
    }

    @Test
    fun `the export builds from the frozen summary and names itself after the shift`() {
        val (shiftId, _) = runBlocking { writtenOffTrip() }
        vm.open()
        settle { vm.state.value.rows.isNotEmpty() }
        vm.openDetail(shiftId)
        settle { vm.state.value.detail != null }

        val export = runBlocking { vm.exportJson() }!!
        assertTrue(export.fileName.startsWith("toms-shift-"))
        assertTrue(export.fileName.endsWith(".json"))
        assertTrue("the kind marks what the file is", export.json.contains("\"kind\": \"toms-shift-export\""))
        assertTrue("the written-off trip is marked", export.json.contains("\"written_off\""))
        assertTrue("the frozen summary rides along", export.json.contains("\"faresChargedCentavos\""))
    }

    @Test
    fun `exporting without an open detail returns nothing`() {
        runBlocking { writtenOffTrip() }
        vm.open()
        settle { vm.state.value.rows.isNotEmpty() }
        assertNull("nothing is exported before a shift is opened", runBlocking { vm.exportJson() })
    }
}
