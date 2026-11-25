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
import kotlinx.coroutines.launch
import ph.toms.conductor.nfc.NfcAvailability
import ph.toms.conductor.nfc.NfcReader

/**
 * The phone's own NFC (reader mode, ISO 14443-A) behind [CardReader]. The reader mode code itself is [NfcReader], unchanged: this
 * class only adapts it, so what it reads and how is exactly what it was. It needs the visible activity ([bind]) because reader mode
 * belongs to the foreground app, and it reads only between [start] and [stop].
 */
class NfcCardReader(
    private val nfc: NfcReader,
    private val scope: CoroutineScope,
) : CardReader {

    override val kind = ReaderKind.PHONE_NFC

    private val _status = MutableStateFlow<ReaderStatus>(ReaderStatus.NoReader)
    override val status: StateFlow<ReaderStatus> = _status.asStateFlow()

    private val _taps = MutableSharedFlow<CardTap>(extraBufferCapacity = 16)
    override val taps: SharedFlow<CardTap> = _taps.asSharedFlow()

    @Volatile private var activity: Activity? = null
    private var job: Job? = null

    fun bind(activity: Activity) {
        this.activity = activity
        refreshStatus()
    }

    private fun refreshStatus() {
        val a = activity ?: return
        _status.value = when (nfc.availability(a)) {
            NfcAvailability.Ready -> ReaderStatus.Ready
            NfcAvailability.Disabled -> ReaderStatus.Error("NFC is switched off. Turn it on in Android settings, or plug in a USB reader.")
            NfcAvailability.Unsupported -> ReaderStatus.NoReader
        }
    }

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        val a = activity ?: return
        refreshStatus()
        if (_status.value != ReaderStatus.Ready) return
        job = scope.launch {
            nfc.reads(a).collect { read ->
                val uid = parseUid(read.uidHex) ?: return@collect
                _taps.emit(CardTap(uid, read.readAtMillis, ReaderKind.PHONE_NFC, read.techList))
            }
        }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun parseUid(hex: String): ByteArray? = runCatching {
        hex.split(':').map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
