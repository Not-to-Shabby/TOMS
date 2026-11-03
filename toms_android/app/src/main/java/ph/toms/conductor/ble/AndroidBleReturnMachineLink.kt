package ph.toms.conductor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@Singleton
@SuppressLint("MissingPermission")
class AndroidBleReturnMachineLink @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReturnMachineLink {

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow<RmConnectionState>(RmConnectionState.Disconnected)
    override val connectionState: StateFlow<RmConnectionState> = _connectionState.asStateFlow()

    private val _lastHeartbeat = MutableStateFlow<RmProtocol.Heartbeat?>(null)
    override val lastHeartbeat: StateFlow<RmProtocol.Heartbeat?> = _lastHeartbeat.asStateFlow()

    private val _events = MutableSharedFlow<RmEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RmEvent> = _events.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val gattMutex = Mutex()

    private var descDone: CompletableDeferred<Int>? = null
    private var charWritten: CompletableDeferred<Int>? = null

    // Characteristic handles
    private var charPaidReply: BluetoothGattCharacteristic? = null
    private var charStateUpdate: BluetoothGattCharacteristic? = null
    private var charTimeSync: BluetoothGattCharacteristic? = null
    private var charDiag: BluetoothGattCharacteristic? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val rawName = result.scanRecord?.deviceName ?: device.name
            val hasMatchingUuid = result.scanRecord?.serviceUuids?.contains(ParcelUuid(RmProtocol.SERVICE_UUID)) == true
            val matchesName = rawName?.startsWith(RmProtocol.DEVICE_NAME_PREFIX) == true
            if (hasMatchingUuid || matchesName) {
                val displayName = rawName ?: RmProtocol.DEVICE_NAME_PREFIX
                stopScanning()
                connectTo(device, displayName)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connectionState.value = RmConnectionState.Disconnected
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val device = g.device
            val name = device.name ?: RmProtocol.DEVICE_NAME_PREFIX
            val address = device.address ?: ""

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = RmConnectionState.Connecting(name, address)
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    device.createBond()
                }
                if (!g.requestMtu(247)) {
                    refreshGatt(g)
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanup()
                _connectionState.value = RmConnectionState.Disconnected
                // Auto-retry scanning if disconnected unexpectedly
                scope.launch {
                    delay(3000)
                    startScanning()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            refreshGatt(g)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect()
                return
            }

            val service = g.getService(RmProtocol.SERVICE_UUID) ?: run {
                g.disconnect()
                return
            }

            charPaidReply = service.getCharacteristic(RmProtocol.CHAR_PAID_REPLY)
            charStateUpdate = service.getCharacteristic(RmProtocol.CHAR_STATE_UPDATE)
            charTimeSync = service.getCharacteristic(RmProtocol.CHAR_TIME_SYNC)
            charDiag = service.getCharacteristic(RmProtocol.CHAR_DIAG)

            // Subscribe to notifications strictly sequentially, awaiting each onDescriptorWrite
            scope.launch {
                val notifyChars = listOf(
                    service.getCharacteristic(RmProtocol.CHAR_PAID_CHECK),
                    service.getCharacteristic(RmProtocol.CHAR_CARD_READ),
                    service.getCharacteristic(RmProtocol.CHAR_HEARTBEAT),
                    service.getCharacteristic(RmProtocol.CHAR_RM_ACK),
                ).filterNotNull()

                for (c in notifyChars) {
                    subscribeCharacteristic(g, c)
                    delay(50)
                }

                val name = g.device.name ?: RmProtocol.DEVICE_NAME_PREFIX
                val address = g.device.address ?: ""
                _connectionState.value = RmConnectionState.Connected(name, address)

                // Sync time immediately on connection
                sendTimeSync()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descDone?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            charWritten?.complete(status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }

        @Deprecated("Deprecated for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { handleNotification(characteristic.uuid, it) }
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            RmProtocol.CHAR_PAID_CHECK -> {
                RmProtocol.decodePaidCheck(value)?.let {
                    _events.tryEmit(RmEvent.PaidCheckReceived(it.checkSeq, it.uid, it.uidHex))
                }
            }
            RmProtocol.CHAR_CARD_READ -> {
                RmProtocol.decodeCardRead(value)?.let {
                    _events.tryEmit(RmEvent.CardReadReceived(it))
                }
            }
            RmProtocol.CHAR_HEARTBEAT -> {
                RmProtocol.decodeHeartbeat(value)?.let {
                    _lastHeartbeat.value = it
                    _events.tryEmit(RmEvent.HeartbeatReceived(it))
                }
            }
            RmProtocol.CHAR_RM_ACK -> {
                RmProtocol.decodeRmAck(value)?.let {
                    _events.tryEmit(RmEvent.RmAckReceived(it))
                }
            }
        }
    }

    private suspend fun subscribeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean =
        gattMutex.withLock {
            g.setCharacteristicNotification(c, true)
            val descriptor = c.getDescriptor(RmProtocol.CCCD_UUID) ?: return@withLock false
            val deferred = CompletableDeferred<Int>()
            descDone = deferred

            val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }

            if (!writeStarted) {
                descDone = null
                return@withLock false
            }

            try {
                withTimeout(3000) { deferred.await() == BluetoothGatt.GATT_SUCCESS }
            } catch (_: Exception) {
                false
            } finally {
                descDone = null
            }
        }

