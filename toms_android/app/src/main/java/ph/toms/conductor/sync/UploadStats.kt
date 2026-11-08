package ph.toms.conductor.sync

import javax.inject.Inject
import javax.inject.Singleton

/** Upload counters accumulated between two device_status writes. */
data class UploadCounters(val attempts: Long, val failures: Long)

/**
 * Accumulates upload attempts and failures from the outbox flusher. [drain] returns what accumulated
 * since the previous call (the device_status telemetry writer calls it at every write) and resets the
 * counters, so each status reports only what happened since the last one.
 */
@Singleton
class UploadStats @Inject constructor() {
    private var attempts = 0L
    private var failures = 0L

    fun record(attemptedCount: Int, failedCount: Int) {
        attempts += attemptedCount
        failures += failedCount
    }

    fun drain(): UploadCounters {
        val snapshot = UploadCounters(attempts, failures)
        attempts = 0
        failures = 0
        return snapshot
    }
}
