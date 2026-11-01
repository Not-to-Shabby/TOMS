package ph.toms.ota

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** A machine seen while scanning. */
data class FoundMachine(val name: String, val address: String, val rssi: Int, val device: BluetoothDevice)

/**
 * Android GATT behind [OtaTransport]. The permission checks are done by the screen before any of
 * this runs, so the calls are marked as already checked.
 *
 * Android's GATT allows one operation at a time: a write or descriptor write must finish before
 * the next. Writes without response are paced by the stack, so [writeData] waits for the
 * "can send more" callback every time, which keeps the radio queue short and avoids dropped data.
 */
@SuppressLint("MissingPermission")
class BleTransport private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : OtaTransport {

    private var gatt: BluetoothGatt? = null
    private val eventChannel = Channel<OtaEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<OtaEvent> get() = eventChannel

    @Volatile
    override var mtu: Int = 23
        private set

    private val ops = Mutex()
    private var connected = CompletableDeferred<Unit>()
    private var servicesDone = CompletableDeferred<Unit>()
    private var mtuDone = CompletableDeferred<Unit>()
    private var descDone = CompletableDeferred<Unit>()
    private var controlWritten = CompletableDeferred<Int>()
    private var dataReady = CompletableDeferred<Unit>()
    private var bondDone = CompletableDeferred<Boolean>()
    private var control: BluetoothGattCharacteristic? = null
    private var data: BluetoothGattCharacteristic? = null

