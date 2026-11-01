package ph.toms.ota

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** The phone's side of one Bluetooth link to the machine. Android's GATT is behind this. */
interface OtaTransport {
    /** Negotiated ATT MTU. */
    val mtu: Int

    /** Notifications from ota_control. Closed with [LinkLost] when the link drops. */
    val events: ReceiveChannel<OtaEvent>

    /** Write to ota_control and wait for the machine's ATT response. */
    suspend fun writeControl(data: ByteArray)

    /** Write to ota_data without response; returns when the stack can take the next one. */
    suspend fun writeData(data: ByteArray)

    fun close()
}

class LinkLost(message: String) : IOException(message)

class OtaRefused(val status: OtaStatus, val offset: Long = 0) :
    Exception("The machine said no: ${status.meaning}")

sealed interface OtaOutcome {
    /** The machine accepted the image and is rebooting into it on trial. */
    data class Installed(val machineRunning: Version) : OtaOutcome

    /** The link dropped after FINISH: the machine may or may not have started the swap. */
    data object Unknown : OtaOutcome
}

class OtaUploader(
    private val connect: suspend () -> OtaTransport,
    private val log: (String) -> Unit = {},
    private val maxReconnects: Int = 5,
    private val reconnectDelayMs: Long = 3000,
    /** START makes the machine erase the update slot, which takes a while. */
    private val startTimeoutMs: Long = 120_000,
    private val finishTimeoutMs: Long = 60_000,
) {
    /** Offset the machine asked to go back to, picked up from a resync event. */
    private var wanted: Long? = null

    suspend fun upload(image: OtaImage, onProgress: (sent: Long, total: Long) -> Unit): OtaOutcome {
        var reconnects = 0
        var t = connect()
        var offset = try {
            start(t, image)
        } catch (e: LinkLost) {
            t.close()
            throw e
        }
        try {
            while (true) {
                try {
                    offset = stream(t, image, offset, onProgress)
                    log("all ${image.size} bytes sent; asking the machine to check and install")
                    val fin = finish(t)
                    when {
                        fin.status == OtaStatus.OFFSET -> {
                            log("machine is missing data after ${fin.offset}; sending the rest again")
                            offset = fin.offset
                        }
                        fin.status == OtaStatus.OK -> return OtaOutcome.Installed(fin.running)
                        else -> throw OtaRefused(fin.status, fin.offset)
                    }
                } catch (e: LinkLost) {
                    if (finishAsked) {
                        log("link lost after FINISH: cannot tell whether the machine installed it")
                        return OtaOutcome.Unknown
                    }
                    if (++reconnects > maxReconnects) throw e
                    log("link lost (${e.message}); reconnecting ($reconnects of $maxReconnects)")
                    t.close()
                    delay(reconnectDelayMs)
                    t = connect()
                    offset = start(t, image)
                    log("reconnected; the machine already has $offset bytes")
                }
            }
        } finally {
            t.close()
        }
    }

    private var finishAsked = false

    private suspend fun start(t: OtaTransport, image: OtaImage): Long {
        finishAsked = false
        wanted = null
        log("START: ${image.size} bytes, version ${image.version}; the machine erases its update slot first")
        t.writeControl(Ota.startFrame(image.size, image.sha256))
        val r = awaitReply(t, Ota.OP_START, startTimeoutMs)
        if (r.status != OtaStatus.OK) throw OtaRefused(r.status, r.offset)
        if (!r.confirmed) throw OtaRefused(OtaStatus.BUSY)
        log("machine runs ${r.running}; ready at offset ${r.offset}")
        return r.offset
    }

    private suspend fun stream(
        t: OtaTransport, image: OtaImage, from: Long, onProgress: (Long, Long) -> Unit,
    ): Long {
        val chunk = Ota.chunkSizeFor(t.mtu)
        log("sending from $from in ${chunk}-byte chunks (MTU ${t.mtu})")
        var offset = from
        val total = image.size.toLong()
        while (offset < total) {
            drain(t, onProgress, total)
            wanted?.let {
                log("machine asked to resend from $it (we were at $offset)")
                offset = it
                wanted = null
            }
            if (offset >= total) break
            val end = minOf(total, offset + chunk).toInt()
            t.writeData(Ota.chunkFrame(offset.toInt(), image.bytes.copyOfRange(offset.toInt(), end)))
            offset = end.toLong()
            onProgress(offset, total)
        }
        return offset
    }

    /** Take every notification already waiting, without blocking. */
    private fun drain(t: OtaTransport, onProgress: (Long, Long) -> Unit, total: Long) {
        while (true) {
            val r = t.events.tryReceive()
            if (r.isClosed) throw (r.exceptionOrNull() as? LinkLost ?: LinkLost("link closed"))
            handle(r.getOrNull() ?: return)
        }
    }

    private fun handle(e: OtaEvent) {
        when (e) {
            is OtaEvent.Resync -> wanted = e.offset
            is OtaEvent.Failed -> throw OtaRefused(e.status, e.offset)
            is OtaEvent.Progress, is OtaEvent.Reply -> Unit
        }
    }

    private suspend fun finish(t: OtaTransport): OtaEvent.Reply {
        finishAsked = true
        t.writeControl(Ota.simpleFrame(Ota.OP_FINISH))
        val r = awaitReply(t, Ota.OP_FINISH, finishTimeoutMs)
        if (r.status == OtaStatus.OFFSET) finishAsked = false
        return r
    }

    private suspend fun awaitReply(t: OtaTransport, op: Int, timeoutMs: Long): OtaEvent.Reply {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val e = t.events.receive()
                    if (e is OtaEvent.Reply && e.op == op) return@withTimeout e
                    handle(e)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: ClosedReceiveChannelException) {
            throw LinkLost("link closed")
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                throw LinkLost("no answer from the machine in ${timeoutMs / 1000} s")
            }
            throw e
        }
    }
}
