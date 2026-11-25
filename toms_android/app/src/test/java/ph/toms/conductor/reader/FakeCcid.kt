package ph.toms.conductor.reader

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A CCID reader at the byte level, following the message layouts of the USB CCID specification (rev 1.1, sections 6.1 and 6.2) and
 * the ACS get-UID pseudo-APDU. It is a reading of the documents, not a real reader: it shows the app parses and builds those bytes,
 * not that a reader behaves so.
 */
class FakeCcidReader {
    /** The UID of the card in the field, or null when there is none. */
    @Volatile var card: ByteArray? = null

    /** The reader answers every message with these raw bytes instead of a real answer (garbage tests). Null: behave. */
    @Volatile var rawAnswer: (() -> ByteArray?)? = null

    /** The reader fails with this slot error to the next [failCount] XfrBlock messages (ICC_MUTE = 0xFE, HW_ERROR = 0xFB). */
    @Volatile var xfrError: Int? = null
    @Volatile var xfrErrorCount = 0

    /** The status words the card answers the get-UID APDU with. 90 00 is success. */
    @Volatile var statusWords = byteArrayOf(0x90.toByte(), 0x00)

    /** The card disappears while the get-UID is being answered. */
    @Volatile var cardGoneDuringRead = false

    /** The reader asks for more time (bmCommandStatus 2) this many times before answering an XfrBlock. */
    @Volatile var timeExtensions = 0

    @Volatile var stopAnswering = false

    val received = mutableListOf<ByteArray>()
    private val outbox = ArrayDeque<ByteArray>()
    @Volatile private var powered = false
    @Volatile var closed = false

    @Synchronized
    fun handle(message: ByteArray): Boolean {
        if (closed) return false
        received.add(message.copyOf())
        if (stopAnswering) return true
        rawAnswer?.let { gen -> gen()?.let { outbox.add(it) }; return true }
        if (message.size < 10) return true
        val type = message[0].toInt() and 0xFF
        val seq = message[6].toInt() and 0xFF
        val present = card != null
        when (type) {
            Ccid.GET_SLOT_STATUS -> outbox.add(slotStatus(seq, if (present) (if (powered) 0 else 1) else 2))
            Ccid.ICC_POWER_ON -> {
                if (!present) {
                    outbox.add(dataBlock(seq, ByteArray(0), iccStatus = 2, commandStatus = 1, error = Ccid.ERR_ICC_MUTE))
                } else {
                    powered = true
                    outbox.add(dataBlock(seq, byteArrayOf(0x3B, 0x8F.toByte(), 0x80.toByte(), 0x01), iccStatus = 0))
                }
            }
            Ccid.ICC_POWER_OFF -> {
                powered = false
                outbox.add(slotStatus(seq, if (present) 1 else 2))
            }
            Ccid.XFR_BLOCK -> {
                repeat(timeExtensions) { outbox.add(timeExtension(seq)) }
                val err = xfrError
                if (cardGoneDuringRead) {
                    card = null
                    outbox.add(dataBlock(seq, ByteArray(0), iccStatus = 2, commandStatus = 1, error = Ccid.ERR_ICC_MUTE))
                } else if (err != null && xfrErrorCount > 0) {
                    xfrErrorCount--
                    outbox.add(dataBlock(seq, ByteArray(0), iccStatus = 0, commandStatus = 1, error = err))
                } else if (!present || !powered) {
                    outbox.add(dataBlock(seq, ByteArray(0), iccStatus = if (present) 1 else 2, commandStatus = 1, error = Ccid.ERR_ICC_MUTE))
                } else {
                    val apdu = message.copyOfRange(10, message.size)
                    if (apdu.contentEquals(Ccid.GET_UID_APDU)) {
                        outbox.add(dataBlock(seq, card!! + statusWords, iccStatus = 0))
                    } else {
                        outbox.add(dataBlock(seq, byteArrayOf(0x6A, 0x81.toByte()), iccStatus = 0))
                    }
                }
            }
        }
        return true
    }

    @Synchronized
    fun next(): ByteArray? = if (closed) null else outbox.poll()

    private fun header(type: Int, length: Int, seq: Int, status: Int, error: Int, third: Int = 0): ByteArray {
        val b = ByteArray(10)
        b[0] = type.toByte()
        b[1] = (length and 0xFF).toByte(); b[2] = ((length shr 8) and 0xFF).toByte()
        b[6] = seq.toByte(); b[7] = status.toByte(); b[8] = error.toByte(); b[9] = third.toByte()
        return b
    }

    private fun status(iccStatus: Int, commandStatus: Int) = (commandStatus shl 6) or iccStatus

    private fun slotStatus(seq: Int, icc: Int) = header(Ccid.SLOT_STATUS, 0, seq, status(icc, 0), 0)

    private fun dataBlock(seq: Int, data: ByteArray, iccStatus: Int, commandStatus: Int = 0, error: Int = 0) =
        header(Ccid.DATA_BLOCK, data.size, seq, status(iccStatus, commandStatus), error) + data

    private fun timeExtension(seq: Int) = header(Ccid.DATA_BLOCK, 0, seq, status(0, 2), 1)
}

/** A transport onto a [FakeCcidReader]. [unplug] makes every later transfer fail, as a pulled cable does. */
class FakeCcidTransport(val reader: FakeCcidReader = FakeCcidReader()) : CcidTransport {
    @Volatile private var unplugged = false
    @Volatile var closedCount = 0

    fun unplug() { unplugged = true }

    override fun send(message: ByteArray): Boolean = !unplugged && reader.handle(message)

    override fun receive(timeoutMs: Int): ByteArray? = if (unplugged) null else reader.next()

    override fun close() { closedCount++ }
}

/** A host with readers that come and go, permission that can be granted or refused, and an open that can fail. */
class FakeUsbHost : UsbReaderHost {
    private val _attached = MutableStateFlow<List<UsbReaderInfo>>(emptyList())
    override val attached: StateFlow<List<UsbReaderInfo>> = _attached.asStateFlow()

    val transports = mutableMapOf<String, FakeCcidTransport>()
    private val granted = mutableSetOf<String>()
    @Volatile var answerPermission: Boolean = true
    @Volatile var openFails = false
    @Volatile var permissionRequests = 0
    @Volatile var opens = 0
    private var seq = 0

    fun plugIn(id: String = "dev1", transport: FakeCcidTransport = FakeCcidTransport()): FakeCcidTransport {
        transports[id] = transport
        seq++
        _attached.value = _attached.value.filter { it.id != id } + UsbReaderInfo(id, "Fake CCID reader", 0x072F, 0x2200, seq)
        return transport
    }

    fun unplug(id: String = "dev1") {
        transports[id]?.unplug()
        granted.remove(id)
        _attached.value = _attached.value.filter { it.id != id }
    }

    fun grant(id: String = "dev1") { granted.add(id) }

    override fun hasPermission(id: String) = id in granted

    override suspend fun requestPermission(id: String): Boolean {
        permissionRequests++
        if (answerPermission) granted.add(id)
        return answerPermission
    }

    override fun open(id: String): CcidTransport? {
        opens++
        if (openFails) return null
        return transports[id]
    }
}
