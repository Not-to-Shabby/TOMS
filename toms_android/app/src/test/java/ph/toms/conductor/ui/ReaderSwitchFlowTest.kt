package ph.toms.conductor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.ble.FakeReturnMachineLink
import ph.toms.conductor.ble.FakeRmKeyEnrolment
import ph.toms.conductor.ble.RealRmReadIntake
import ph.toms.conductor.data.EventTypes
import ph.toms.conductor.data.FakeConfigSync
import ph.toms.conductor.data.InitialConfig
import ph.toms.conductor.data.TestConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.ApprovedCardList
import ph.toms.conductor.feedback.RecordingFeedback
import ph.toms.conductor.location.LocationTracker
import ph.toms.conductor.reader.CardReaderManager
import ph.toms.conductor.reader.CardTap
import ph.toms.conductor.reader.FakeCardReader
import ph.toms.conductor.reader.ReaderChoice
import ph.toms.conductor.reader.ReaderKind
import ph.toms.conductor.reader.ReaderStatus
import ph.toms.conductor.reader.toCardRead
import ph.toms.conductor.service.ShiftServiceController
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.settings.MemorySettings
import ph.toms.conductor.sync.CardRegistrySync
import ph.toms.conductor.sync.ConfigRefresher
import ph.toms.conductor.telemetry.FakeDeviceStatusReporter

