package ph.toms.conductor.reader

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A reader a test drives directly: set its status, present a card, and see whether it was started or stopped. */
class FakeCardReader(override val kind: ReaderKind, initial: ReaderStatus = ReaderStatus.Ready) : CardReader {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<ReaderStatus> = _status.asStateFlow()

    private val _taps = MutableSharedFlow<CardTap>(extraBufferCapacity = 16)
    override val taps: SharedFlow<CardTap> = _taps.asSharedFlow()

    @Volatile var running = false
    @Volatile var starts = 0
    @Volatile var stops = 0
    @Volatile var retries = 0

    fun setStatus(s: ReaderStatus) { _status.value = s }

    suspend fun present(uid: ByteArray, at: Long = System.currentTimeMillis()) {
        _taps.emit(CardTap(uid, at, kind))
    }

    override fun start() { running = true; starts++ }
    override fun stop() { running = false; stops++ }
    override fun retry() { retries++ }
}
