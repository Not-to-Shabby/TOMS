package ph.toms.conductor.sync

import ph.toms.conductor.data.db.OutboxDao
import ph.toms.conductor.data.db.PendingEvent

sealed interface UploadResult {
    /** The server acknowledged these event IDs. Duplicates it already had must be included. */
    data class Acked(val eventIds: Set<String>) : UploadResult
    data class Failed(val reason: String) : UploadResult
}

interface EventUploader {
    val channel: String
    suspend fun upload(batch: List<PendingEvent>): UploadResult
}

/** Used until the backend exists (Phase 1.4): every upload fails, so events stay pending. */
class NoBackendUploader : EventUploader {
    override val channel = "data_a"
    override suspend fun upload(batch: List<PendingEvent>) = UploadResult.Failed("no backend configured")
}

data class FlushReport(val attempted: Int, val delivered: Int, val failure: String?)

class OutboxFlusher(
    private val outbox: OutboxDao,
    private val uploader: EventUploader,
    private val batchSize: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Sends pending events oldest first. Only IDs the server acknowledges are marked delivered,
     * so a partial or lost response never drops an event; the rest are retried and the server
     * dedups on event_id.
     */
    suspend fun flush(): FlushReport {
        var attempted = 0
        var delivered = 0
        while (true) {
            val batch = outbox.pending(batchSize)
            if (batch.isEmpty()) return FlushReport(attempted, delivered, null)
            val ids = batch.map { it.eventId }
            attempted += ids.size
            when (val r = uploader.upload(batch)) {
                is UploadResult.Failed -> {
                    outbox.markAttempt(ids, clock())
                    return FlushReport(attempted, delivered, r.reason)
                }
                is UploadResult.Acked -> {
                    val acked = ids.filter { it in r.eventIds }
                    if (acked.isEmpty()) {
                        outbox.markAttempt(ids, clock())
                        return FlushReport(attempted, delivered, "server acked none of the batch")
                    }
                    outbox.markDelivered(acked, uploader.channel, clock())
                    delivered += acked.size
                    val unacked = ids - acked.toSet()
                    if (unacked.isNotEmpty()) {
                        outbox.markAttempt(unacked, clock())
                        return FlushReport(attempted, delivered, "partial ack")
                    }
                }
            }
        }
    }
}
