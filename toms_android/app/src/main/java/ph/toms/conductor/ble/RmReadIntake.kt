package ph.toms.conductor.ble

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ph.toms.conductor.data.EventTypes
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.db.RmReadEntity
import ph.toms.conductor.data.db.TomsDatabase

/** A read the machine flushed from its flash log because the phone was away when it was made. */
@Serializable
data class RmStoredReadPayload(val machineId: String, val readSeq: Long, val buffered: Int, val atMillis: Long)

/** The phone answered a paid check late (or never), so the machine played the unverified tone. */
@Serializable
data class RmUnverifiedPayload(val machineId: String, val checkSeq: Long, val delayMs: Long, val reason: String)

/** The read_seq jumped ahead after a lost card: the machine's log overflowed and reads are gone. */
@Serializable
data class RmLostGapPayload(val machineId: String, val lastSeq: Long, val readSeq: Long, val gap: Long, val lastState: Int)

/**
 * Turns verified Return Machine card reads into durable phone state:
 *
 *  - every read is stored under (machine id, read_seq) — a resent read is a no-op insert;
 *  - the read ack goes out only after the Room transaction that stored the read has committed, with
 *    the highest stored read_seq, so the machine frees exactly what the phone keeps;
 *  - a stored read (the machine flushed it from its flash log) is recorded as an `rm_stored_read`
 *    event, one per read, so the office sees reads the machine made while the phone was away;
 *  - when the previous read was of a lost card and the read_seq skips ahead, an `rm_lost_gap` event
 *    records the reads the machine could no longer keep;
 *  - a paid check the phone answers late — or never, because the link dropped — is recorded as an
 *    `rm_unverified` event: the machine played the unverified tone for that read.
 *
 * Events upload through the outbox like any other event. Card UIDs stay in the local rm_reads table;
 * the uploaded payloads carry machine ids, sequence numbers and counts only.
 */
interface RmReadIntake {
    fun onConnected(machineId: String)
    fun onDisconnected()
    fun onHeartbeat(heartbeat: RmProtocol.Heartbeat)
    fun onCardRead(read: RmProtocol.CardRead)
    fun onPaidCheck(checkSeq: Long)
    fun onPaidReplySent(checkSeq: Long)
}

