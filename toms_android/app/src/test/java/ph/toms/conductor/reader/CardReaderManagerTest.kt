package ph.toms.conductor.reader

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Which one reader is used, in every combination of choice and status, and that taps come from that reader only. */
class CardReaderManagerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nfc = FakeCardReader(ReaderKind.PHONE_NFC)
    private val usb = FakeCardReader(ReaderKind.USB, ReaderStatus.NoReader)
    private val choice = MutableStateFlow(ReaderChoice.AUTO)
    private val taps = CopyOnWriteArrayList<CardTap>()
    private lateinit var manager: CardReaderManager
    private val uid = byteArrayOf(1, 2, 3, 4)

    @Before
    fun setUp() {
        manager = CardReaderManager(nfc, usb, choice, scope)
        scope.launch { manager.taps.collect { taps.add(it) } }
    }

    @After
    fun tearDown() {
        manager.stop()
        scope.cancel()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("timed out: $what", condition())
    }

    private fun active() = manager.summary.value.active

    // ---- the choice, as a pure function

    @Test
    fun `phone NFC and USB each use only their own reader whatever the other says`() {
        for (u in listOf(ReaderStatus.Ready, ReaderStatus.NoReader, ReaderStatus.PermissionNeeded())) {
            assertEquals(ReaderKind.PHONE_NFC, CardReaderManager.compute(ReaderChoice.PHONE_NFC, u, ReaderStatus.Ready).active)
        }
        for (n in listOf(ReaderStatus.Ready, ReaderStatus.NoReader)) {
            assertEquals(ReaderKind.USB, CardReaderManager.compute(ReaderChoice.USB, ReaderStatus.NoReader, n).active)
        }
    }

    @Test
    fun `auto uses USB when it is ready or waiting for permission, and phone NFC otherwise`() {
        fun auto(u: ReaderStatus) = CardReaderManager.compute(ReaderChoice.AUTO, u, ReaderStatus.Ready).active
        assertEquals(ReaderKind.USB, auto(ReaderStatus.Ready))
        assertEquals(ReaderKind.USB, auto(ReaderStatus.PermissionNeeded(denied = false)))
        assertEquals("a refusal falls back so a card can still be read", ReaderKind.PHONE_NFC, auto(ReaderStatus.PermissionNeeded(denied = true)))
        assertEquals(ReaderKind.PHONE_NFC, auto(ReaderStatus.NoReader))
        assertEquals(ReaderKind.PHONE_NFC, auto(ReaderStatus.Error("x")))
    }

    // ---- one reader at a time

    @Test
    fun `with auto and no USB reader the phone NFC reads and the USB reader only watches`() {
        manager.start()
        waitFor("nfc running") { nfc.running }
        assertEquals(ReaderKind.PHONE_NFC, active())
        assertTrue("the USB reader watches for a plug-in", usb.running)
    }

    @Test
    fun `plugging a reader in switches to it and stops the phone NFC, and unplugging switches back`() {
        manager.start()
        waitFor("nfc running") { nfc.running }

        usb.setStatus(ReaderStatus.Ready)
        waitFor("usb active") { active() == ReaderKind.USB }
        waitFor("nfc stopped") { !nfc.running }

        usb.setStatus(ReaderStatus.NoReader)
        waitFor("nfc active") { active() == ReaderKind.PHONE_NFC }
        waitFor("nfc running") { nfc.running }
    }

    @Test
    fun `only the active reader's taps get through, so one card is never read twice`() {
        manager.start()
        waitFor("running") { nfc.running }

        runBlocking { nfc.present(uid) }
        waitFor("nfc tap") { taps.size == 1 }

        usb.setStatus(ReaderStatus.Ready)
        waitFor("usb active") { active() == ReaderKind.USB }
        runBlocking {
            nfc.present(uid) // the phone's chip still sees the card, but it is not the reader in use
            usb.present(uid)
        }
        waitFor("usb tap") { taps.size == 2 }
        Thread.sleep(150)
        assertEquals(2, taps.size)
        assertEquals(ReaderKind.USB, taps[1].source)
    }

    @Test
    fun `a fixed choice ignores the other reader completely`() {
        choice.value = ReaderChoice.PHONE_NFC
        manager.start()
        waitFor("nfc running") { nfc.running }
        usb.setStatus(ReaderStatus.Ready)
        Thread.sleep(150)
        assertEquals(ReaderKind.PHONE_NFC, active())
        assertFalse("the USB reader is not even running", usb.running)
        runBlocking { usb.present(uid) }
        Thread.sleep(100)
        assertTrue(taps.isEmpty())
    }

    @Test
    fun `changing the choice while running switches at once`() {
        manager.start()
        usb.setStatus(ReaderStatus.Ready)
        waitFor("usb active") { active() == ReaderKind.USB }

        choice.value = ReaderChoice.PHONE_NFC
        waitFor("nfc active") { active() == ReaderKind.PHONE_NFC }
        waitFor("nfc running") { nfc.running }
        waitFor("usb stopped") { !usb.running }

        choice.value = ReaderChoice.USB
        waitFor("usb active") { active() == ReaderKind.USB }
        waitFor("usb running") { usb.running }
        waitFor("nfc stopped") { !nfc.running }
    }

    @Test
    fun `stopping stops both readers and a later tap is not passed on`() {
        manager.start()
        waitFor("running") { nfc.running }
        manager.stop()
        assertFalse(nfc.running)
        assertFalse(usb.running)
    }

    // ---- what the screens say

    @Test
    fun `the chip and the sentence say which reader is in use and what is wrong, in plain words`() {
        fun s(c: ReaderChoice, u: ReaderStatus, n: ReaderStatus) = CardReaderManager.compute(c, u, n)

        assertEquals("Reader ready", s(ReaderChoice.AUTO, ReaderStatus.NoReader, ReaderStatus.Ready).chip)
        assertEquals("USB reader ready", s(ReaderChoice.AUTO, ReaderStatus.Ready, ReaderStatus.Ready).chip)
        assertEquals("No USB reader", s(ReaderChoice.USB, ReaderStatus.NoReader, ReaderStatus.Ready).chip)
        assertEquals("USB: allow access", s(ReaderChoice.USB, ReaderStatus.PermissionNeeded(), ReaderStatus.Ready).chip)
        assertEquals("USB access refused", s(ReaderChoice.USB, ReaderStatus.PermissionNeeded(true), ReaderStatus.Ready).chip)
        assertEquals("NFC is off", s(ReaderChoice.PHONE_NFC, ReaderStatus.NoReader, ReaderStatus.Error("off")).chip)
        assertEquals("No NFC", s(ReaderChoice.PHONE_NFC, ReaderStatus.NoReader, ReaderStatus.NoReader).chip)

        val denied = s(ReaderChoice.USB, ReaderStatus.PermissionNeeded(true), ReaderStatus.Ready)
        assertTrue(denied.detail, denied.detail.contains("Try again") && denied.canRetry)
        val fine = s(ReaderChoice.AUTO, ReaderStatus.NoReader, ReaderStatus.Ready)
        assertFalse(fine.canRetry)
    }

    @Test
    fun `when auto falls back to the phone NFC the sentence says why the USB reader is not used`() {
        val summary = CardReaderManager.compute(ReaderChoice.AUTO, ReaderStatus.PermissionNeeded(denied = true), ReaderStatus.Ready)
        assertEquals(ReaderKind.PHONE_NFC, summary.active)
        assertTrue(summary.detail, summary.detail.contains("USB reader is not being used"))
        assertTrue(summary.canRetry)

        val none = CardReaderManager.compute(ReaderChoice.AUTO, ReaderStatus.NoReader, ReaderStatus.Ready)
        assertFalse("nothing to explain when no USB reader is plugged in", none.detail.contains("not being used"))
    }

    @Test
    fun `no text names a class, a code or an android term`() {
        val all = listOf(
            ReaderStatus.Ready, ReaderStatus.NoReader, ReaderStatus.PermissionNeeded(), ReaderStatus.PermissionNeeded(true),
            ReaderStatus.Error("The USB reader stopped answering. Unplug it and plug it in again."),
        )
        for (c in ReaderChoice.values()) for (u in all) for (n in all) {
            val sm = CardReaderManager.compute(c, u, n)
            val words = sm.chip + " " + sm.detail + " " + sm.name
            listOf("CCID", "UsbManager", "0x", "Exception", "APDU", "bulk", "PendingIntent").forEach {
                assertFalse("\"$words\" mentions $it", words.contains(it))
            }
        }
    }

    @Test
    fun `retry goes to the USB reader`() {
        manager.retry()
        assertEquals(1, usb.retries)
    }
}