    private fun refreshGatt(g: BluetoothGatt): Boolean {
        return try {
            val method = g.javaClass.getMethod("refresh")
            (method.invoke(g) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun connectTo(device: BluetoothDevice, name: String) {
        _connectionState.value = RmConnectionState.Connecting(name, device.address)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private fun cleanup() {
        charPaidReply = null
        charStateUpdate = null
        charTimeSync = null
        charDiag = null
        try {
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
    }

    override fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning || _connectionState.value is RmConnectionState.Connected) return

        scanning = true
        _connectionState.value = RmConnectionState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(RmProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    override fun stopScanning() {
        if (!scanning) return
        scanning = false
        val scanner = adapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        if (_connectionState.value is RmConnectionState.Scanning) {
            _connectionState.value = RmConnectionState.Disconnected
        }
    }

    override fun disconnect() {
        stopScanning()
        gatt?.disconnect()
        cleanup()
        _connectionState.value = RmConnectionState.Disconnected
    }

    override fun sendPaidReply(checkSeq: Long, verdict: Int) {
        val g = gatt ?: return
        val ch = charPaidReply ?: return
        val bytes = RmProtocol.encodePaidReply(checkSeq, verdict)
        scope.launch {
            gattMutex.withLock {
                writeCharacteristic(g, ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            }
        }
    }

    override fun sendStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>) {
        val g = gatt ?: return
        val ch = charStateUpdate ?: return
        val bytes = RmProtocol.encodeStateUpdate(tableVersion, snapshot, entries)
        scope.launch {
            writeCharWithResp(g, ch, bytes)
        }
    }

    override fun sendTimeSync(nowMillis: Long) {
        val g = gatt ?: return
        val ch = charTimeSync ?: return
        val bytes = RmProtocol.encodeTimeSync(nowMillis)
        scope.launch {
            writeCharWithResp(g, ch, bytes)
        }
    }

    override fun sendAlarmClear() {
        val g = gatt ?: return
        val ch = charDiag ?: return
        val bytes = RmProtocol.encodeAlarmClear()
        scope.launch {
            writeCharWithResp(g, ch, bytes)
        }
    }

    private suspend fun writeCharWithResp(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = gattMutex.withLock {
        val deferred = CompletableDeferred<Int>()
        charWritten = deferred
        val started = writeCharacteristic(g, c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (!started) {
            charWritten = null
            return@withLock false
        }
        try {
            withTimeout(3000) { deferred.await() == BluetoothGatt.GATT_SUCCESS }
        } catch (_: Exception) {
            false
        } finally {
            charWritten = null
        }
    }

    private fun writeCharacteristic(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        bytes: ByteArray,
        writeType: Int,
    ): Boolean {
        c.writeType = writeType
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }
}