    @Volatile
    private var closed = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = deviceOf(i) ?: return
            if (d.address != device.address) return
            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> bondDone.complete(true)
                BluetoothDevice.BOND_NONE -> bondDone.complete(false)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("link state $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else {
                val why = LinkLost("disconnected (status $status)")
                for (d in listOf(connected, servicesDone, mtuDone, descDone, dataReady)) d.completeExceptionally(why)
                controlWritten.completeExceptionally(why)
                bondDone.complete(false)
                eventChannel.close(why)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDone.complete(Unit)
            else servicesDone.completeExceptionally(LinkLost("service discovery failed ($status)"))
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            log("MTU is $mtu (status $status)")
            mtuDone.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) descDone.complete(Unit)
            else descDone.completeExceptionally(LinkLost("could not subscribe ($status)"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid == Ota.CONTROL) controlWritten.complete(status)
            else dataReady.complete(Unit) // write-without-response also reports here on some stacks
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c.uuid, value)
        }

        // API 26..32
        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(c.uuid, c.value ?: return)
        }
    }

    private fun deliver(uuid: UUID, value: ByteArray) {
        if (uuid != Ota.CONTROL) return
        Ota.parseEvent(value)?.let { eventChannel.trySend(it) } ?: log("ignored a ${value.size}-byte notification")
    }

    private suspend fun open(timeoutMs: Long) {
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        log("connecting to ${device.address}")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        withTimeout(timeoutMs) { connected.await() }

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("not paired yet: asking Android to pair (a prompt may appear on the screen)")
            if (!device.createBond()) throw LinkLost("could not start pairing")
            if (!withTimeout(60_000) { bondDone.await() }) throw LinkLost("pairing failed or was refused")
            log("paired")
        }

        log("discovering services")
        gatt!!.discoverServices()
        withTimeout(timeoutMs) { servicesDone.await() }
        val svc = gatt!!.getService(Ota.SERVICE) ?: throw LinkLost("this device has no TOMS service")
        control = svc.getCharacteristic(Ota.CONTROL) ?: throw LinkLost("firmware has no update service (too old?)")
        data = svc.getCharacteristic(Ota.DATA) ?: throw LinkLost("firmware has no update data characteristic")

        log("requesting MTU 65 (the machine's maximum)")
        gatt!!.requestMtu(65)
        withTimeout(timeoutMs) { mtuDone.await() }

        gatt!!.setCharacteristicNotification(control, true)
        val cccd = control!!.getDescriptor(CCCD) ?: throw LinkLost("ota_control has no notification descriptor")
        write(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        withTimeout(timeoutMs) { descDone.await() }
        log("subscribed to the update channel")
    }

    @Suppress("DEPRECATION")
    private fun write(d: BluetoothGattDescriptor, v: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) gatt!!.writeDescriptor(d, v)
        else {
            d.value = v
            gatt!!.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(c: BluetoothGattCharacteristic, v: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt!!.writeCharacteristic(c, v, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = v
            gatt!!.writeCharacteristic(c)
        }

    override suspend fun writeControl(data: ByteArray) = ops.withLock {
        controlWritten = CompletableDeferred()
        if (!writeChar(control!!, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
            throw LinkLost("control write was not accepted by the stack")
        }
        val st = withTimeout(30_000) { controlWritten.await() }
        if (st != BluetoothGatt.GATT_SUCCESS) throw LinkLost("control write failed (status $st)")
    }

    override suspend fun writeData(data: ByteArray) = ops.withLock {
        var tries = 0
        while (!writeChar(this.data!!, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
            // The stack's queue is full: give the radio a moment, then try this chunk again.
            if (closed || ++tries > 2000) throw LinkLost("data write kept being refused")
            kotlinx.coroutines.delay(2)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { context.unregisterReceiver(bondReceiver) }
        gatt?.disconnect()
        gatt?.close()
        eventChannel.close()
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Suppress("DEPRECATION")
        private fun deviceOf(i: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

        suspend fun connect(context: Context, device: BluetoothDevice, log: (String) -> Unit): BleTransport {
            try {
                return connectOnce(context, device, log)
            } catch (e: LinkLost) {
                // A machine that rebooted (every update ends with one) lost its copy of the
                // pairing keys; the phone still holds them, so the link dies on encryption
                // with "key missing". Drop the stale pairing and pair again from scratch.
                if (device.bondState != BluetoothDevice.BOND_BONDED) throw e
                log("the machine rejected the stored pairing key; removing the stale pairing and pairing again")
                if (!removeBond(device)) {
                    throw LinkLost(
                        "Could not clear the old pairing automatically. In Android Settings > " +
                            "Bluetooth, remove TOMS-RM from the paired devices, then try again.",
                    )
                }
                val end = System.currentTimeMillis() + 15_000
                while (device.bondState == BluetoothDevice.BOND_BONDED && System.currentTimeMillis() < end) {
                    delay(200)
                }
                return connectOnce(context, device, log)
            }
        }

        private suspend fun connectOnce(context: Context, device: BluetoothDevice, log: (String) -> Unit): BleTransport {
            val t = BleTransport(context.applicationContext, device, log)
            try {
                t.open(20_000)
            } catch (e: Throwable) {
                t.close()
                throw if (e is kotlinx.coroutines.TimeoutCancellationException) LinkLost("timed out connecting") else e
            }
            return t
        }

        private fun removeBond(device: BluetoothDevice): Boolean = try {
            device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }

        /** Scan until [onFound] has been called for each machine seen, or the caller cancels. */
        suspend fun scan(context: Context, timeoutMs: Long, onFound: (FoundMachine) -> Unit) {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val scanner = adapter?.bluetoothLeScanner ?: throw LinkLost("Bluetooth is off")
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ota.SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            suspendCancellableCoroutine<Unit> { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(type: Int, r: ScanResult) {
                        onFound(FoundMachine(r.scanRecord?.deviceName ?: r.device.name ?: "TOMS machine",
                            r.device.address, r.rssi, r.device))
                    }

                    override fun onScanFailed(code: Int) {
                        if (cont.isActive) cont.resumeWithException(LinkLost("scan failed ($code)"))
                    }
                }
                scanner.startScan(listOf(filter), settings, cb)
                val timer = Thread {
                    try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) { return@Thread }
                    runCatching { scanner.stopScan(cb) }
                    if (cont.isActive) cont.resume(Unit)
                }
                timer.isDaemon = true
                // one handler only: coroutines refuse a second invokeOnCancellation
                cont.invokeOnCancellation {
                    runCatching { scanner.stopScan(cb) }
                    timer.interrupt()
                }
                timer.start()
            }
        }
    }
}
