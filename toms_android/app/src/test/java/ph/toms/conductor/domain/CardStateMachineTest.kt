package ph.toms.conductor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStateMachineTest {

    private fun ok(s: CardState, e: CardEvent): CardState =
        (CardStateMachine.apply(s, e) as Transition.Ok).state

    @Test fun `happy path paid trip`() {
        var s = CardState.AVAILABLE
        s = ok(s, CardEvent.ASSIGN); assertEquals(CardState.ASSIGNED_UNPAID, s)
        s = ok(s, CardEvent.MARK_PAID); assertEquals(CardState.ASSIGNED_PAID, s)
        s = ok(s, CardEvent.RETURNED_AT_TERMINAL); assertEquals(CardState.RETURNED, s)
        s = ok(s, CardEvent.RELEASE_RETURNED); assertEquals(CardState.AVAILABLE, s)
    }

    @Test fun `unpaid return raises alarm state until conductor resolves`() {
        var s = ok(CardState.ASSIGNED_UNPAID, CardEvent.RETURNED_AT_TERMINAL)
        assertEquals(CardState.RETURNED_UNPAID, s)
        s = ok(s, CardEvent.CONDUCTOR_RESOLVE)
        assertEquals(CardState.AVAILABLE, s)
    }

    @Test fun `lost card is reconciled back to available`() {
        val lost = ok(CardState.ASSIGNED_PAID, CardEvent.MARK_LOST)
        assertEquals(CardState.LOST, lost)
        assertEquals(CardState.AVAILABLE, ok(lost, CardEvent.RECONCILE_LOST))
    }

    @Test fun `second tap on every non available card is blocked`() {
        for (s in CardState.entries.filter { it != CardState.AVAILABLE }) {
            val r = CardStateMachine.apply(s, CardEvent.ASSIGN)
            assertTrue("$s", r is Transition.Rejected)
            assertEquals(RejectReason.CARD_NOT_AVAILABLE, (r as Transition.Rejected).reason)
        }
    }

    @Test fun `release unpaid frees an assigned unpaid card and nothing else`() {
        assertEquals(CardState.AVAILABLE, ok(CardState.ASSIGNED_UNPAID, CardEvent.CONDUCTOR_RELEASE_UNPAID))
        for (s in CardState.entries.filter { it != CardState.ASSIGNED_UNPAID }) {
            val r = CardStateMachine.apply(s, CardEvent.CONDUCTOR_RELEASE_UNPAID)
            assertTrue("$s must reject a release", r is Transition.Rejected)
            assertEquals(RejectReason.INVALID_TRANSITION, (r as Transition.Rejected).reason)
        }
    }

    @Test fun `release is the only way from assigned unpaid to available without a payment or a terminal read`() {
        // Payment and terminal reads are the events that move a card forward through the paid or returned path.
        val paymentOrTerminal = setOf(CardEvent.MARK_PAID, CardEvent.RETURNED_AT_TERMINAL)
        val reachingAvailable = CardEvent.entries.filter {
            (CardStateMachine.apply(CardState.ASSIGNED_UNPAID, it) as? Transition.Ok)?.state == CardState.AVAILABLE
        }
        assertEquals(listOf(CardEvent.CONDUCTOR_RELEASE_UNPAID), reachingAvailable)
        assertTrue(reachingAvailable.none { it in paymentOrTerminal })
        // The terminal read still goes to the alarm state, so a write-off is never mistaken for a return.
        assertEquals(CardState.RETURNED_UNPAID, ok(CardState.ASSIGNED_UNPAID, CardEvent.RETURNED_AT_TERMINAL))
    }

    @Test fun `invalid transitions are rejected and state is unchanged`() {
        val r = CardStateMachine.apply(CardState.AVAILABLE, CardEvent.MARK_PAID)
        assertEquals(RejectReason.INVALID_TRANSITION, (r as Transition.Rejected).reason)
        assertTrue(CardStateMachine.apply(CardState.RETURNED, CardEvent.MARK_PAID) is Transition.Rejected)
        assertTrue(CardStateMachine.apply(CardState.RETURNED_UNPAID, CardEvent.RELEASE_RETURNED) is Transition.Rejected)
    }
}

class TapDebouncerTest {

    @Test fun `same card inside window is dropped, after window accepted`() {
        val d = TapDebouncer(windowMillis = 1000)
        assertTrue(d.accept("04:AA:BB:CC", 0))
        assertFalse(d.accept("04:AA:BB:CC", 500))
        assertTrue(d.accept("04:AA:BB:CC", 1000))
    }

    @Test fun `uid formatting does not defeat the debounce`() {
        val d = TapDebouncer(windowMillis = 1000)
        assertTrue(d.accept("04:aa:bb:cc", 0))
        assertFalse(d.accept("04AABBCC", 100))
    }

    @Test fun `different cards are independent`() {
        val d = TapDebouncer(windowMillis = 1000)
        assertTrue(d.accept("04:11:11:11", 0))
        assertTrue(d.accept("04:22:22:22", 10))
    }
}
