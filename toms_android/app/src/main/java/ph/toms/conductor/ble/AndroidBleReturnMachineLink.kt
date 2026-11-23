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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
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
    private val trust: RmMachineTrust,
    private val enrolment: dagger.Lazy<RmKeyEnrolment>,
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

    private val _droppedCount = MutableStateFlow(0)
    override val droppedCount: StateFlow<Int> = _droppedCount.asStateFlow()

    /** Layout 1 until the machine says layout 2 is in force after this connection's `07 02`; reset at every disconnect. */
    private val negotiation = RmLayoutNegotiation()
    override val layout: StateFlow<RmProtocol.Layout> = negotiation.layout

    private val _pairingNotice = MutableStateFlow<RmKeyStatus?>(null)
    override val pairingNotice: StateFlow<RmKeyStatus?> = _pairingNotice.asStateFlow()

    /** What Android tells the phone about pairing with the machine, turned into plain words (see [RmPairingTracker]). */
    private val pairing = RmPairingTracker { notice -> _pairingNotice.value = notice }

    private var replyToReplace: CompletableDeferred<RmProtocol.ReplaceReply>? = null

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = bluetoothDevice(intent) ?: return
            if (device.address != machineAddress) return
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST ->
                    pairing.onPairingRequest(intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1))
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> pairing.onBondChanged(
                    intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE),
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun bluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Not exported: only the system sends these, and only the app reads them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(pairingReceiver, filter)
        }
    }

    /** The key this phone signs its writes with (null: no trailer). See [RmSignedWrites.writeKey]. */
    private fun writeKey(): ByteArray? = RmSignedWrites.writeKey(trust, machineAddress)

    /** The address of the machine the GATT is attached to: the per-machine id the trust is keyed by. */
    private var machineAddress: String? = null

    /** Everything signed the machine sends is verified here before the app believes it. */
    private val gate = RmNotifyGate(
        trust,
        machineId = { machineAddress },
        onDrop = { reason, suffix ->
            _droppedCount.value += 1
            Log.w("RmTrust", "dropped notify 0x%02x: %s".format(suffix, reason))
        },
        onVerified = { id, match -> enrolment.get().onVerified(id, match) },
        onSignedWithoutKey = { id -> enrolment.get().onUnknownSignature(id) },
        onBadSignature = { id -> enrolment.get().onUnknownSignature(id) },
        layout = { negotiation.layout.value },
    )

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val gattMutex = Mutex()

    private var descDone: CompletableDeferred<Int>? = null
    private var charWritten: CompletableDeferred<Int>? = null
    private var provisionReply: CompletableDeferred<Int>? = null

    // Characteristic handles
    private var charPaidReply: BluetoothGattCharacteristic? = null
    private var charStateUpdate: BluetoothGattCharacteristic? = null
    private var charTimeSync: BluetoothGattCharacteristic? = null
    private var charDiag: BluetoothGattCharacteristic? = null
    private var charAck: BluetoothGattCharacteristic? = null

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
                pairing.onConnected(bonded = device.bondState == BluetoothDevice.BOND_BONDED)
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    // Android chooses the pairing method from what this phone can show and enter (a phone with a screen and a
                    // keyboard announces display-and-yes/no, which gives numeric comparison); the app cannot force it.
                    // [pairing] reads what Android did and tells the conductor if it was not an authenticated pairing.
                    pairing.onBondRequested()
                    device.createBond()
                }
                if (!g.requestMtu(247)) {
                    refreshGatt(g)
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // The machine goes back to layout 1 on a disconnect, so the phone does too.
                negotiation.reset()
                pairing.onDisconnected()
                replyToReplace?.cancel()
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
            charAck = service.getCharacteristic(RmProtocol.CHAR_ACK)

            // Subscribe to notifications strictly sequentially, awaiting each onDescriptorWrite.
            // Diag is subscribed too: it carries the provisioning status reply (0x86).
            scope.launch {
                val notifyChars = listOf(
                    service.getCharacteristic(RmProtocol.CHAR_PAID_CHECK),
                    service.getCharacteristic(RmProtocol.CHAR_CARD_READ),
                    service.getCharacteristic(RmProtocol.CHAR_HEARTBEAT),
                    service.getCharacteristic(RmProtocol.CHAR_RM_ACK),
                    service.getCharacteristic(RmProtocol.CHAR_DIAG),
                ).filterNotNull()

                for (c in notifyChars) {
                    subscribeCharacteristic(g, c)
                    delay(50)
                }

                val name = g.device.name ?: RmProtocol.DEVICE_NAME_PREFIX
                val address = g.device.address ?: ""
                // Every connection starts on layout 1, whatever the last one used.
                negotiation.reset()
                _connectionState.value = RmConnectionState.Connected(name, address)

                // Sync time immediately on connection
                sendTimeSync()

                // Ask for layout 2 once the parser that understands it is in place (it is: the gate frames with the layout
                // in force). A machine that does not answer keeps layout 1, byte for byte what it sent before.
                val ch = charDiag
                if (ch != null) negotiation.negotiate { bytes -> writeCharWithResp(g, ch, bytes) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descDone?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pairing.onWriteRefused(status)
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
        val suffix = RmProtocol.suffixOf(uuid)
        when (suffix) {
            RmSignature.SUFFIX_CARD_READ, RmSignature.SUFFIX_HEARTBEAT, RmSignature.SUFFIX_RM_ACK -> {
                // Verified or dropped by the gate; nothing unverified reaches the app from here.
                val payload = gate.accept(suffix, value) ?: return
                // Decoded with the same layout the gate framed with: the two cannot disagree.
                val inForce = negotiation.layout.value
                when (suffix) {
                    RmSignature.SUFFIX_CARD_READ ->
                        RmProtocol.decodeCardReadStrict(payload, inForce)?.let { _events.tryEmit(RmEvent.CardReadReceived(it)) }
                    RmSignature.SUFFIX_HEARTBEAT ->
                        RmProtocol.decodeHeartbeatStrict(payload, inForce)?.let {
                            _lastHeartbeat.value = it
                            _events.tryEmit(RmEvent.HeartbeatReceived(it))
                        }
                    else ->
                        RmProtocol.decodeRmAckStrict(payload)?.let { _events.tryEmit(RmEvent.RmAckReceived(it)) }
                }
            }
            RmProtocol.suffixOf(RmProtocol.CHAR_PAID_CHECK) -> {
                RmProtocol.decodePaidCheck(value)?.let {
                    _events.tryEmit(RmEvent.PaidCheckReceived(it.checkSeq, it.uid, it.uidHex, negotiation.layout.value))
                }
            }
            RmProtocol.suffixOf(RmProtocol.CHAR_DIAG) -> {
                RmProtocol.decodeProvisionReply(value)?.let { status ->
                    if (status == RmProtocol.PROVISION_REFUSED) pairing.onLinkNotAuthenticated()
                    provisionReply?.complete(status)
                }
                RmProtocol.decodeReplaceReply(value)?.let { reply ->
                    if (reply.status == RmProtocol.REPLACE_NOT_AUTHENTICATED) pairing.onLinkNotAuthenticated()
                    replyToReplace?.complete(reply)
                }
                negotiation.onDiagNotify(value)
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
        machineAddress = device.address
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
        charAck = null
        machineAddress = null
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

    private companion object {
        /** The machine answers the key provisioning write within one connection interval or two. */
        const val PROVISION_REPLY_TIMEOUT_MS = 5_000L
        const val REPLACE_REPLY_TIMEOUT_MS = 5_000L
    }

    override fun sendPaidReply(checkSeq: Long, verdict: Int) {
        val g = gatt ?: return
        val ch = charPaidReply ?: return
        val bytes = RmSignedWrites.paidReply(writeKey(), checkSeq, verdict)
        scope.launch {
            gattMutex.withLock {
                writeCharacteristic(g, ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            }
        }
    }

    override fun sendStateUpdate(tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>) {
        val g = gatt ?: return
        val ch = charStateUpdate ?: return
        val bytes = RmSignedWrites.stateUpdate(writeKey(), tableVersion, snapshot, entries)
        scope.launch {
            writeCharWithResp(g, ch, bytes)
        }
    }

    override fun sendTimeSync(nowMillis: Long) {
        val g = gatt ?: return
        val ch = charTimeSync ?: return
        val bytes = RmSignedWrites.timeSync(writeKey(), nowMillis)
        scope.launch {
            writeCharWithResp(g, ch, bytes)
        }
    }

    override fun sendAlarmClear() {
        val g = gatt ?: return
        val ch = charDiag ?: return
        val bytes = RmSignedWrites.alarmClear(writeKey())
        scope.launch {
            writeCharWithResp(g, ch, bytes)
        }
    }

    override fun sendReadAck(upToReadSeq: Long) {
        val g = gatt ?: return
        val ch = charAck ?: return
        val bytes = RmSignedWrites.readAck(writeKey(), upToReadSeq)
        scope.launch {
            writeCharWithResp(g, ch, bytes)
        }
    }

    override suspend fun sendProvisionKey(key: ByteArray): Int? {
        // writeCharWithResp takes the GATT mutex itself, so the await must stay outside it.
        val deferred = CompletableDeferred<Int>()
        provisionReply = deferred
        try {
            val g = gatt ?: return null
            val ch = charDiag ?: return null
            val written = writeCharWithResp(g, ch, RmProtocol.encodeProvisionKey(key))
            if (!written) return null
            return try {
                withTimeout(PROVISION_REPLY_TIMEOUT_MS) { deferred.await() }
            } catch (_: Exception) {
                null
            }
        } finally {
            provisionReply = null
        }
    }

    override suspend fun sendKeyReplace(currentKey: ByteArray, newKey: ByteArray): Int? {
        val deferred = CompletableDeferred<RmProtocol.ReplaceReply>()
        replyToReplace = deferred
        try {
            val g = gatt ?: return null
            val ch = charDiag ?: return null
            val written = writeCharWithResp(g, ch, RmProtocol.encodeKeyReplace(currentKey, newKey))
            if (!written) return null
            return try {
                withTimeout(REPLACE_REPLY_TIMEOUT_MS) { deferred.await().status }
            } catch (_: Exception) {
                null
            }
        } finally {
            replyToReplace = null
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