@Singleton
class RealRmReadIntake @Inject constructor(
    private val trips: TripRepository,
    private val db: TomsDatabase,
    private val link: ReturnMachineLink,
) : RmReadIntake {

    private val json = Json { encodeDefaults = true }

    // One thread for the whole intake: onPaidCheck, the reply and the read processing then happen in
    // the order the app called them, so a reply sent after a check can never miss it.
    private val singleThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "rm-intake") }
    private val scope = CoroutineScope(SupervisorJob() + singleThread.asCoroutineDispatcher())
    private val mutex = Mutex()
    private val pendingChecks = LinkedHashMap<Long, Job>()

    /** The connected machine, by its Bluetooth address: the id the trust store and rm_reads are keyed by. */
    @Volatile private var machineId: String? = null

    /** The machine's last reported buffered_reads: > 0 means reads are arriving from its flash log. */
    @Volatile private var lastBuffered = 0

    /** Highest read_seq waiting for an ack, and the highest one already sent. */
    private var pendingAckMax = 0L
    private var lastAckSent = 0L

    /** The paid-check window the firmware waits (default 1500 ms); a test can shorten it. */
    var paidTimeoutMs: Long = DEFAULT_PAID_TIMEOUT_MS

    override fun onConnected(machineId: String) {
        this.machineId = machineId
    }

    override fun onDisconnected() {
        // The machine played the unverified tone for every check still waiting when the link died.
        scope.launch {
            mutex.withLock {
                val id = machineId ?: return@withLock
                val now = System.currentTimeMillis()
                for ((checkSeq, timer) in pendingChecks) {
                    timer.cancel()
                    trips.recordMachineEvent(
                        EventTypes.RM_UNVERIFIED,
                        json.encodeToString(RmUnverifiedPayload(id, checkSeq, now, REASON_DISCONNECTED)),
                        trips.openShift()?.id,
                    )
                }
                pendingChecks.clear()
            }
        }
    }

    override fun onHeartbeat(heartbeat: RmProtocol.Heartbeat) {
        lastBuffered = heartbeat.bufferedReads
    }

    override fun onPaidCheck(checkSeq: Long) {
        scope.launch {
            val timer = scope.launch {
                delay(paidTimeoutMs)
                mutex.withLock {
                    // Still pending after the window: the machine stopped waiting and played the
                    // unverified tone, whatever the conductor did since.
                    if (pendingChecks.remove(checkSeq) != null) {
                        val id = machineId ?: return@withLock
                        trips.recordMachineEvent(
                            EventTypes.RM_UNVERIFIED,
                            json.encodeToString(RmUnverifiedPayload(id, checkSeq, paidTimeoutMs, REASON_TIMEOUT)),
                            trips.openShift()?.id,
                        )
                    }
                }
            }
            mutex.withLock { pendingChecks[checkSeq] = timer }
        }
    }

    override fun onPaidReplySent(checkSeq: Long) {
        scope.launch {
            mutex.withLock { pendingChecks.remove(checkSeq)?.cancel() }
        }
    }

    override fun onCardRead(read: RmProtocol.CardRead) {
        scope.launch {
            mutex.withLock {
                val id = machineId ?: return@withLock
                val arrival = System.currentTimeMillis()
                val readAt = if (read.timestamp > 0) read.timestamp * 1000L else arrival
                // A stored read either arrives while the machine reports a pending log, or plainly
                // happened long before the machine delivered it.
                val stored = lastBuffered > 0 || arrival - readAt > STORED_READ_WINDOW_MS
                val shiftId = trips.openShift()?.id
                var lostGap: RmLostGapPayload? = null
                var storedRead: RmStoredReadPayload? = null

                db.withTransaction {
                    val prevSeq = db.rmReads().maxReadSeq(id)
                    val prevState = db.rmReads().lastStateCached(id)
                    val inserted = db.rmReads().insert(
                        RmReadEntity(
                            machineId = id, readSeq = read.readSeq, uidHex = read.uidHex,
                            atype = read.atype, stateCached = read.stateCached,
                            readAtMillis = readAt, arrivalMillis = arrival, stored = stored,
                        ),
                    ) > 0

                    if (inserted) {
                        // A lost card whose next read_seq skips ahead: the machine's log overflowed and
                        // the reads in between are gone. Recorded once, at the first read after the gap.
                        val gap = prevSeq?.let { read.readSeq - it - 1 } ?: 0
                        if (prevSeq != null && gap > 0 && prevState == RmProtocol.STATUS_LOST) {
                            lostGap = RmLostGapPayload(id, prevSeq, read.readSeq, gap, prevState)
                        }
                        if (stored) {
                            storedRead = RmStoredReadPayload(id, read.readSeq, lastBuffered, arrival)
                        }
                        lostGap?.let { trips.appendMachineEvent(EventTypes.RM_LOST_GAP, json.encodeToString(it), shiftId) }
                        storedRead?.let { trips.appendMachineEvent(EventTypes.RM_STORED_READ, json.encodeToString(it), shiftId) }
                    }
                }
                // The read row and its events are committed now — only then does the machine learn
                // it may free anything.
                if (lostGap != null || storedRead != null) trips.kick()
                ackUpTo(read.readSeq)
            }
        }
    }

    /** Ack after the commit: the machine frees every read up to the highest one the phone keeps. A
     *  resent read is acked again — the machine keeps resending until it sees an ack for that seq. */
    private fun ackUpTo(readSeq: Long) {
        if (readSeq > pendingAckMax) pendingAckMax = readSeq
        lastAckSent = pendingAckMax
        link.sendReadAck(pendingAckMax)
    }

    companion object {
        const val DEFAULT_PAID_TIMEOUT_MS = 1_500L

        /** A read this much older than its delivery came from the machine's flash log. */
        const val STORED_READ_WINDOW_MS = 10_000L
        const val REASON_TIMEOUT = "timeout"
        const val REASON_DISCONNECTED = "disconnected"
    }
}
