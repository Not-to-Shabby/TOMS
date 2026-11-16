package ph.toms.conductor.ble

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.EventTypes
import ph.toms.conductor.data.StartShiftResult
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.CardState

/**
 * The intake's contract: dedupe by (machine id, read_seq), ack only after the storage committed,
 * one event per stored read, the lost-card gap recorded once, and a late paid reply counted as the
 * machine's unverified outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RmReadIntakeTest {

    private lateinit var context: Context
    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private lateinit var link: FakeReturnMachineLink
    private lateinit var intake: RealRmReadIntake
    private var eventSeq = 0

    private val machine = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "dev-1", newEventId = { "evt-${eventSeq++}" })
        link = FakeReturnMachineLink()
        intake = RealRmReadIntake(repo, db, link)
        intake.onConnected(machine)
        link.simulateConnected(address = machine)
    }

    @After
    fun tearDown() = db.close()

    private fun read(seq: Long, uid: String = "6FF1AD39", stateCached: Int = RmProtocol.STATUS_ASSIGNED_UNPAID): RmProtocol.CardRead {
        val uidBytes = uid.filter { it.isLetterOrDigit() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return RmProtocol.CardRead(seq, timestamp = 1_700_000_000L, bootMs = seq * 100, uid = uidBytes, uidHex = uid, atype = 3, stateCached = stateCached)
    }

    private fun reads() = runBlocking { db.rmReads().count() }

    private fun eventsOf(type: String) = runBlocking { db.events().ofType(type) }

    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) {
            Thread.sleep(15)
        }
        assertTrue("condition not met in time", condition())
    }

    private fun settleIdle() {
        // The intake processes on its own IO scope; give the queue a moment to drain.
        val end = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < end) Thread.sleep(15)
    }

    @Test
    fun `a stored read is stored once, gets one event and an ack for its seq`() {
        link.bufferedReads = 3
        intake.onHeartbeat(RmProtocol.Heartbeat(60, 85, 4132, 0, 1, 3, 0))
        intake.onCardRead(read(7))
        settle { eventsOf(EventTypes.RM_STORED_READ).size == 1 }

        assertEquals(1, reads())
        assertEquals(1, eventsOf(EventTypes.RM_STORED_READ).size)
        assertEquals(listOf(7L), link.sentReadAcks.map { it.upToReadSeq })
    }

    @Test
    fun `a resent read is acked again but creates no second event`() {
        link.bufferedReads = 1
        intake.onHeartbeat(RmProtocol.Heartbeat(60, 85, 4132, 0, 1, 1, 0))
        intake.onCardRead(read(4))
        settle { eventsOf(EventTypes.RM_STORED_READ).size == 1 }
        val acksAfterFirst = link.sentReadAcks.size

        intake.onCardRead(read(4)) // the machine's reconnect resend
        settleIdle()

        assertEquals("still one row", 1, reads())
        assertEquals("still one event", 1, eventsOf(EventTypes.RM_STORED_READ).size)
        assertTrue("the resend is acked again", link.sentReadAcks.size > acksAfterFirst)
        assertEquals(4L, link.sentReadAcks.last().upToReadSeq)
    }

    @Test
    fun `a reconnect that resends 10 reads produces exactly 10 events and 10 acks`() {
        // The machine reports ten stored reads and flushes them in order, oldest first.
        link.simulateFullLog(pending = 10)
        intake.onHeartbeat(RmProtocol.Heartbeat(60, 85, 4132, 0, 1, 10, 0))
        repeat(10) { i ->
            intake.onCardRead(read(i + 1L))
            settle { eventsOf(EventTypes.RM_STORED_READ).size == i + 1 }
        }

        assertEquals(10, reads())
        assertEquals("exactly 10 events", 10, eventsOf(EventTypes.RM_STORED_READ).size)
        assertEquals("exactly 10 acks", 10, link.sentReadAcks.size)
        assertEquals("each ack carries the highest seq so far", (1L..10L).toList(), link.sentReadAcks.map { it.upToReadSeq })
    }

    @Test
    fun `a live read delivered fresh produces no stored-read event`() {
        // timestamp == now: the machine just made the read and delivered it live.
        val live = read(1).copy(timestamp = System.currentTimeMillis() / 1000)
        intake.onCardRead(live)
        settleIdle()

        assertEquals(1, reads())
        assertEquals(0, eventsOf(EventTypes.RM_STORED_READ).size)
        assertEquals("live reads are acked too", listOf(1L), link.sentReadAcks.map { it.upToReadSeq })
    }

    @Test
    fun `a lost card whose read_seq skips ahead records the gap once`() {
        intake.onCardRead(read(5, stateCached = RmProtocol.STATUS_LOST))
        settle { reads() == 1 }
        val eventsBefore = eventsOf(EventTypes.RM_LOST_GAP).size

        // The machine's log was full: reads 6..19 are gone, the next one is 20.
        intake.onCardRead(read(20))
        settle { eventsOf(EventTypes.RM_LOST_GAP).size == eventsBefore + 1 }
        intake.onCardRead(read(21))
        settleIdle()

        assertEquals("the gap is recorded once, not per read", eventsBefore + 1, eventsOf(EventTypes.RM_LOST_GAP).size)
        val gap = eventsOf(EventTypes.RM_LOST_GAP).last().payload
        assertTrue(gap.contains("\"gap\":14"))
        assertTrue(gap.contains("\"lastSeq\":5"))
    }

    @Test
    fun `a paid check answered in time records nothing and a late one is counted as unverified`() {
        intake.paidTimeoutMs = 60
        intake.onPaidCheck(8)
        intake.onPaidReplySent(8)
        settleIdle()
        assertEquals("answered in time: quiet", 0, eventsOf(EventTypes.RM_UNVERIFIED).size)

        intake.onPaidCheck(7)
        settle { eventsOf(EventTypes.RM_UNVERIFIED).size == 1 }
        val payload = eventsOf(EventTypes.RM_UNVERIFIED).single().payload
        assertTrue(payload.contains("\"checkSeq\":7"))
        assertTrue(payload.contains("\"reason\":\"timeout\""))
        assertTrue("no card uid in the payload", !payload.contains("6FF1AD39"))

        intake.onPaidReplySent(7) // the late reply changes nothing: the tone already played
        settleIdle()
        assertEquals(1, eventsOf(EventTypes.RM_UNVERIFIED).size)
    }

    @Test
    fun `a disconnect while a paid check waits records the unverified outcome`() {
        intake.paidTimeoutMs = 60_000
        intake.onPaidCheck(9)
        intake.onDisconnected()
        settle { eventsOf(EventTypes.RM_UNVERIFIED).size == 1 }

        val payload = eventsOf(EventTypes.RM_UNVERIFIED).single().payload
        assertTrue(payload.contains("\"reason\":\"disconnected\""))
    }

    @Test
    fun `machine events are tagged with the open shift and ride the outbox without card uids`() = runBlocking {
        val shift = (repo.startShift("juan", "v1", "r1") as StartShiftResult.Started).shift
        link.bufferedReads = 1
        intake.onHeartbeat(RmProtocol.Heartbeat(60, 85, 4132, 0, 1, 1, 0))
        intake.onCardRead(read(3))
        settle { eventsOf(EventTypes.RM_STORED_READ).size == 1 }

        val stored = eventsOf(EventTypes.RM_STORED_READ).single()
        assertEquals("tagged to the shift the phone was in", shift.id, stored.shiftId)
        assertTrue("it waits in the outbox like any other event", db.outbox().pendingCountNow() > 0)
        assertFalse("no passenger card uid leaves the phone", stored.payload.contains("6FF1AD39"))
        assertEquals("the uid itself stays only in the local SQLCipher table", 1, reads())
    }
}
