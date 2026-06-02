import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:vibration/vibration.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import '../models/models.dart';
import '../config/env.dart';
import 'database_service.dart';
import 'usb_service.dart';
import 'session_service.dart';
import 'occupancy_service.dart';
import 'connectivity_service.dart';
import 'sync_service.dart';
import 'gps_service.dart';
import 'proximity_service.dart';

/// Orchestrates USB events → local DB storage → cloud sync.
class AppState extends ChangeNotifier {
  final UsbService usbService;
  final DatabaseService dbService;

  late final SessionService sessionService;
  late final OccupancyService occupancyService;
  late final ConnectivityService connectivityService;
  late final SyncService syncService;
  late final GpsService gpsService;
  late final ProximityService proximityService;

  // Local notifications plugin
  final _notifications = FlutterLocalNotificationsPlugin();

  StreamSubscription<PassengerLog>? _passengerSub;
  StreamSubscription<NfcTapEvent>? _nfcTapSub;
  StreamSubscription<String>? _buttonPressSub;
  StreamSubscription<String>? _releaseSub;
  StreamSubscription<bool>? _connectivitySub;
  StreamSubscription<SlaveBatteryEvent>? _slaveBatterySub;

  /// uid (hex, no colons, uppercase) → last-known battery %, or -1 if unknown.
  final Map<String, int> _slaveBatteryMap = {};

  List<PassengerLog> _recentLogs = [];
  int _todayRevenue = 0;
  int _todayPassengers = 0;
  int _totalLogs = 0;
  Timer? _statusTimer;
  Timer? _scheduleTimer;

  List<dynamic> _paths = [];
  int? _activePathId;
  int _activeRouteId = 1;
  String _activePathName = 'Primary';

  List<TransitStop> _stops = [];
  String? _requestingUid;
  String? _manualAssignTarget;

  // ── Getters ──────────────────────────────────────────────────
  String? get requestingUid => _requestingUid;
  String? get manualAssignTarget => _manualAssignTarget;
  bool get isDocked => false;
  List<TransitStop> get stops => _stops;

  List<PassengerLog> get recentLogs => _recentLogs;
  int get todayRevenue => _todayRevenue;
  int get todayPassengers => _todayPassengers;
  int get totalLogs => _totalLogs;

  // Occupancy delegation
  List<PassengerSlot> get activeSlots =>
      occupancyService.getPassengerSlots(batteryMap: _slaveBatteryMap);
  int get slavesDeployed => occupancyService.slavesDeployed;
  int get maxCapacity => occupancyService.maxCapacity;
  double get occupancyRate => occupancyService.occupancyRate;
  int get paidCount => occupancyService.paidCount;
  int get unpaidCount => occupancyService.unpaidCount;
  String get vehicleId => occupancyService.vehicleId;

  // Session delegation
  List<PendingPassenger> get pendingQueue => sessionService.pendingQueue;
  List<PassengerSession> get activeSessions => sessionService.activeSessions;

  PassengerSession? getSessionBySlaveUid(String slaveUid) =>
      sessionService.getSessionBySlave(slaveUid);

  int getSlotNumber(String slaveUid) =>
      occupancyService.getSlotNumber(slaveUid);

  // Fare calculation delegation
  int calculateBaseFare(TransitStop origin, TransitStop destination) =>
      sessionService.calculateBaseFare(origin, destination);

  int calculateFare(int baseFare, PassengerType type) =>
      sessionService.calculateFare(baseFare, type);

  int calculateDiscount(int baseFare, PassengerType type) =>
      sessionService.calculateDiscount(baseFare, type);

  String get todayRevenueFormatted {
    final pesos = _todayRevenue ~/ 100;
    final cents = _todayRevenue % 100;
    return '₱$pesos.${cents.toString().padLeft(2, '0')}';
  }

