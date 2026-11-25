package ph.toms.conductor.reader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real [UsbReaderHost]: Android's USB host API (`UsbManager`) for smart-card (CCID) readers.
 *
 *  - **Finding a reader.** Every device Android lists whose interface class is Smart Card (0x0B, USB_CLASS_CSCID) with a bulk-IN and a
 *    bulk-OUT endpoint. Attach and detach are heard through broadcasts registered at run time (the detach one cannot be declared in
 *    the manifest, see the USB host guide), and the list is read once at start in case a reader was already plugged in.
 *  - **Permission.** `UsbManager.requestPermission` with an explicit, mutable broadcast `PendingIntent` for this app: the system adds
 *    the answer to that intent when it sends it, which an immutable one would drop. The permission lasts until the reader is
 *    unplugged unless the person ticks the system dialog's "always" box (which is only offered when the app is a candidate handler
 *    for the device, see the manifest's attach filter).
 *  - **Talking.** Claims the CCID interface and moves the bytes with `bulkTransfer` on the reader thread.
 *
 * Not run on a phone; see documentation/usb_reader.md.
 */
@Singleton
class AndroidUsbReaderHost @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsbReaderHost {

    private val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private val _attached = MutableStateFlow<List<UsbReaderInfo>>(emptyList())
    override val attached: StateFlow<List<UsbReaderInfo>> = _attached.asStateFlow()

    private val devices = ConcurrentHashMap<String, UsbDevice>()
    private val attachSeq = ConcurrentHashMap<String, Int>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
                ACTION_PERMISSION -> {
                    val device = deviceOf(intent) ?: return
                    pending.remove(device.deviceName)?.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
    }

    /** Called when the app starts reading; starts listening for plug and unplug (once). */
    @Synchronized
    fun ensureListening() {
        if (!supported || registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        // Not exported: the permission answer comes from the system to this app's own PendingIntent; attach and detach are system broadcasts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        refresh()
    }

    private fun refresh() {
        val manager = usb ?: return
        val now = manager.deviceList.values.filter { cardReaderInterface(it) != null }
        val known = _attached.value.map { it.id }.toSet()
        devices.clear()
        now.forEach { devices[it.deviceName] = it }
        // A reader that was gone and is back gets a new sequence number, so a quick unplug and replug restarts its session.
        val gone = known - now.map { it.deviceName }.toSet()
        gone.forEach { attachSeq.merge(it, 1) { a, b -> a + b } }
        _attached.value = now.map { d ->
            if (d.deviceName !in known) attachSeq.merge(d.deviceName, 1) { a, b -> a + b }
            UsbReaderInfo(d.deviceName, d.productName ?: "USB reader", d.vendorId, d.productId, attachSeq[d.deviceName] ?: 0)
        }
    }

    override fun hasPermission(id: String): Boolean = devices[id]?.let { usb?.hasPermission(it) } ?: false

    override suspend fun requestPermission(id: String): Boolean {
        val manager = usb ?: return false
        val device = devices[id] ?: return false
        val answer = CompletableDeferred<Boolean>()
        pending[id] = answer
        // Mutable on purpose (the system fills in the answer), and explicit (limited to this app), so Android 14's rule against a mutable
        // implicit PendingIntent does not apply. FLAG_MUTABLE exists from Android 12; older versions have no such flag and are mutable.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            manager.requestPermission(device, pi)
            // A person is being asked: give them time, but never wait forever.
            withTimeoutOrNull(PERMISSION_WAIT_MS) { answer.await() } ?: false
        } finally {
            pending.remove(id)
        }
    }

    override fun open(id: String): CcidTransport? {
        val manager = usb ?: return null
        val device = devices[id] ?: return null
        val (intf, bulkIn, bulkOut) = cardReaderInterface(device) ?: return null
        val connection = runCatching { manager.openDevice(device) }.getOrNull() ?: return null
        if (!runCatching { connection.claimInterface(intf, true) }.getOrDefault(false)) {
            runCatching { connection.close() }
            return null
        }
        return UsbConnectionTransport(connection, intf, bulkIn, bulkOut)
    }

    private fun cardReaderInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_CSCID) continue
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) return Triple(intf, inEp, outEp)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_PERMISSION = "ph.toms.conductor.USB_PERMISSION"
        const val PERMISSION_WAIT_MS = 120_000L
    }
}

/** One claimed CCID interface. Every transfer is bounded by a timeout, and a closed or unplugged reader just reports a failed transfer. */
private class UsbConnectionTransport(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : CcidTransport {

    override fun send(message: ByteArray): Boolean =
        runCatching { connection.bulkTransfer(bulkOut, message, message.size, SEND_TIMEOUT_MS) == message.size }.getOrDefault(false)

    override fun receive(timeoutMs: Int): ByteArray? = runCatching {
        val buffer = ByteArray(bulkIn.maxPacketSize.coerceAtLeast(64) * 2 + 64)
        val n = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
        if (n < 0) null else buffer.copyOf(n)
    }.getOrNull()

    override fun close() {
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
    }

    private companion object {
        const val SEND_TIMEOUT_MS = 1_000
    }
}
