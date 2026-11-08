package ph.toms.conductor.telemetry

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.BuildConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.ble.FakeReturnMachineLink
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.sync.FakeUplinkManager
import ph.toms.conductor.sync.UploadStats
import ph.toms.conductor.sync.UplinkState

/** The reporter writes device_status to the outbox: at shift start, on the interval, and at shift end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceStatusReporterTest {

    private lateinit var context: Context
    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private lateinit var stats: UploadStats
    private val uplink = FakeUplinkManager(UplinkState.Online(isCellular = true))
    private val rmLink = FakeReturnMachineLink()
    private var fix: GeoFix? = GeoFix(8.2, 124.2, 5f, 1_000L)
    private var now = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "dev-1", clock = { now })
        stats = UploadStats()
    }

    @After
    fun tearDown() = db.close()

    private fun reporter() = RealDeviceStatusReporter(
        trips = repo, outbox = db.outbox(), events = db.events(),
        uplink = uplink, rmLink = rmLink, stats = stats, context = context,
    )

    private fun statuses() = runBlocking { db.events().ofType("device_status") }

    private fun decode(json: String) = Json { encodeDefaults = true }.decodeFromString<DeviceStatusPayload>(json)

    @Test
    fun `the shift-start status carries every field`() = runBlocking {
        val reporter = reporter()
        reporter.write("shift-1", fix)

        val events = statuses()
        assertEquals(1, events.size)
        assertEquals("shift-1", events.single().shiftId)

        val payload = decode(events.single().payload)
        // The status counts what was waiting before it was written; at shift start that is nothing.
        assertEquals(0, payload.outboxPending)
        assertEquals(0, payload.outboxParked)
        assertEquals("mobile", payload.network)
        assertEquals("disconnected", payload.rmLinkState)
        assertEquals(BuildConfig.VERSION_NAME, payload.appVersion)
        assertEquals("shift-1", payload.shiftId)
        assertEquals(0, payload.localSeq) // nothing was written before the status
        assertTrue(payload.gpsFixAgeSeconds != null)
        assertEquals(5.0, payload.gpsAccuracyMeters!!, 0.0)
    }

    @Test
    fun `stop writes the shift-end status with the ended shift id`() = runBlocking {
        val reporter = reporter()
        reporter.write("shift-1", fix)
        now += 60_000
        // stop() writes the final status itself (its GPS fields are null without a fix provider).
        reporter.stop("shift-1")

        assertEquals(2, statuses().size)
        assertEquals("shift-1", statuses().last().shiftId)
    }

    @Test
    fun `upload counters drain into the status instead of repeating`() = runBlocking {
        stats.record(7, 2)
        val reporter = reporter()
        reporter.write("shift-1", fix)

        assertEquals(7, decode(statuses().first().payload).uploadAttempts)
        assertEquals(2, decode(statuses().first().payload).uploadFailures)

        // Drained: the next status reports zeros, not the same counters again.
        now += 60_000
        reporter.write("shift-1", fix)
        val second = decode(statuses().last().payload)
        assertEquals(0, second.uploadAttempts)
        assertEquals(0, second.uploadFailures)
    }

    @Test
    fun `no fix means null gps fields, and offline means network none`() = runBlocking {
        fix = null
        uplink.setState(UplinkState.Offline)
        rmLink.simulateConnected("TOMS-RM-3C48")

        val reporter = reporter()
        reporter.write("shift-1", fix)

        val payload = decode(statuses().single().payload)
        assertNull(payload.gpsFixAgeSeconds)
        assertNull(payload.gpsAccuracyMeters)
        assertEquals("none", payload.network)
        assertEquals("connected", payload.rmLinkState)
    }

    @Test
    fun `the status is recorded as a pending outbox row for the server to pick up`() = runBlocking {
        val reporter = reporter()
        reporter.write("shift-1", fix)
        assertEquals(1, db.outbox().pendingCountNow())
    }

    @Test
    fun `the local sequence advances with the events written`() = runBlocking {
        val reporter = reporter()
        reporter.write("shift-1", fix)
        val first = decode(statuses().first().payload).localSeq

        now += 60_000
        repo.recordCardState("6FF1AD39", "card-1", "trip-1", ph.toms.conductor.domain.CardState.ASSIGNED_PAID, now)
        now += 60_000
        reporter.write("shift-1", fix)

        val second = decode(statuses().last().payload).localSeq
        assertTrue("the sequence advanced past the first status", second > first)
    }
}
