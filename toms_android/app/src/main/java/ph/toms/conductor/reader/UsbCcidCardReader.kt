package ph.toms.conductor.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A USB smart-card (CCID) reader that is plugged in. [attachSeq] changes every time it is plugged in, so a quick unplug and replug is seen. */
data class UsbReaderInfo(val id: String, val name: String, val vendorId: Int, val productId: Int, val attachSeq: Int)

/** What the USB reader needs from Android, so everything above it runs in a plain test with a fake. */
interface UsbReaderHost {
    /** The CCID readers plugged in right now, in the order Android lists them. Empty when none, or when the phone has no USB host mode. */
    val attached: StateFlow<List<UsbReaderInfo>>

    fun hasPermission(id: String): Boolean

    /** Asks Android to let the app use the reader (this may show a system dialog) and waits for the answer. False when the person said no. */
    suspend fun requestPermission(id: String): Boolean

    /** Opens the reader's CCID interface. Null when it cannot be opened (unplugged meanwhile, claimed by another app). Blocking. */
    fun open(id: String): CcidTransport?
}

/**
 * Reads a passenger card's UID through a USB CCID reader plugged into the phone's USB port (USB host mode, over an OTG adapter).
 *
 * One presentation of a card gives one tap: the card is read when it appears in the field and is not read again until it has left
 * it. The conductor screen's own debounce (`TapDebouncer`) still guards a second tap of the same card a moment later.
 *
 * It copes with: no reader, a reader without permission (asks once, and again only when the person asks), a refused permission,
 * the reader being unplugged or replugged at any moment, a card taken away during a read, a reader that errors or answers with
 * garbage (a few bad answers in a row stop the session with a plain-words error and it tries again a little later), and a reader
 * that cannot be opened. Nothing here throws on bad bytes from the transport.
 *
 * Nothing is logged but events and a short UID prefix through [log], which the app only wires in a debug build.
 */
class UsbCcidCardReader(
    private val host: UsbReaderHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = DEFAULT_POLL_MS,
    private val reconnectMillis: Long = DEFAULT_RECONNECT_MS,
    private val log: (String) -> Unit = {},
) : CardReader {

    override val kind = ReaderKind.USB

    private val _status = MutableStateFlow<ReaderStatus>(ReaderStatus.NoReader)
    override val status: StateFlow<ReaderStatus> = _status.asStateFlow()

    private val _taps = MutableSharedFlow<CardTap>(extraBufferCapacity = 16)
    override val taps: SharedFlow<CardTap> = _taps.asSharedFlow()

    private val retrySignal = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch { host.attached.collectLatest { readers -> run(readers) } }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun retry() {
        retrySignal.trySend(Unit)
    }

    private suspend fun run(readers: List<UsbReaderInfo>) {
        val info = readers.firstOrNull()
        if (info == null) {
            _status.value = ReaderStatus.NoReader
            return
        }
        while (currentCoroutineContext().isActive) {
            if (!host.hasPermission(info.id)) {
                _status.value = ReaderStatus.PermissionNeeded(denied = false)
                log("usb permission requested")
                if (!host.requestPermission(info.id)) {
                    _status.value = ReaderStatus.PermissionNeeded(denied = true)
                    log("usb permission refused")
                    retrySignal.receive() // not asked again until the person asks
                    continue
                }
            }
            session(info)
            // The session ended on a fault: wait a little (or until asked), then open the reader again.
            withTimeoutOrNull(reconnectMillis) { retrySignal.receive() }
        }
    }

    /** One opened reader, until it faults. Returns only on a fault; unplugging cancels it from outside. */
    private suspend fun session(info: UsbReaderInfo) {
        val transport = withContext(io) { host.open(info.id) }
        if (transport == null) {
            _status.value = ReaderStatus.Error("The USB reader could not be opened. Unplug it, plug it in again and allow it when asked.")
            log("usb open failed")
            return
        }
        try {
            val client = CcidClient(transport)
            _status.value = ReaderStatus.Ready
            var handled = false
            var tries = 0
            var faults = 0
            while (currentCoroutineContext().isActive) {
                when (val slot = withContext(io) { client.slotStatus() }) {
                    is CcidResponse.Ok -> {
                        if (slot.iccStatus == Ccid.ICC_ABSENT) {
                            handled = false
                            tries = 0
                            faults = 0
                        } else if (!handled) {
                            when (val read = withContext(io) { client.readUid() }) {
                                is UidRead.Uid -> {
                                    faults = 0
                                    handled = true
                                    log("tap uid=${prefix(read.bytes)}")
                                    _taps.emit(CardTap(read.bytes, clock(), ReaderKind.USB))
                                }
                                UidRead.CardGone -> {
                                    faults = 0
                                    if (++tries >= MAX_TRIES) handled = true
                                }
                                is UidRead.Rejected -> {
                                    faults = 0
                                    handled = true // a card this app cannot use: not retried until it is taken away
                                    log("card refused: ${read.why}")
                                }
                                // The status answered but the read failed: only a read that works clears the count, or a reader that
                                // answers its status and fails every read would never be given up on.
                                UidRead.ReaderFault -> faults++
                            }
                        } else {
                            faults = 0
                        }
                    }
                    else -> faults++
                }
                if (faults >= MAX_FAULTS) {
                    _status.value = ReaderStatus.Error("The USB reader stopped answering. Unplug it and plug it in again.")
                    log("usb reader fault")
                    return
                }
                delay(pollMillis)
            }
        } finally {
            withContext(NonCancellable) { runCatching { transport.close() } }
        }
    }

    private fun prefix(uid: ByteArray) = uid.take(2).joinToString("") { "%02X".format(it) } + ".."

    companion object {
        const val DEFAULT_POLL_MS = 150L
        const val DEFAULT_RECONNECT_MS = 3_000L

        /** Bad answers in a row before the session is given up. One is a glitch; the reader is polled several times a second. */
        const val MAX_FAULTS = 3

        /** Reads of one presentation that lose the card before it is left alone until it is taken away. */
        const val MAX_TRIES = 3
    }
}
