package ph.toms.conductor.ble

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's side of choosing the message layout for a connection (firmware/PROTOCOL.md section 7.1).
 *
 * Every connection starts on layout 1. After connecting and subscribing, the phone writes diag `07 02` and waits for the
 * machine's `87 00 02 02`. The layout in [layout] is whatever the machine last said is in force:
 *
 *  - a reply that says layout 2 is in force switches the parser to layout 2;
 *  - no reply in [replyTimeoutMs] (an older firmware that does not know command 07, or a write that never arrived): layout 1,
 *    which is the layout the machine keeps using for a phone it has not heard from;
 *  - status 1 (unsupported) or 2 (malformed): the machine left its layout unchanged, so the reply's own version is applied.
 *
 * The switch happens **in [onDiagNotify], on the Bluetooth callback thread, the moment the reply arrives**, not in the
 * coroutine that waits for it. The machine uses layout 2 from the instant it sent the reply, and the next notify on the link
 * (a `card_read` one byte longer than before) can be parsed on that same thread before a waiting coroutine resumes; the
 * parser must already be on layout 2 by then. A reply that arrives after [replyTimeoutMs] is applied too: the machine switched
 * when it handled the write, whether or not the phone was still waiting.
 *
 * The choice is per connection: [reset] puts the phone back on layout 1 at every disconnect, exactly as the machine does, and
 * the whole exchange runs again after the next connect. [layout] is what the notify gate frames messages with, so the parser
 * and the machine can never disagree about where a trailer starts.
 */
class RmLayoutNegotiation(private val replyTimeoutMs: Long = REPLY_TIMEOUT_MS) {

    private val _layout = MutableStateFlow(RmProtocol.Layout.V1)

    /** The layout in force on this connection. Layout 1 until the machine says layout 2 is in force. */
    val layout: StateFlow<RmProtocol.Layout> = _layout.asStateFlow()

    private val waiting = AtomicReference<CompletableDeferred<RmProtocol.WireReply>?>(null)

    /** A new connection (or a disconnect): back to layout 1, nothing waiting. */
    fun reset() {
        _layout.value = RmProtocol.Layout.V1
        waiting.getAndSet(null)?.cancel()
    }

    /**
     * A diag notify arrived. A layout reply is applied immediately (see the class note); any other diag notify is ignored here.
     */
    fun onDiagNotify(data: ByteArray) {
        val reply = RmProtocol.decodeWireReply(data) ?: return
        _layout.value = when (reply.versionInForce) {
            RmProtocol.Layout.V2.version -> RmProtocol.Layout.V2
            else -> RmProtocol.Layout.V1
        }
        waiting.get()?.complete(reply)
    }

    /**
     * Writes the layout choice with [write] (diag `07 02`; false when the write could not start) and waits for the answer.
     * Returns the layout in force afterwards: layout 2 only when the machine said so.
     */
    suspend fun negotiate(write: suspend (ByteArray) -> Boolean): RmProtocol.Layout {
        val deferred = CompletableDeferred<RmProtocol.WireReply>()
        waiting.set(deferred)
        try {
            if (write(RmProtocol.encodeWireSelect(RmProtocol.Layout.V2))) {
                withTimeoutOrNull(replyTimeoutMs) { deferred.await() }
            }
        } finally {
            waiting.compareAndSet(deferred, null)
        }
        // Whatever onDiagNotify applied stands; with no reply the layout is still the one the connection started on.
        return _layout.value
    }

    companion object {
        /** How long the phone waits for `87 ...`; the machine answers within a connection interval or two. */
        const val REPLY_TIMEOUT_MS = 3_000L
    }
}
