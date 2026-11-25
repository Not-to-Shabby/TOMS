package ph.toms.conductor.reader

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ph.toms.conductor.nfc.CardRead
import ph.toms.conductor.nfc.toUidHex

/** The tap in the shape the boarding logic already takes. */
fun CardTap.toCardRead(): CardRead = CardRead(uid.toUidHex(), techList, atMillis)

/**
 * What the screens say about the card reader: which one is in use, its state, and (when [AUTO][ReaderChoice.AUTO] fell back to the
 * phone's NFC because the USB reader could not be used) why the USB one is not.
 */
data class ReaderSummary(
    val choice: ReaderChoice,
    val active: ReaderKind,
    val status: ReaderStatus,
    val usbStatus: ReaderStatus,
) {
    /** The name of the reader in use, in words. */
    val name: String get() = if (active == ReaderKind.USB) "USB reader" else "Phone NFC"

    /** The short text for the chip on the conductor screen. */
    val chip: String
        get() = when (active) {
            ReaderKind.PHONE_NFC -> when (status) {
                ReaderStatus.Ready -> "Reader ready"
                ReaderStatus.NoReader -> "No NFC"
                is ReaderStatus.Error -> "NFC is off"
                is ReaderStatus.PermissionNeeded -> "NFC is off"
            }
            ReaderKind.USB -> when (status) {
                ReaderStatus.Ready -> "USB reader ready"
                ReaderStatus.NoReader -> "No USB reader"
                is ReaderStatus.PermissionNeeded -> if (status.denied) "USB access refused" else "USB: allow access"
                is ReaderStatus.Error -> "USB reader error"
            }
        }

    /** True when the chip should be shown as working. */
    val working: Boolean get() = status == ReaderStatus.Ready

    /** One or two plain sentences for More. */
    val detail: String
        get() {
            val own = when (val s = status) {
                ReaderStatus.Ready ->
                    if (active == ReaderKind.USB) "The USB reader is ready. Present a card to it." else "The phone's NFC is ready. Tap a card on the back of the phone."
                ReaderStatus.NoReader ->
                    if (active == ReaderKind.USB) "No USB reader is plugged in." else "This phone has no NFC."
                is ReaderStatus.PermissionNeeded ->
                    if (s.denied) "Android was told not to let TOMS use the USB reader. Tap \"Try again\" and choose OK when it asks."
                    else "Android is asking whether TOMS may use the USB reader. Choose OK."
                is ReaderStatus.Error -> s.reason
            }
            val fellBack = choice == ReaderChoice.AUTO && active == ReaderKind.PHONE_NFC && usbStatus != ReaderStatus.NoReader
            return if (fellBack) "$own The USB reader is not being used: ${usbNote()}" else own
        }

    private fun usbNote(): String = when (val s = usbStatus) {
        is ReaderStatus.PermissionNeeded -> "Android has not let TOMS use it."
        is ReaderStatus.Error -> s.reason
        else -> "it is not ready."
    }

    /** True when a "Try again" button helps: a USB reader that was refused or stopped answering. */
    val canRetry: Boolean
        get() = (active == ReaderKind.USB && (status is ReaderStatus.Error || (status as? ReaderStatus.PermissionNeeded)?.denied == true)) ||
            (usbStatus is ReaderStatus.Error || (usbStatus as? ReaderStatus.PermissionNeeded)?.denied == true)
}

/**
 * Chooses which one reader is used and gives the app a single stream of taps.
 *
 * The rule: [ReaderChoice.PHONE_NFC] and [ReaderChoice.USB] use only that reader. [ReaderChoice.AUTO] uses the USB reader while one
 * is plugged in and allowed (or waiting for the person to allow it), and the phone's NFC otherwise, switching by itself when a reader
 * is plugged or unplugged. Only the chosen reader's taps are passed on and only that reader is reading, so a card is never read twice
 * by two readers; the boarding logic's own tap debounce still guards the same card presented twice in a moment.
 *
 * A reader reads only between [start] and [stop], which the screen ties to being in the foreground, as reader mode always was.
 */
class CardReaderManager(
    private val nfc: CardReader,
    private val usb: CardReader,
    private val choice: StateFlow<ReaderChoice>,
    private val scope: CoroutineScope,
) {

    private val _taps = MutableSharedFlow<CardTap>(extraBufferCapacity = 32)
    val taps: SharedFlow<CardTap> = _taps.asSharedFlow()

    private val _summary = MutableStateFlow(compute(choice.value, usb.status.value, nfc.status.value))
    val summary: StateFlow<ReaderSummary> = _summary.asStateFlow()

    @Volatile private var active: ReaderKind = _summary.value.active
    private var job: Job? = null

    /** The foreground activity, which the phone's NFC needs (reader mode belongs to the visible app). */
    fun bindActivity(activity: Activity) {
        (nfc as? NfcCardReader)?.bind(activity)
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { nfc.taps.collect { if (active == ReaderKind.PHONE_NFC) _taps.emit(it) } }
            launch { usb.taps.collect { if (active == ReaderKind.USB) _taps.emit(it) } }
            combine(choice, usb.status, nfc.status) { c, u, n -> compute(c, u, n) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        nfc.stop()
        usb.stop()
    }

    /** "Try again" on More. */
    fun retry() = usb.retry()

    private fun apply(summary: ReaderSummary) {
        active = summary.active
        // The USB reader runs whenever it may be chosen (it must watch for a plug-in, and ask for permission); the phone's NFC runs only when it is in use.
        if (summary.choice == ReaderChoice.PHONE_NFC) usb.stop() else usb.start()
        if (summary.active == ReaderKind.PHONE_NFC) nfc.start() else nfc.stop()
        _summary.value = summary
    }

    companion object {
        /** Pure, so every combination is a plain test. */
        fun compute(choice: ReaderChoice, usb: ReaderStatus, nfc: ReaderStatus): ReaderSummary {
            val active = when (choice) {
                ReaderChoice.PHONE_NFC -> ReaderKind.PHONE_NFC
                ReaderChoice.USB -> ReaderKind.USB
                ReaderChoice.AUTO ->
                    if (usb == ReaderStatus.Ready || (usb is ReaderStatus.PermissionNeeded && !usb.denied)) ReaderKind.USB else ReaderKind.PHONE_NFC
            }
            val status = if (active == ReaderKind.USB) usb else nfc
            return ReaderSummary(choice, active, status, usb)
        }
    }
}
