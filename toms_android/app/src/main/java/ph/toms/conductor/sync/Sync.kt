package ph.toms.conductor.sync

import ph.toms.conductor.data.db.OutboxDao
import ph.toms.conductor.data.db.PendingEvent

sealed interface UploadResult {
    /**
     * The server acknowledged these event IDs. Duplicates it already had must be included.
     * [rejected] maps the IDs the server refused to its reason. A refused event will be refused again,
     * so it is parked instead of retried.
     */
    data class Acked(val eventIds: Set<String>, val rejected: Map<String, String> = emptyMap()) : UploadResult
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

data class FlushReport(val attempted: Int, val delivered: Int, val failure: String?, val parked: Int = 0)

class OutboxFlusher(
    private val outbox: OutboxDao,
    private val uploader: EventUploader,
    private val batchSize: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Sends pending events oldest first. Only IDs the server acknowledges are marked delivered,
     * so a partial or lost response never drops an event; the rest are retried and the server
     * dedups on event_id. An event the server rejects is parked with its reason: it is kept, but it no
     * longer blocks the events behind it or counts as waiting to upload.
     */
    suspend fun flush(): FlushReport {
        var attempted = 0
        var delivered = 0
        var parked = 0
        while (true) {
            val batch = outbox.pending(batchSize)
            if (batch.isEmpty()) return FlushReport(attempted, delivered, null, parked)
            val ids = batch.map { it.eventId }
            attempted += ids.size
            when (val r = uploader.upload(batch)) {
                is UploadResult.Failed -> {
                    outbox.markAttempt(ids, clock())
                    return FlushReport(attempted, delivered, r.reason, parked)
                }
                is UploadResult.Acked -> {
                    val acked = ids.filter { it in r.eventIds }
                    val refused = ids.filter { it in r.rejected && it !in acked }
                    if (acked.isEmpty() && refused.isEmpty()) {
                        outbox.markAttempt(ids, clock())
                        return FlushReport(attempted, delivered, "server acked none of the batch", parked)
                    }
                    if (acked.isNotEmpty()) {
                        outbox.markDelivered(acked, uploader.channel, clock())
                        delivered += acked.size
                    }
                    refused.forEach { outbox.markParked(listOf(it), r.rejected.getValue(it), clock()) }
                    parked += refused.size
                    val unhandled = ids - acked.toSet() - refused.toSet()
                    if (unhandled.isNotEmpty()) {
                        outbox.markAttempt(unhandled, clock())
                        return FlushReport(attempted, delivered, "partial ack", parked)
                    }
                }
            }
        }
    }
}
