import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:usb_serial/usb_serial.dart';
import 'package:usb_serial/transaction.dart';
import '../models/models.dart';

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

  // Event stream for dock state changes
  final StreamController<bool> _dockController =
      StreamController<bool>.broadcast();
  Stream<bool> get dockStream => _dockController.stream;

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

        case 'dock':
          final state = json['state'] as String?;
          _dockController.add(state == 'connected');
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
    _dockController.close();
    super.dispose();
  }
}