/**
 * Switching the card reader in the middle of a shift, through the real view model and database: every passenger card tapped gets
 * exactly one boarding, whichever reader read it, and nothing is lost or doubled at the moment of the switch. The two readers here are
 * test doubles that follow the CardReader contract; the real USB reader's behaviour is in UsbCcidCardReaderTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderSwitchFlowTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private lateinit var vm: ConductorViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nfc = FakeCardReader(ReaderKind.PHONE_NFC)
    private val usb = FakeCardReader(ReaderKind.USB, ReaderStatus.NoReader)
    private val choice = MutableStateFlow(ReaderChoice.AUTO)
    private val feedback = RecordingFeedback()
    private lateinit var manager: CardReaderManager
    private var eventSeq = 0
    private var shiftSeq = 0
    private var clock = 10_000L

    private class Config : DeviceConfigStore {
        override val deviceId = "dev-1"
        override val serverUrl = MutableStateFlow("http://localhost:3000")
        override val deviceToken = MutableStateFlow<String?>(null)
        override fun setServerUrl(url: String) { serverUrl.value = url }
        override fun setDeviceToken(token: String?) { deviceToken.value = token }
    }

    private class NoCardSync : CardRegistrySync {
        override suspend fun fetchApprovedCards(): Result<ApprovedCardList> = Result.failure(java.io.IOException("not used"))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "dev-1", newEventId = { "evt-${eventSeq++}" }, newShiftId = { "shift-${shiftSeq++}" })
        val link = FakeReturnMachineLink()
        vm = ConductorViewModel(
            repo, LocationTracker(context), feedback, MemorySettings(Handedness.Right),
            Config(), NoCardSync(), ConfigRefresher(repo, FakeConfigSync()), link, ShiftServiceController.None, FakeDeviceStatusReporter(),
            RealRmReadIntake(repo, db, link), FakeRmKeyEnrolment(), InitialConfig(TestConfig.config),
        )
        manager = CardReaderManager(nfc, usb, choice, scope)
        // The same wiring the screen has: the manager's taps go to the view model as card reads.
        scope.launch { manager.taps.collect { vm.onCardRead(it.toCardRead()) } }
        manager.start()
        vm.startShift("juan", "v1", "r1")
        settle { vm.state.value.shift != null }
    }

    @After
    fun tearDown() {
        manager.stop()
        scope.cancel()
        var last = -1
        var stable = 0
        val end = System.currentTimeMillis() + 3_000
        while (stable < 3 && System.currentTimeMillis() < end) {
            dispatcher.scheduler.advanceUntilIdle()
            val now = runBlocking { db.events().count() }
            stable = if (now == last) stable + 1 else 0
            last = now
            Thread.sleep(15)
        }
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

    private fun tripsStarted() = runBlocking { db.events().all() }.count { it.type == EventTypes.TRIP_CREATED }

    private fun uid(hex: String) = hex.split(":").map { it.toInt(16).toByte() }.toByteArray()

    /** Presents a card on [reader], one second after the last tap, as a person would. */
    private fun present(reader: FakeCardReader, hex: String) {
        clock += 5_000
        runBlocking { reader.present(uid(hex), clock) }
        settle()
        Thread.sleep(60)
        settle()
    }

    private val cardA = "6F:F1:AD:39"
    private val cardB = "36:AF:5E:75"
    private val cardC = "56:5E:A4:75"

    @Test
    fun `a boarding from the phone NFC and one from the USB reader each start exactly one trip`() {
        present(nfc, cardA)
        assertEquals(1, tripsStarted())

        usb.setStatus(ReaderStatus.Ready)
        settle { manager.summary.value.active == ReaderKind.USB }
        present(usb, cardB)

        assertEquals(2, tripsStarted())
    }

    @Test
    fun `switching to USB in the middle of the shift and back loses no tap and starts no extra trip`() {
        present(nfc, cardA)
        usb.setStatus(ReaderStatus.Ready)
        settle { manager.summary.value.active == ReaderKind.USB }
        present(usb, cardB)
        usb.setStatus(ReaderStatus.NoReader)
        settle { manager.summary.value.active == ReaderKind.PHONE_NFC }
        present(nfc, cardC)

        assertEquals("three cards, three trips", 3, tripsStarted())
    }

    @Test
    fun `the same card seen by both readers at the moment of the switch is one boarding`() {
        usb.setStatus(ReaderStatus.Ready)
        settle { manager.summary.value.active == ReaderKind.USB }
        // The card sits in the phone's NFC field and on the USB reader: both would read it; only the one in use counts.
        runBlocking {
            nfc.present(uid(cardA), 20_000)
            usb.present(uid(cardA), 20_010)
        }
        settle()
        Thread.sleep(100)
        settle()
        assertEquals(1, tripsStarted())
    }

    @Test
    fun `the same card read twice in a moment is one boarding and one cue, the second read dropped by the existing tap debounce`() {
        runBlocking { nfc.present(uid(cardA), 30_000) }
        settle()
        Thread.sleep(100)
        settle()
        val cuesAfterFirst = feedback.cues.size
        assertEquals(1, tripsStarted())

        runBlocking { nfc.present(uid(cardA), 30_400) }
        settle()
        Thread.sleep(100)
        settle()

        // The trip count alone cannot show the debounce (the card is already out, so no second trip starts either way); the cue can.
        assertEquals(1, tripsStarted())
        assertEquals("the second read was dropped, not answered", cuesAfterFirst, feedback.cues.size)
    }

    @Test
    fun `the same card after the debounce window is answered again, not dropped`() {
        runBlocking { nfc.present(uid(cardA), 30_000) }
        settle()
        Thread.sleep(100)
        settle()
        val cuesAfterFirst = feedback.cues.size

        runBlocking { nfc.present(uid(cardA), 30_000 + 5_000) }
        settle()
        Thread.sleep(100)
        settle()

        assertEquals(1, tripsStarted())
        assertTrue("a later tap of a card already out is answered", feedback.cues.size > cuesAfterFirst)
    }

    @Test
    fun `unplugging the USB reader mid-shift falls back and the next card is read by the phone`() {
        usb.setStatus(ReaderStatus.Ready)
        settle { manager.summary.value.active == ReaderKind.USB }
        present(usb, cardA)
        usb.setStatus(ReaderStatus.NoReader)
        settle { manager.summary.value.active == ReaderKind.PHONE_NFC }
        present(nfc, cardB)
        assertEquals(2, tripsStarted())
    }

    @Test
    fun `a refused USB reader under auto leaves the phone NFC reading, so a card is never stranded`() {
        usb.setStatus(ReaderStatus.PermissionNeeded(denied = true))
        settle { manager.summary.value.active == ReaderKind.PHONE_NFC }
        present(nfc, cardA)
        assertEquals(1, tripsStarted())
    }

    @Test
    fun `a card tapped as a different case or format is the same card, whichever reader read it`() {
        present(nfc, cardA)
        usb.setStatus(ReaderStatus.Ready)
        settle { manager.summary.value.active == ReaderKind.USB }
        // the same UID bytes arrive from the USB reader; the card is already out, so this is not a second boarding
        present(usb, cardA)
        assertEquals(1, tripsStarted())
    }

    @Test
    fun `the tap carries the UID as the same text the phone NFC always produced`() {
        val tap = CardTap(uid(cardA), 1, ReaderKind.USB)
        assertEquals("6F:F1:AD:39", tap.toCardRead().uidHex)
        assertEquals(1L, tap.toCardRead().readAtMillis)
    }
}
