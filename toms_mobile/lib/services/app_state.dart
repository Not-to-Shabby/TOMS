import 'dart:async';
import 'package:flutter/foundation.dart';
import '../models/models.dart';
import 'database_service.dart';
import 'usb_service.dart';

/// Orchestrates USB events → local DB storage → cloud sync.
class AppState extends ChangeNotifier {
  final UsbService usbService;
  final DatabaseService dbService;

  StreamSubscription<PassengerLog>? _passengerSub;
  StreamSubscription<bool>? _dockSub;

  List<PassengerLog> _recentLogs = [];
  int _todayRevenue = 0;
  int _todayPassengers = 0;
  int _totalLogs = 0;
  bool _docked = false;
  Timer? _statusTimer;

  List<PassengerLog> get recentLogs => _recentLogs;
  int get todayRevenue => _todayRevenue;
  int get todayPassengers => _todayPassengers;
  int get totalLogs => _totalLogs;
  bool get isDocked => _docked;

  String get todayRevenueFormatted {
    final pesos = _todayRevenue ~/ 100;
    final cents = _todayRevenue % 100;
    return '₱$pesos.${cents.toString().padLeft(2, '0')}';
  }

  AppState({required this.usbService, required this.dbService}) {
    _init();
  }

  Future<void> _init() async {
    // Listen for new passenger boarding events from USB
    _passengerSub = usbService.passengerStream.listen(_onPassenger);
    _dockSub = usbService.dockStream.listen(_onDock);

    // Load initial data
    await refreshData();

    // Periodically request status when connected
    _statusTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      if (usbService.isConnected) {
        usbService.requestStatus();
      }
    });
  }

  Future<void> _onPassenger(PassengerLog log) async {
    // Save to local DB immediately (offline-first)
    await dbService.insertLog(log);
    await refreshData();
    notifyListeners();
  }

  void _onDock(bool docked) {
    _docked = docked;
    notifyListeners();
  }

  Future<void> refreshData() async {
    _recentLogs = await dbService.getAllLogs(limit: 20);
    _todayRevenue = await dbService.getTodayRevenue();
    _todayPassengers = await dbService.getTodayPassengerCount();
    _totalLogs = await dbService.getLogCount();
    notifyListeners();
  }

  Future<void> connectUsb() async {
    await usbService.connect();
  }

  @override
  void dispose() {
    _passengerSub?.cancel();
    _dockSub?.cancel();
    _statusTimer?.cancel();
    super.dispose();
  }
}
