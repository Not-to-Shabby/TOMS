import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:usb_serial/usb_serial.dart';
import 'package:usb_serial/transaction.dart';
import '../models/models.dart';

/// Slave battery telemetry received via the Master's heartbeat relay.
class SlaveBatteryEvent {
  final String slaveUid;    // eFuse MAC hex string (no colons, uppercase)
  final int batteryMv;
  final int batteryPct;

  const SlaveBatteryEvent({
    required this.slaveUid,
    required this.batteryMv,
    required this.batteryPct,
  });
}

/// Manages USB CDC-ACM communication with the ESP32 Master.
///
/// Protocol: newline-delimited JSON over virtual serial port.
/// Master → Phone events: {"evt":"passenger",...}, {"evt":"dock",...}, etc.
/// Phone → Master commands: {"cmd":"handshake"}, {"cmd":"get_status"}, etc.
class UsbService extends ChangeNotifier {
  UsbPort? _port;
  Transaction<String>? _transaction;
  StreamSubscription<String>? _subscription;

  bool _connected = false;
  String _deviceName = '';
  DeviceStatus? _lastStatus;
  String? _lastError;

  // Raw message log for debugging
  final List<String> _rawLog = [];
  static const int _maxLogEntries = 200;
  List<String> get rawLog => List.unmodifiable(_rawLog);

  // Event stream for passenger boarding events
  final StreamController<PassengerLog> _passengerController =
      StreamController<PassengerLog>.broadcast();
  Stream<PassengerLog> get passengerStream => _passengerController.stream;

  // Event stream for NFC tap events
  final StreamController<NfcTapEvent> _nfcTapController =
      StreamController<NfcTapEvent>.broadcast();
  Stream<NfcTapEvent> get nfcTapStream => _nfcTapController.stream;

  // Event stream for button press notifications
  final StreamController<String> _buttonPressController =
      StreamController<String>.broadcast();
  Stream<String> get buttonPressStream => _buttonPressController.stream;

  // Event stream for slot release notifications
  final StreamController<String> _releaseController =
      StreamController<String>.broadcast();
  Stream<String> get releaseStream => _releaseController.stream;

  // Event stream for dock state changes
  final StreamController<bool> _dockController =
      StreamController<bool>.broadcast();
  Stream<bool> get dockStream => _dockController.stream;

  // Event stream for slave battery telemetry (relayed via Master heartbeat)
  final StreamController<SlaveBatteryEvent> _slaveBatteryController =
      StreamController<SlaveBatteryEvent>.broadcast();
  Stream<SlaveBatteryEvent> get slaveBatteryStream =>
      _slaveBatteryController.stream;

  bool get isConnected => _connected;
  String get deviceName => _deviceName;
  DeviceStatus? get lastStatus => _lastStatus;
  String? get lastError => _lastError;

  /// Scan and connect to the first available USB serial device.
  Future<bool> connect() async {
    _lastError = null;
    try {
      final devices = await UsbSerial.listDevices();
      if (devices.isEmpty) {
        _lastError = 'No USB devices found';
        notifyListeners();
        return false;
      }

      // Prefer the ESP32 device
      final device = devices.first;
      _deviceName = device.productName ?? 'ESP32';

      _port = await device.create();
      if (_port == null) {
        _lastError = 'Failed to open USB port';
        notifyListeners();
        return false;
      }

      final opened = await _port!.open();
      if (!opened) {
        _lastError = 'Failed to open port';
        notifyListeners();
        return false;
      }

      await _port!.setDTR(true);
      await _port!.setRTS(true);
      _port!.setPortParameters(
        115200,
        UsbPort.DATABITS_8,
        UsbPort.STOPBITS_1,
        UsbPort.PARITY_NONE,
      );

      // Line-based transaction parser (newline-delimited JSON)
      _transaction = Transaction.stringTerminated(
        _port!.inputStream!,
        Uint8List.fromList([10]), // \n
      );

      _subscription = _transaction!.stream.listen(
        _onData,
        onError: (e) {
          debugPrint('USB stream error: $e');
          _lastError = e.toString();
          disconnect();
        },
        onDone: () {
          debugPrint('USB stream closed');
          disconnect();
        },
      );

      _connected = true;
      notifyListeners();

      // Send handshake
      await sendCommand('handshake');

      return true;
    } catch (e) {
      _lastError = e.toString();
      notifyListeners();
      return false;
    }
  }

  /// Disconnect from USB device.
  void disconnect() {
    _subscription?.cancel();
    _subscription = null;
    _transaction = null;
    _port?.close();
    _port = null;
    _connected = false;
    _lastStatus = null;
    notifyListeners();
  }

  /// Send a JSON command to the Master.
  Future<void> sendCommand(String cmd, [Map<String, dynamic>? extra]) async {
    if (_port == null) return;

    final payload = <String, dynamic>{'cmd': cmd};
    if (extra != null) payload.addAll(extra);

    final json = '${jsonEncode(payload)}\n';
    await _port!.write(Uint8List.fromList(utf8.encode(json)));
    _addLog('TX: ${json.trim()}');
    debugPrint('USB TX: $json');
  }

  /// Send a raw string directly (for debugging).
  Future<void> sendRawDebug(String raw) async {
    if (_port == null) return;
    final data = '$raw\n';
    await _port!.write(Uint8List.fromList(utf8.encode(data)));
    _addLog('TX (raw): ${raw.trim()}');
    debugPrint('USB TX (raw): $raw');
  }

  /// Request current device status.
  Future<void> requestStatus() async {
    await sendCommand('get_status');
  }

