package ph.toms.conductor.domain

enum class CardState { AVAILABLE, ASSIGNED_UNPAID, ASSIGNED_PAID, RETURNED, RETURNED_UNPAID, LOST }

enum class CardEvent {
    /** Conductor taps an available card to start a trip. */
    ASSIGN,
    MARK_PAID,
    /** The Return Terminal (or the manual return button) reports the card. */
    RETURNED_AT_TERMINAL,
    /** Conductor clears an unpaid-return alarm. */
    CONDUCTOR_RESOLVE,
    /** Closed trip's card goes back into circulation. */
    RELEASE_RETURNED,
    MARK_LOST,
    RECONCILE_LOST,
}

enum class RejectReason { CARD_NOT_AVAILABLE, INVALID_TRANSITION }

sealed interface Transition {
    data class Ok(val state: CardState) : Transition
    data class Rejected(val reason: RejectReason, val from: CardState, val event: CardEvent) : Transition
}

object CardStateMachine {

    fun apply(state: CardState, event: CardEvent): Transition {
        val next = when (state) {
            CardState.AVAILABLE -> when (event) {
                CardEvent.ASSIGN -> CardState.ASSIGNED_UNPAID
                else -> null
            }
            CardState.ASSIGNED_UNPAID -> when (event) {
                CardEvent.MARK_PAID -> CardState.ASSIGNED_PAID
                CardEvent.RETURNED_AT_TERMINAL -> CardState.RETURNED_UNPAID
                CardEvent.MARK_LOST -> CardState.LOST
                else -> null
            }
            CardState.ASSIGNED_PAID -> when (event) {
                CardEvent.RETURNED_AT_TERMINAL -> CardState.RETURNED
                CardEvent.MARK_LOST -> CardState.LOST
                else -> null
            }
            CardState.RETURNED -> when (event) {
                CardEvent.RELEASE_RETURNED -> CardState.AVAILABLE
                else -> null
            }
            CardState.RETURNED_UNPAID -> when (event) {
                CardEvent.CONDUCTOR_RESOLVE -> CardState.AVAILABLE
                else -> null
            }
            CardState.LOST -> when (event) {
                CardEvent.RECONCILE_LOST -> CardState.AVAILABLE
                else -> null
            }
        }
        if (next != null) return Transition.Ok(next)
        val reason = if (event == CardEvent.ASSIGN) RejectReason.CARD_NOT_AVAILABLE else RejectReason.INVALID_TRANSITION
        return Transition.Rejected(reason, state, event)
    }
}

/** Drops repeated reads of the same card inside [windowMillis]; a different card is never dropped. */
class TapDebouncer(private val windowMillis: Long = 1500) {
    private val lastAccepted = HashMap<String, Long>()

    fun accept(uid: String, nowMillis: Long): Boolean {
        val key = ApprovedCards.normalizeUid(uid)
        val last = lastAccepted[key]
        if (last != null && nowMillis - last < windowMillis) return false
        lastAccepted[key] = nowMillis
        return true
    }
}
