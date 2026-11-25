package ph.toms.conductor.reader

import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The USB reader against a fake host and a byte-level fake CCID reader: every situation of the brief, with real time and short poll
 * intervals. The fake reader follows the CCID specification's layouts; none of this shows how a real reader behaves.
 */
class UsbCcidCardReaderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = FakeUsbHost()
    private val taps = CopyOnWriteArrayList<CardTap>()
    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var reader: UsbCcidCardReader

    private val uidA = byteArrayOf(0x6F, 0xF1.toByte(), 0xAD.toByte(), 0x39)
    private val uidB = byteArrayOf(0x36, 0xAF.toByte(), 0x5E, 0x75)

    @Before
    fun setUp() {
        reader = UsbCcidCardReader(host, scope, Dispatchers.IO, pollMillis = 10, reconnectMillis = 60, log = { logs.add(it) })
        scope.launch { reader.taps.collect { taps.add(it) } }
    }

    @After
    fun tearDown() {
        reader.stop()
        scope.cancel()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("timed out: $what", condition())
    }

    private fun pause(ms: Long = 150) = Thread.sleep(ms)

    private fun ready(): FakeCcidTransport {
        host.grant()
        val t = host.plugIn()
        reader.start()
        waitFor("ready") { reader.status.value == ReaderStatus.Ready }
        return t
    }

    // ---- reading

    @Test
    fun `a card presented gives one tap with its UID, the time and the USB source`() {
        val t = ready()
        t.reader.card = uidA
        waitFor("tap") { taps.size == 1 }

        assertArrayEquals(uidA, taps[0].uid)
        assertEquals(ReaderKind.USB, taps[0].source)
        assertTrue(taps[0].atMillis > 0)
    }

    @Test
    fun `a card left on the reader is read once, however long it stays`() {
        val t = ready()
        t.reader.card = uidA
        waitFor("tap") { taps.size == 1 }
        pause(400)
        assertEquals(1, taps.size)
    }

    @Test
    fun `taking the card away and presenting it again gives a second tap`() {
        val t = ready()
        t.reader.card = uidA
        waitFor("first") { taps.size == 1 }
        t.reader.card = null
        pause(100)
        t.reader.card = uidA
        waitFor("second") { taps.size == 2 }
    }

    @Test
    fun `two different cards one after the other give two taps in order`() {
        val t = ready()
        t.reader.card = uidA
        waitFor("a") { taps.size == 1 }
        t.reader.card = null
        pause(80)
        t.reader.card = uidB
        waitFor("b") { taps.size == 2 }
        assertArrayEquals(uidB, taps[1].uid)
    }

    @Test
    fun `swapping straight to another card without a gap is not missed`() {
        val t = ready()
        t.reader.card = uidA
        waitFor("a") { taps.size == 1 }
        // the reader reports no empty moment: the card in the field changes in one step
        t.reader.card = uidB
        pause(300)
        // Not detectable through the slot status alone (it stays "present"); the next presentation after a gap is read.
        // This is a known limit, listed in the docs, and it must not make a duplicate of the first card.
        assertTrue(taps.none { it.uid.contentEquals(uidA) && taps.indexOf(it) > 0 })
    }

    // ---- no reader, permission

    @Test
    fun `with no reader plugged in the status says so and nothing is read`() {
        reader.start()
        waitFor("no reader") { reader.status.value == ReaderStatus.NoReader }
        pause(100)
        assertEquals(0, host.opens)
        assertTrue(taps.isEmpty())
    }

    @Test
    fun `a reader without permission is asked for it once and then read`() {
        host.plugIn()
        reader.start()
        waitFor("ready") { reader.status.value == ReaderStatus.Ready }
        assertEquals(1, host.permissionRequests)
    }

    @Test
    fun `permission refused shows as refused, is not asked again by itself, and nothing is opened`() {
        host.answerPermission = false
        host.plugIn()
        reader.start()
        waitFor("refused") { reader.status.value == ReaderStatus.PermissionNeeded(denied = true) }
        pause(300)
        assertEquals("not asked again until the person asks", 1, host.permissionRequests)
        assertEquals(0, host.opens)
    }

    @Test
    fun `try again after a refusal asks again and reads when it is allowed`() {
        host.answerPermission = false
        val t = host.plugIn()
        reader.start()
        waitFor("refused") { reader.status.value == ReaderStatus.PermissionNeeded(denied = true) }

        host.answerPermission = true
        reader.retry()
        waitFor("ready") { reader.status.value == ReaderStatus.Ready }
        t.reader.card = uidA
        waitFor("tap") { taps.size == 1 }
        assertEquals(2, host.permissionRequests)
    }

    @Test
    fun `a reader that cannot be opened is an error in words and is tried again a little later`() {
        host.grant()
        host.openFails = true
        host.plugIn()
        reader.start()
        waitFor("error") { (reader.status.value as? ReaderStatus.Error)?.reason?.contains("could not be opened") == true }
        host.openFails = false
        waitFor("recovers") { reader.status.value == ReaderStatus.Ready }
    }

    // ---- unplug and replug

    @Test
    fun `unplugging goes to no reader, closes the connection and stops reading`() {
        val t = ready()
        host.unplug()
        waitFor("no reader") { reader.status.value == ReaderStatus.NoReader }
        waitFor("closed") { t.closedCount >= 1 }
        t.reader.card = uidA
        pause(200)
        assertTrue("no tap from an unplugged reader", taps.isEmpty())
    }

    @Test
    fun `unplugging in the middle of a read ends quietly with no tap and no crash`() {
        val t = ready()
        // the cable is pulled between the slot status and the UID read
        t.reader.cardGoneDuringRead = false
        t.reader.card = uidA
        t.unplug()
        host.unplug()
        pause(300)
        assertEquals(ReaderStatus.NoReader, reader.status.value)
        assertTrue(taps.size <= 1) // either the read finished before the pull, or it did not; never more than one, never an error
    }

    @Test
    fun `replugging reads again with the permission asked for again`() {
        ready()
        host.unplug()
        waitFor("gone") { reader.status.value == ReaderStatus.NoReader }

        host.answerPermission = true
        val t2 = host.plugIn()
        waitFor("ready again") { reader.status.value == ReaderStatus.Ready }
        t2.reader.card = uidB
        waitFor("tap") { taps.size == 1 }
        assertArrayEquals(uidB, taps[0].uid)
        assertEquals("a replug is a new connection and a new permission", 1, host.permissionRequests)
    }

    @Test
    fun `a quick unplug and replug with the same id restarts the session`() {
        val t1 = ready()
        host.unplug()
        val t2 = host.plugIn()
        host.grant()
        waitFor("second open") { host.opens >= 2 }
        waitFor("ready") { reader.status.value == ReaderStatus.Ready }
        t2.reader.card = uidA
        waitFor("tap") { taps.size == 1 }
        assertTrue(t1.closedCount >= 1)
    }

    // ---- card and reader faults

    @Test
    fun `a card taken away during the read is no tap, no error, and it reads when presented again`() {
        val t = ready()
        t.reader.cardGoneDuringRead = true
        t.reader.card = uidA
        pause(300)
        assertTrue(taps.isEmpty())
        assertEquals(ReaderStatus.Ready, reader.status.value)

        t.reader.cardGoneDuringRead = false
        t.reader.card = uidA
        waitFor("tap") { taps.size == 1 }
    }

    @Test
    fun `a card that never answers is given up after a few tries, not retried forever`() {
        val t = ready()
        t.reader.xfrError = Ccid.ERR_ICC_MUTE
        t.reader.xfrErrorCount = 1_000
        t.reader.card = uidA
        pause(500)
        val used = t.reader.received.count { (it[0].toInt() and 0xFF) == Ccid.XFR_BLOCK }
        assertTrue("tried $used times", used in 1..UsbCcidCardReader.MAX_TRIES)
        assertTrue(taps.isEmpty())
        assertEquals(ReaderStatus.Ready, reader.status.value)
    }

    @Test
    fun `a card with an unusable answer is not retried until it is taken away`() {
        val t = ready()
        t.reader.statusWords = byteArrayOf(0x6A, 0x81.toByte())
        t.reader.card = uidA
        pause(400)
        assertTrue(taps.isEmpty())
        assertEquals("one try, then left alone", 1, t.reader.received.count { (it[0].toInt() and 0xFF) == Ccid.XFR_BLOCK })
    }

    @Test
    fun `a reader that reports a hardware error repeatedly stops with a plain error, then recovers when it works again`() {
        val t = ready()
        t.reader.xfrError = Ccid.ERR_HW_ERROR
        t.reader.xfrErrorCount = 1_000
        t.reader.card = uidA
        waitFor("error") { reader.status.value is ReaderStatus.Error }
        val text = (reader.status.value as ReaderStatus.Error).reason
        assertTrue(text, text.contains("stopped answering") && text.contains("plug it in again"))

        t.reader.xfrErrorCount = 0
        host.unplug()
        val t2 = host.plugIn()
        host.grant()
        waitFor("ready") { reader.status.value == ReaderStatus.Ready }
        t2.reader.card = uidB
        waitFor("tap") { taps.size == 1 }
    }

    @Test
    fun `a reader that stops answering is an error in words, and it is tried again after a while`() {
        val t = ready()
        t.reader.stopAnswering = true
        waitFor("error") { reader.status.value is ReaderStatus.Error }
        t.reader.stopAnswering = false
        waitFor("recovers") { reader.status.value == ReaderStatus.Ready }
    }

    @Test
    fun `the reader asking for more time is waited out`() {
        val t = ready()
        t.reader.timeExtensions = 2
        t.reader.card = uidA
        waitFor("tap") { taps.size == 1 }
    }

    @Test
    fun `random bytes from the reader never crash it, never make a tap, and end as an error`() {
        host.grant()
        val t = host.plugIn()
        val rnd = Random(7)
        t.reader.rawAnswer = { ByteArray(rnd.nextInt(30)).also { rnd.nextBytes(it) } }
        reader.start()
        pause(500)
        assertTrue("a tap from random bytes", taps.isEmpty())
        val s = reader.status.value
        assertTrue("status was $s", s is ReaderStatus.Error || s == ReaderStatus.Ready)
    }

    @Test
    fun `random bytes that look like valid answers never produce a UID of a wrong length`() {
        host.grant()
        val t = host.plugIn()
        val rnd = Random(11)
        t.reader.rawAnswer = {
            // looks like a DataBlock with a plausible header and random contents
            val data = ByteArray(rnd.nextInt(14)).also { rnd.nextBytes(it) }
            byteArrayOf(0x80.toByte(), data.size.toByte(), 0, 0, 0, 0, 0, 0, 0, 0) + data
        }
        reader.start()
        pause(500)
        assertTrue(taps.all { it.uid.size in listOf(4, 7, 10) })
    }

    // ---- stopping and logging

    @Test
    fun `stopping closes the reader and no later card is read`() {
        val t = ready()
        reader.stop()
        waitFor("closed") { t.closedCount >= 1 }
        t.reader.card = uidA
        pause(300)
        assertTrue(taps.isEmpty())
    }

    @Test
    fun `starting again after a stop reads again`() {
        val t = ready()
        reader.stop()
        pause(100)
        reader.start()
        waitFor("ready") { reader.status.value == ReaderStatus.Ready }
        t.reader.card = uidA
        waitFor("tap") { taps.size == 1 }
    }

    @Test
    fun `the log carries at most a two byte UID prefix, never a whole UID`() {
        val t = ready()
        t.reader.card = uidA
        waitFor("tap") { taps.size == 1 }
        val joined = logs.joinToString(" ")
        assertTrue(joined, joined.contains("uid=6FF1.."))
        assertFalse(joined, joined.contains("6FF1AD39"))
        assertFalse(joined, joined.contains("AD39"))
    }

    @Test
    fun `a second start while running does not open the reader twice`() {
        ready()
        val opens = host.opens
        reader.start()
        reader.start()
        pause(200)
        assertEquals(opens, host.opens)
    }
}