  // ── Initialization ───────────────────────────────────────────
  AppState({
    required this.usbService,
    required this.dbService,
    String Function()? tokenGetter,
  }) {
    sessionService = SessionService();
    occupancyService = OccupancyService(sessionService: sessionService);
    connectivityService = ConnectivityService();
    syncService = SyncService(db: dbService, connectivity: connectivityService)
      ..tokenGetter = tokenGetter;
    gpsService = GpsService();
    proximityService = ProximityService(
      sessionService: sessionService,
      gpsService: gpsService,
    );
    initialize();
  }

  /// Called when conductor ends their shift — resets occupancy and returns
  /// to vehicle assignment. AuthService.assignVehicle(null) is called by the caller.
  void endShift() {
    sessionService.clearAll();
    occupancyService.reset();
    notifyListeners();
  }

  Future<void> initialize() async {
    await connectivityService.init();
    syncService.startRetryTimer();

    // 1. Fetch vehicle config first to determine its active assigned route ID
    try {
      if (connectivityService.isOnline) {
        final response = await http
            .get(Uri.parse('${Env.apiBaseUrl}/api/vehicles'))
            .timeout(const Duration(seconds: 5));
        if (response.statusCode == 200) {
          final List<dynamic> vehiclesList = jsonDecode(response.body);
          final currentVehicle = vehiclesList.firstWhere(
            (v) => v['id'] == occupancyService.vehicleId,
            orElse: () => null,
          );
          if (currentVehicle != null) {
            occupancyService.maxCapacity =
                currentVehicle['max_capacity'] as int? ?? 20;
            _activeRouteId = currentVehicle['assigned_route_id'] as int? ?? 1;
            debugPrint('Loaded vehicle config. Route ID: $_activeRouteId, Max Capacity: ${occupancyService.maxCapacity}');
          }
        }
      }
    } catch (e) {
      debugPrint('Error loading vehicles from API: $e');
    }

    // Load route stops dynamically from API and cache locally
    try {
      final prefs = await SharedPreferences.getInstance();
      String? pathsJson;

      // Attempt to fetch fresh stops and matrix if online
      if (connectivityService.isOnline) {
        try {
          // Loaded from environment variables configuration.
          final responsePaths = await http
              .get(Uri.parse('${Env.apiBaseUrl}/api/routes/$_activeRouteId/paths'))
              .timeout(const Duration(seconds: 5));
          if (responsePaths.statusCode == 200) {
            pathsJson = responsePaths.body;
            await prefs.setString('cached_paths', pathsJson);
            debugPrint('Successfully fetched and cached fresh paths for route $_activeRouteId.');
          }

          final responseRoutes = await http
              .get(Uri.parse('${Env.apiBaseUrl}/api/routes'))
              .timeout(const Duration(seconds: 5));
          if (responseRoutes.statusCode == 200) {
            final routesList = json.decode(responseRoutes.body) as List;
            if (routesList.isNotEmpty) {
              final activeRoute = routesList.firstWhere(
                (r) => r['id'] == _activeRouteId,
                orElse: () => routesList.first,
              );
              final baseFare = (activeRoute['base_fare'] ?? 15.0).toDouble();
              final perKmFare = (activeRoute['per_km_fare'] ?? 2.5).toDouble();
              sessionService.updateFareMatrix(baseFare, perKmFare);
              await prefs.setDouble('cached_base_fare', baseFare);
              await prefs.setDouble('cached_per_km_fare', perKmFare);
              debugPrint('Fare Matrix Updated: Base ₱$baseFare, Per Km ₱$perKmFare');
            }
          }
        } catch (e) {
          debugPrint('Failed to fetch paths or matrix from API: $e');
        }
      }

      // Fallback to cache if API failed or device is offline
      pathsJson ??= prefs.getString('cached_paths');

      if (pathsJson != null) {
        _paths = json.decode(pathsJson) as List;
      } else {
        // Final fallback to bundled assets if no cache exists (first launch offline)
        final stopsStr = await rootBundle.loadString('assets/stops.json');
        _paths = [
          {'id': 1, 'name': 'Primary', 'stops': json.decode(stopsStr)},
        ];
        debugPrint('Loaded default paths from assets.');
      }

      _evaluateActivePath();

      // Restore fare matrix from cache if offline
      if (!connectivityService.isOnline) {
        final cachedBase = prefs.getDouble('cached_base_fare') ?? 15.0;
        final cachedPerKm = prefs.getDouble('cached_per_km_fare') ?? 2.5;
        sessionService.updateFareMatrix(cachedBase, cachedPerKm);
      }
    } catch (e) {
      debugPrint('Critical error loading stops or fares: $e');
    }

    // Phase 3 — GPS + Proximity
    await gpsService.init(_stops);
    proximityService.onAlarmTriggered = _onProximityAlarm;

    // Initialise local notifications
    await _initNotifications();

    // Setup USB stream listeners
    _passengerSub = usbService.passengerStream.listen(_onPassengerLogReceived);
    _nfcTapSub = usbService.nfcTapStream.listen(_onNfcTapReceived);
    _buttonPressSub = usbService.buttonPressStream.listen(
      _onButtonPressReceived,
    );
    _releaseSub = usbService.releaseStream.listen(_onReleaseReceived);
    _slaveBatterySub = usbService.slaveBatteryStream.listen((event) {
      _slaveBatteryMap[event.slaveUid] = event.batteryPct;
      notifyListeners();
    });

    // Listen to connectivity state changes
    _connectivitySub = connectivityService.onConnectivityChanged.listen((
      online,
    ) {
      if (online) syncService.flushQueue();
      notifyListeners();
    });

    await refreshData();

    _statusTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      if (usbService.isConnected) usbService.requestStatus();
    });

    _scheduleTimer = Timer.periodic(const Duration(minutes: 1), (_) {
      _evaluateActivePath();
    });

    sessionService.addListener(notifyListeners);
    occupancyService.addListener(notifyListeners);
    gpsService.addListener(notifyListeners);
  }

  // ── GPS getters (proxied to UI) ──────────────────────────────
  TransitStop? get nearestStop => gpsService.nearestStop;
  bool get gpsPermissionGranted => gpsService.permissionGranted;
  bool get gpsTracking => gpsService.isTracking;
  String? get gpsError => gpsService.error;

  // ── Notifications setup ──────────────────────────────────────
  Future<void> _initNotifications() async {
    const android = AndroidInitializationSettings('@mipmap/ic_launcher');
    const settings = InitializationSettings(android: android);
    await _notifications.initialize(settings);

    // Create high-priority channel for alarms
    const channel = AndroidNotificationChannel(
      'toms_proximity_alarm',
      'TOMS Proximity Alarms',
      description: 'Alerts when a passenger is approaching their destination.',
      importance: Importance.high,
      playSound: true,
    );
    final plugin = _notifications
        .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin
        >();
    await plugin?.createNotificationChannel(channel);
  }

  // ── Proximity alarm handler ──────────────────────────────────
  Future<void> _onProximityAlarm(PassengerSession session) async {
    final slot = occupancyService.getSlotNumber(session.slaveUid);
    final fare = session.finalFareCentavos;
    final fareStr =
        '₱${fare ~/ 100}.${(fare % 100).toString().padLeft(2, '0')}';

    // Vibrate: SOS pattern (felt even if phone is silent)
    if (await Vibration.hasVibrator()) {
      Vibration.vibrate(pattern: [0, 400, 200, 400, 200, 800]);
    }

    // Post a persistent notification
    await _notifications.show(
      slot, // use slot number as notification ID so each slot has its own
      '⚠ Passenger #$slot approaching stop',
      'Collect ${session.type.label} fare $fareStr · ${session.destination.name}',
      NotificationDetails(
        android: AndroidNotificationDetails(
          'toms_proximity_alarm',
          'TOMS Proximity Alarms',
          channelDescription: 'Passenger approaching destination',
          importance: Importance.high,
          priority: Priority.high,
          ongoing: false,
          icon: '@mipmap/ic_launcher',
        ),
      ),
    );

    notifyListeners(); // refresh banner

    // ── Phase 5: Hardware alarm ──────────────────────────────────────────────
    // Also trigger the slave's physical display alarm via Master's ESP-NOW relay.
    // Estimate remaining minutes from current GPS distance at ~30 km/h average.
    if (usbService.isConnected) {
      final pos = gpsService.currentPosition;
      int estimatedMinutes = 2; // safe default
      if (pos != null) {
        final distanceM = Geolocator.distanceBetween(
          pos.latitude,
          pos.longitude,
          session.destination.lat,
          session.destination.lon,
        );
        // 30 km/h = 500 m/min → convert distance to minutes, floor at 1
        estimatedMinutes = (distanceM / 500).ceil().clamp(1, 99);
      }

      // alarm_type 0 = approaching (show minutes countdown on slave screen)
      await usbService.sendAlarmCommand(session.slaveUid, 0, estimatedMinutes);
    }
  }

  // ── USB Event Handlers ───────────────────────────────────────
  Future<void> _onPassengerLogReceived(PassengerLog log) async {
    await dbService.insertLog(log);
    await refreshData();
  }

  void _onNfcTapReceived(NfcTapEvent tap) {
    final uid = tap.slaveUid.replaceAll(':', '');
    final activeSession = sessionService.getSessionBySlave(uid);
    if (activeSession != null) {
      _executeRelease(uid);
    } else {
      _executeBoard(uid);
    }
  }

  void _onButtonPressReceived(String uid) {
    final activeSession = sessionService.getSessionBySlave(uid);
    if (activeSession != null) {
      sessionService.markPaid(uid);
      _pushPaymentEvent(uid, activeSession);
    } else {
      _executeBoard(uid);
    }
  }

  void _onReleaseReceived(String uid) {
    _executeRelease(uid);
  }

  // ── Boarding Logic ───────────────────────────────────────────
  /// Boards next queued passenger to the given slave UID.
  /// Respects manual target if set, otherwise falls back to FIFO.
  void _executeBoard(String uid) {
    PendingPassenger? target;

    // Check manual assignment target first
    if (_manualAssignTarget != null) {
      try {
        target = sessionService.pendingQueue.firstWhere(
          (p) => p.id == _manualAssignTarget,
        );
        _manualAssignTarget = null;
      } catch (_) {
        _manualAssignTarget = null; // target was removed — fall through
      }
    }

    // FIFO fallback
    target ??= sessionService.pendingQueue.isNotEmpty
        ? sessionService.pendingQueue.first
        : null;

    if (target != null) {
      final session = sessionService.assignSlave(target.id, uid);
      if (session != null) {
        _sendBoardCommand(uid, session);
        _pushBoardEvent(uid, session);
      }
    } else {
      _requestingUid = uid;
      notifyListeners();
    }
  }

  void _executeRelease(String uid) {
    final session = sessionService.getSessionBySlave(uid);
    if (session == null) return;

    final slotNum = occupancyService.getSlotNumber(uid);

    _pushSyncEvent('release', uid, session, slotNum);

    final log = PassengerLog(
      timestamp: DateTime.now().millisecondsSinceEpoch ~/ 1000,
      boardingType: 1,
      fareCentavos: session.finalFareCentavos,
      seatNumber: slotNum,
      routeId: session.boarding.id,
      passengerId: uid,
      synced: false,
      passengerType: session.type.index,
      discountCentavos: sessionService.calculateDiscount(
        session.baseFareCentavos,
        session.type,
      ),
      boardingStop: session.boarding.name,
      destinationStop: session.destination.name,
      destinationLat: session.destination.lat,
      destinationLon: session.destination.lon,
    );

    // Send remote release to Slave
    usbService.sendForceReleaseCommand(uid);

    dbService.insertLog(log);
    sessionService.releaseSlot(uid);
    refreshData();
  }

  void _sendBoardCommand(String uid, PassengerSession session) {
    final slotNum = occupancyService.getSlotNumber(uid);
    usbService.sendCommand('board', {
      'uid': uid,
      'fare': session.finalFareCentavos,
      'seat': slotNum,
      'route': _activeRouteId,
      'vehicle_id': occupancyService.vehicleId,
      'origin': session.boarding.name,
      'destination': session.destination.name,
    });
  }

  void _pushBoardEvent(String uid, PassengerSession session) {
    final slotNum = occupancyService.getSlotNumber(uid);
    _pushSyncEvent('board', uid, session, slotNum);
  }

  void _pushPaymentEvent(String uid, PassengerSession session) {
    final slotNum = occupancyService.getSlotNumber(uid);
    _pushSyncEvent('payment', uid, session, slotNum);
  }

  void _pushSyncEvent(
    String type,
    String uid,
    PassengerSession session,
    int slotNum,
  ) async {
    final seatMapJson = jsonEncode(
      occupancyService.getPassengerSlots().map((s) => s.toJson()).toList(),
    );

    final prefs = await SharedPreferences.getInstance();
    final conductorName = prefs.getString('conductor_name');
    final conductorId = prefs.getString('conductor_id') ?? 'UNKNOWN';

    syncService.push(
      type,
      vehicleId: occupancyService.vehicleId,
      route: _activePathName,
      slotNumber: slotNum,
      slaveUid: uid,
      passengerType: session.type.name,
      fareCentavos: session.finalFareCentavos,
      discountCentavos: sessionService.calculateDiscount(
        session.baseFareCentavos,
        session.type,
      ),
      boardingStop: session.boarding.name,
      destinationStop: session.destination.name,
      occupancyNow: occupancyService.slavesDeployed,
      maxCapacity: occupancyService.maxCapacity,
      seatMap: seatMapJson,
      conductorId: conductorId,
      conductorName: conductorName,
      currentLat: gpsService.currentPosition?.latitude,
      currentLon: gpsService.currentPosition?.longitude,
    );
  }

  // ── Public UI Actions ────────────────────────────────────────
  /// Queue a new passenger from the POS boarding form.
  void queuePassenger(
    TransitStop boarding,
    TransitStop destination,
    PassengerType type,
  ) {
    sessionService.addToQueue(boarding, destination, type);
    notifyListeners();
  }

  /// Set which queue item the next slave tap should be assigned to.
  void setManualTarget(String queueId) {
    _manualAssignTarget = queueId;
    notifyListeners();
  }

  void clearManualTarget() {
    _manualAssignTarget = null;
    notifyListeners();
  }

  /// Direct manual boarding when conductor fills in all data themselves.
  void assignManualPassenger(
    String uid,
    TransitStop boarding,
    TransitStop destination,
    PassengerType type,
  ) {
    sessionService.addToQueue(boarding, destination, type);
    final pending = sessionService.pendingQueue.last;
    final session = sessionService.assignSlave(pending.id, uid);
    if (session != null) {
      _sendBoardCommand(uid, session);
      _pushBoardEvent(uid, session);
    }
    if (_requestingUid == uid) _requestingUid = null;
    notifyListeners();
  }

  void markPaidManual(String uid) {
    final session = sessionService.getSessionBySlave(uid);
    if (session != null) {
      sessionService.markPaid(uid);
      proximityService.clearAlarm(uid);
      _pushPaymentEvent(uid, session);
    }
  }

  void releaseSlotManual(String uid) => _executeRelease(uid);

  void clearRequestingUid() {
    _requestingUid = null;
    notifyListeners();
  }

  Future<void> refreshData() async {
    _recentLogs = await dbService.getAllLogs(limit: 20);
    _todayRevenue = await dbService.getTodayRevenue();
    _todayPassengers = await dbService.getTodayPassengerCount();
    _totalLogs = await dbService.getLogCount();
    notifyListeners();
  }

  Future<void> connectUsb() async => usbService.connect();

  /// Push the active route fare configuration to the Master hardware.
  ///
  /// Should be called after vehicle assignment or whenever the route changes.
  /// The Master saves the fare dict to NVS and broadcasts it to all slaves
  /// via `TOMS_MSG_FARE_TABLE_UPDATE` over ESP-NOW.
  Future<void> syncFareConfigToMaster() async {
    if (!usbService.isConnected) {
      debugPrint('syncFareConfigToMaster: USB not connected, skipping');
      return;
    }

    final baseFare = (sessionService.baseFareCentavos).round();
    final perKm = (sessionService.perKmCentavos).round();
    final cap = occupancyService.maxCapacity;
    final waypoints = _stops.map((s) => s.name).toList();

    await usbService.syncFareTable(
      baseFareCentavos: baseFare,
      perKmCentavos: perKm,
      maxCapacity: cap,
      waypoints: waypoints,
    );

    debugPrint(
      'syncFareConfigToMaster: sent base=₱${baseFare / 100} '
      'per_km=₱${perKm / 100} cap=$cap waypoints=$waypoints',
    );
  }

  @override
  void dispose() {
    _passengerSub?.cancel();
    _nfcTapSub?.cancel();
    _buttonPressSub?.cancel();
    _releaseSub?.cancel();
    _connectivitySub?.cancel();
    _slaveBatterySub?.cancel();
    _statusTimer?.cancel();
    _scheduleTimer?.cancel();
    sessionService.removeListener(notifyListeners);
    occupancyService.removeListener(notifyListeners);
    gpsService.removeListener(notifyListeners);
    connectivityService.dispose();
    syncService.dispose();
    gpsService.dispose();
    proximityService.dispose();
    super.dispose();
  }

  void _evaluateActivePath() {
    if (_paths.isEmpty) return;

    final now = DateTime.now();
    final currentDay = [
      'mon',
      'tue',
      'wed',
      'thu',
      'fri',
      'sat',
      'sun',
    ][now.weekday - 1];
    final currentTimeStr =
        "${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}";

    Map<String, dynamic>? activePath;

    for (final p in _paths) {
      if (p['schedule'] != null) {
        final sched = p['schedule'];
        final days = (sched['active_days'] as String).toLowerCase();
        if (days.contains(currentDay)) {
          final start = sched['start_time'] as String;
          final end = sched['end_time'] as String;
          if (currentTimeStr.compareTo(start) >= 0 &&
              currentTimeStr.compareTo(end) <= 0) {
            activePath = p;
            break;
          }
        }
      }
    }

    // Fallback to Primary or first path
    activePath ??= _paths.firstWhere(
      (p) => p['name'] == 'Primary',
      orElse: () => _paths.first,
    );

    if (activePath != null && activePath['id'] != _activePathId) {
      _activePathId = activePath['id'];
      _activePathName = activePath['name'];

      final stopsList = activePath['stops'] as List? ?? [];
      final uniqueStops = <String, dynamic>{};
      for (final s in stopsList) {
        if (!uniqueStops.containsKey(s['name'])) uniqueStops[s['name']] = s;
      }

      final stopsJson = json.encode(uniqueStops.values.toList());
      _stops = _parseStopsFromJson(stopsJson);

      gpsService.init(_stops);
      notifyListeners();
    }
  }
}

// ── Background Isolate Parsers ───────────────────────────────────────────────

List<TransitStop> _parseStopsFromJson(String jsonStr) {
  final List<dynamic> stopsList = jsonDecode(jsonStr);
  return stopsList.map((j) => TransitStop.fromJson(j)).toList();
}