  /// Send a proximity alarm command to the Master for relay to a specific Slave.
  ///
  /// The Master will look up the Slave's MAC from [slaveUid] and send a
  /// `TOMS_MSG_ALARM_CMD` via ESP-NOW, which triggers the red alarm screen.
  ///
  /// [alarmType]: 0 = approaching (show minutes), 1 = final stop ("PAY NOW!")
  /// [minutesLeft]: Estimated minutes to destination (ignored for type=1).
  Future<void> sendAlarmCommand(
      String slaveUid, int alarmType, int minutesLeft) async {
    // Normalize UID — strip colons, uppercase
    final uid = slaveUid.replaceAll(':', '').toUpperCase();
    await sendCommand('send_alarm', {
      'uid': uid,
      'alarm_type': alarmType,
      'minutes': minutesLeft,
    });
    debugPrint('USB TX: send_alarm uid=$uid type=$alarmType min=$minutesLeft');
  }

  /// Push the current route fare configuration to the Master.
  ///
  /// The Master saves the fare dictionary to NVS and broadcasts
  /// `TOMS_MSG_FARE_TABLE_UPDATE` to all known Slaves via ESP-NOW.
  ///
  /// [baseFareCentavos]: Base fare in centavos (e.g. 1300 = ₱13.00).
  /// [perKmCentavos]: Per-km rate in centavos (e.g. 250 = ₱2.50/km).
  Future<void> syncFareTable({
    required int baseFareCentavos,
    required int perKmCentavos,
    int? maxCapacity,
    List<String>? waypoints,
  }) async {
    await sendCommand('sync_fare_table', {
      'base_fare': baseFareCentavos,
      'per_km': perKmCentavos,
      if (maxCapacity != null) 'max_capacity': maxCapacity,
      if (waypoints != null) 'waypoints': waypoints,
    });
    debugPrint('USB TX: sync_fare_table base=$baseFareCentavos per_km=$perKmCentavos cap=$maxCapacity');
  }

  /// Explicitly sync full device configuration to the Master.
  Future<void> syncConfig({
    required int baseFareCentavos,
    required int perKmCentavos,
    required int maxCapacity,
    required List<String> waypoints,
  }) async {
    await sendCommand('sync_config', {
      'base_fare': baseFareCentavos,
      'per_km': perKmCentavos,
      'max_capacity': maxCapacity,
      'waypoints': waypoints,
    });
    debugPrint('USB TX: sync_config base=$baseFareCentavos per=$perKmCentavos cap=$maxCapacity');
  }

  /// Process incoming JSON line from the Master.
  void _onData(String data) {
    final trimmed = data.trim();
    if (trimmed.isEmpty) return;

    debugPrint('USB RX: $trimmed');
    _addLog('RX: $trimmed');

    try {
      final json = jsonDecode(trimmed) as Map<String, dynamic>;
      final evt = json['evt'] as String?;

      switch (evt) {
        case 'ack':
          // Handshake response — extract device info
          if (json.containsKey('battery_pct')) {
            _lastStatus = DeviceStatus.fromJson(json);
            notifyListeners();
          }
          break;

        case 'status':
          _lastStatus = DeviceStatus.fromJson(json);
          notifyListeners();
          break;

        case 'passenger':
          final log = PassengerLog.fromUsbJson(json);
          _passengerController.add(log);
          break;

        case 'nfc_tap':
          final tap = NfcTapEvent.fromJson(json);
          _nfcTapController.add(tap);
          break;

        case 'button_press':
          final mac = json['mac'] as String? ?? json['uid'] as String? ?? '';
          final formattedMac = mac.replaceAll(':', '');
          _buttonPressController.add(formattedMac);
          break;

        case 'release':
          final mac = json['mac'] as String? ?? json['uid'] as String? ?? '';
          final formattedMac = mac.replaceAll(':', '');
          _releaseController.add(formattedMac);
          break;

        case 'dock':
          final state = json['state'] as String?;
          _dockController.add(state == 'connected');
          break;

        case 'slave_battery':
          final uid = (json['uid'] as String? ?? '').replaceAll(':', '').toUpperCase();
          _slaveBatteryController.add(SlaveBatteryEvent(
            slaveUid: uid,
            batteryMv: json['battery_mv'] as int? ?? 0,
            batteryPct: json['battery_pct'] as int? ?? 0,
          ));
          break;

        case 'slave_docked':
          debugPrint('Slave docked: UID=${json['uid']}, seat=${json['seat']}');
          break;

        case 'error':
          _lastError = json['msg'] as String?;
          notifyListeners();
          break;

        default:
          debugPrint('Unknown USB event: $evt');
      }
    } catch (e) {
      debugPrint('JSON parse error: $e for data: $trimmed');
    }
  }

  void _addLog(String entry) {
    final ts = DateTime.now();
    final stamp = '${ts.hour.toString().padLeft(2, '0')}:${ts.minute.toString().padLeft(2, '0')}:${ts.second.toString().padLeft(2, '0')}';
    _rawLog.insert(0, '[$stamp] $entry');
    if (_rawLog.length > _maxLogEntries) {
      _rawLog.removeRange(_maxLogEntries, _rawLog.length);
    }
    notifyListeners();
  }

  void clearLog() {
    _rawLog.clear();
    notifyListeners();
  }

  @override
  void dispose() {
    disconnect();
    _passengerController.close();
    _nfcTapController.close();
    _buttonPressController.close();
    _releaseController.close();
    _dockController.close();
    _slaveBatteryController.close();
    super.dispose();
  }
}
