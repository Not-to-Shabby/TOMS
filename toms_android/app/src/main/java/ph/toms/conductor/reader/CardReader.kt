package ph.toms.conductor.reader

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Which kind of reader produced a tap. */
enum class ReaderKind { PHONE_NFC, USB }

/** What the conductor picked in More. [AUTO] uses a USB reader when one is attached and working, the phone's NFC otherwise. */
enum class ReaderChoice { PHONE_NFC, USB, AUTO }

/** What a reader can say about itself. The reasons are plain words for the conductor, never a code or a class name. */
sealed interface ReaderStatus {
    data object Ready : ReaderStatus

    /** Nothing to read with: no USB reader plugged in, or no NFC chip in this phone. */
    data object NoReader : ReaderStatus

    /** A USB reader is attached but Android has not let the app use it. [denied] is true after the person said no. */
    data class PermissionNeeded(val denied: Boolean = false) : ReaderStatus

    data class Error(val reason: String) : ReaderStatus
}

/** One presented passenger card: only its UID (the card stores nothing else the app reads). [techList] is for the debug list on More. */
class CardTap(
    val uid: ByteArray,
    val atMillis: Long,
    val source: ReaderKind,
    val techList: List<String> = emptyList(),
)

/**
 * Something that reads a passenger card's UID. The boarding logic only sees [taps]; which reader produced them, and how, is not its
 * concern. A reader reads only between [start] and [stop].
 */
interface CardReader {
    val kind: ReaderKind
    val status: StateFlow<ReaderStatus>
    val taps: SharedFlow<CardTap>

    fun start()
    fun stop()

    /** The person asked to try again (for a USB reader that was refused, or stopped answering). Most readers have nothing to retry. */
    fun retry() {}
}
