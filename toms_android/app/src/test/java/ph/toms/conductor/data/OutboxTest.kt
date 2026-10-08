package ph.toms.conductor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.db.EventEntity
import ph.toms.conductor.data.db.OutboxEntity
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.Trip
import ph.toms.conductor.sync.EventUploader
import ph.toms.conductor.sync.OutboxFlusher
import ph.toms.conductor.data.db.PendingEvent
import ph.toms.conductor.sync.UploadResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxTest {

    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private var nextId = 0

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, deviceId = "dev-1", newEventId = { "evt-${nextId++}" })
    }

    @After fun tearDown() = db.close()

    private fun trip(id: String, uid: String = "6FF1AD39") = Trip(
        id = id, cardUuid = "card-$uid", nfcUid = uid, boardingStopId = "s1",
        declaredDestinationStopId = "s2", actualDestinationStopId = null, discountCategoryId = null,
        computedFareCentavos = 1500, fareCentavos = 1500, discountCentavos = 0, fareVersion = 0,
        overrideReason = null, createdAtMillis = 1000,
    )

    private class FakeUploader(
        private val behaviour: (List<PendingEvent>) -> UploadResult,
    ) : EventUploader {
        override val channel = "data_a"
        val seen = mutableListOf<List<String>>()
        override suspend fun upload(batch: List<PendingEvent>): UploadResult {
            seen += batch.map { it.eventId }
            return behaviour(batch)
        }
    }

    private fun flusher(u: EventUploader, batch: Int = 50) = OutboxFlusher(db.outbox(), u, batch) { 5000 }

    @Test fun `recording a trip writes trip card event and outbox row`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        assertEquals(1, db.trips().all().size)
        assertEquals(CardState.ASSIGNED_UNPAID, repo.loadCardStates()["6FF1AD39"])
        assertEquals(1, db.events().count())
        assertEquals(1, db.outbox().pendingCountNow())
    }

    @Test fun `data survives closing and reopening state because it is persisted`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        repo.recordCardState("6FF1AD39", "card-6FF1AD39", "t1", CardState.ASSIGNED_PAID, 2000)
        val reloaded = TripRepository(db, "dev-1")
        assertEquals(1, reloaded.loadTrips().size)
        assertEquals(CardState.ASSIGNED_PAID, reloaded.loadCardStates()["6FF1AD39"])
        assertEquals(2, db.outbox().pendingCountNow())
    }

    @Test fun `duplicate event id is ignored`() = runBlocking {
        val e = EventEntity(eventId = "same", deviceId = "d", type = "t", payload = "{}", createdAtMillis = 1)
        assertTrue(db.events().insert(e) > 0)
        assertEquals(-1L, db.events().insert(e.copy(seq = 0)))
        db.outbox().insert(OutboxEntity("same"))
        db.outbox().insert(OutboxEntity("same"))
        assertEquals(1, db.events().count())
        assertEquals(1, db.outbox().pendingCountNow())
    }

    @Test fun `local sequence is strictly increasing in write order`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        repo.recordTrip(trip("t2", "AA"), CardState.ASSIGNED_UNPAID, 1100)
        val seqs = db.outbox().pending(10).map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(2, seqs.toSet().size)
    }

    @Test fun `failed upload leaves events pending and counts the attempt`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        val report = flusher(FakeUploader { UploadResult.Failed("offline") }).flush()
        assertEquals("offline", report.failure)
        assertEquals(0, report.delivered)
        assertEquals(1, db.outbox().pendingCountNow())
        assertEquals(1, db.outbox().pending(10).single().attemptCount)
    }

    @Test fun `acked events are marked delivered with the channel and not resent`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        val up = FakeUploader { b -> UploadResult.Acked(b.map { it.eventId }.toSet()) }
        val report = flusher(up).flush()
        assertEquals(1, report.delivered)
        assertEquals(0, db.outbox().pendingCountNow())
        flusher(up).flush()
        assertEquals(1, up.seen.size)
    }

    @Test fun `partial ack delivers only acked ids and retries the rest`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        repo.recordTrip(trip("t2", "AA"), CardState.ASSIGNED_UNPAID, 1100)
        val first = db.outbox().pending(10).first().eventId
        val report = flusher(FakeUploader { UploadResult.Acked(setOf(first)) }).flush()
        assertEquals("partial ack", report.failure)
        assertEquals(1, report.delivered)
        val left = db.outbox().pending(10)
        assertEquals(1, left.size)
        assertTrue(left.single().eventId != first)
    }

    @Test fun `an ack for ids that were never sent is ignored`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        val report = flusher(FakeUploader { UploadResult.Acked(setOf("not-ours")) }).flush()
        assertEquals(0, report.delivered)
        assertEquals(1, db.outbox().pendingCountNow())
    }

    @Test fun `lost response then retry delivers once because the server dedups on event id`() = runBlocking {
        repo.recordTrip(trip("t1"), CardState.ASSIGNED_UNPAID, 1000)
        val serverStore = mutableSetOf<String>()
        var lose = true
        val up = FakeUploader { b ->
            serverStore += b.map { it.eventId }
            if (lose) UploadResult.Failed("timeout after server accepted") else UploadResult.Acked(b.map { it.eventId }.toSet())
        }
        flusher(up).flush()
        lose = false
        flusher(up).flush()
        assertEquals(1, serverStore.size)
        assertEquals(0, db.outbox().pendingCountNow())
    }

    @Test fun `large backlog drains in batches in order`() = runBlocking {
        repeat(120) { i -> repo.recordTrip(trip("t$i", "U$i"), CardState.ASSIGNED_UNPAID, 1000L + i) }
        val up = FakeUploader { b -> UploadResult.Acked(b.map { it.eventId }.toSet()) }
        val report = flusher(up, batch = 50).flush()
        assertEquals(120, report.delivered)
        assertEquals(listOf(50, 50, 20), up.seen.map { it.size })
        assertEquals(0, db.outbox().pendingCountNow())
    }
}
