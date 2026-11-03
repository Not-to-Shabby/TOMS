package ph.toms.conductor.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UplinkManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `initial state is Offline and not online`() = testScope.runTest {
        val coordinator = UplinkCoordinator(this, hysteresisMillis = 3000L)
        assertEquals(UplinkState.Offline, coordinator.state.value)
        assertFalse(coordinator.state.value.isOnline)
    }

    @Test
    fun `unvalidated network moves state to Connecting but does not trigger Online`() = testScope.runTest {
        val coordinator = UplinkCoordinator(this, hysteresisMillis = 3000L)

        coordinator.onNetworkAvailable(isCellular = true, isValidated = false)
        assertEquals(UplinkState.Connecting, coordinator.state.value)

        // Even after time passes, without validation it remains in Connecting
        advanceTimeBy(5000L)
        assertEquals(UplinkState.Connecting, coordinator.state.value)
        assertFalse(coordinator.state.value.isOnline)
    }

    @Test
    fun `validated network waits for hysteresis before transitioning to Online and emitting restored event`() = testScope.runTest {
        val coordinator = UplinkCoordinator(this, hysteresisMillis = 3000L)
        val restoredEvents = mutableListOf<Unit>()
        val job = launch { coordinator.uplinkRestored.toList(restoredEvents) }

        coordinator.onNetworkAvailable(isCellular = true, isValidated = true)
        assertEquals(UplinkState.Connecting, coordinator.state.value)
        assertEquals(0, restoredEvents.size)

        // Advance 2 seconds (less than hysteresis)
        advanceTimeBy(2000L)
        assertEquals(UplinkState.Connecting, coordinator.state.value)
        assertEquals(0, restoredEvents.size)

        // Complete hysteresis (3000ms total)
        advanceTimeBy(1001L)
        assertEquals(UplinkState.Online(isCellular = true), coordinator.state.value)
        assertTrue(coordinator.state.value.isOnline)
        assertEquals(1, restoredEvents.size)

        job.cancel()
    }

    @Test
    fun `network flapping cancels hysteresis timer and never reaches Online`() = testScope.runTest {
        val coordinator = UplinkCoordinator(this, hysteresisMillis = 3000L)
        val restoredEvents = mutableListOf<Unit>()
        val job = launch { coordinator.uplinkRestored.toList(restoredEvents) }

        // 1. Enters momentary coverage
        coordinator.onNetworkAvailable(isCellular = true, isValidated = true)
        assertEquals(UplinkState.Connecting, coordinator.state.value)

        // 2. Drops signal after 1 second
        advanceTimeBy(1000L)
        coordinator.onNetworkLost()
        assertEquals(UplinkState.Offline, coordinator.state.value)

        // 3. Past original 3-second mark
        advanceTimeBy(3000L)
        assertEquals(UplinkState.Offline, coordinator.state.value)
        assertEquals(0, restoredEvents.size)

        // 4. Second transient burst for 1.5 seconds
        coordinator.onNetworkAvailable(isCellular = true, isValidated = true)
        advanceTimeBy(1500L)
        coordinator.onNetworkLost()
        assertEquals(UplinkState.Offline, coordinator.state.value)

        advanceTimeBy(5000L)
        assertEquals(UplinkState.Offline, coordinator.state.value)
        assertEquals(0, restoredEvents.size)

        job.cancel()
    }

    @Test
    fun `reconnection resets full hysteresis window before declaring Online`() = testScope.runTest {
        val coordinator = UplinkCoordinator(this, hysteresisMillis = 3000L)
        val restoredEvents = mutableListOf<Unit>()
        val job = launch { coordinator.uplinkRestored.toList(restoredEvents) }

        // Transient connect and drop
        coordinator.onNetworkAvailable(isCellular = true, isValidated = true)
        advanceTimeBy(2000L)
        coordinator.onNetworkLost()
        assertEquals(UplinkState.Offline, coordinator.state.value)

        // Stable connection begins
        advanceTimeBy(500L)
        coordinator.onNetworkAvailable(isCellular = false, isValidated = true) // e.g. Wi-Fi at depot
        assertEquals(UplinkState.Connecting, coordinator.state.value)

        // 2 seconds later -> still Connecting (only 2s of the new 3s window elapsed)
        advanceTimeBy(2000L)
        assertEquals(UplinkState.Connecting, coordinator.state.value)
        assertEquals(0, restoredEvents.size)

        // Another 1.1s later -> transitions to Online(isCellular = false)
        advanceTimeBy(1100L)
        assertEquals(UplinkState.Online(isCellular = false), coordinator.state.value)
        assertEquals(1, restoredEvents.size)

        job.cancel()
    }

    @Test
    fun `transport change while Online updates cellular flag without re-emitting restored event`() = testScope.runTest {
        val coordinator = UplinkCoordinator(this, hysteresisMillis = 1000L)
        val restoredEvents = mutableListOf<Unit>()
        val job = launch { coordinator.uplinkRestored.toList(restoredEvents) }

        coordinator.onNetworkAvailable(isCellular = true, isValidated = true)
        advanceTimeBy(1500L)
        assertEquals(UplinkState.Online(isCellular = true), coordinator.state.value)
        assertEquals(1, restoredEvents.size)

        // Switched from 4G to Depot Wi-Fi while remaining connected
        coordinator.onNetworkAvailable(isCellular = false, isValidated = true)
        assertEquals(UplinkState.Online(isCellular = false), coordinator.state.value)
        assertEquals(1, restoredEvents.size) // No extra drain trigger on seamless transport handover

        job.cancel()
    }
}
