import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import '../config/env.dart';
import 'database_service.dart';
import 'connectivity_service.dart';

class SyncService extends ChangeNotifier {
  final DatabaseService db;
  final ConnectivityService connectivity;

  // Configurable server endpoint URL loaded from environment variables.
  String serverUrl = '${Env.apiBaseUrl}/api/events';

  /// Injected at runtime from AuthService so we never hardcode the token.
  String Function()? tokenGetter;

  String get _authToken => tokenGetter?.call() ?? '';

  Timer? _retryTimer;
  bool _flushing = false;
  int _pendingSyncCount = 0;

  /// Number of events waiting to be synced — used for UI badge.
  int get pendingSyncCount => _pendingSyncCount;

  SyncService({required this.db, required this.connectivity});

  void startRetryTimer() {
    _retryTimer?.cancel();
    _retryTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (connectivity.isOnline) {
        flushQueue();
      }
    });
  }

  Future<void> push(
    String eventType, {
    required String vehicleId,
    required String route,
    required int slotNumber,
    required String slaveUid,
    required String passengerType,
    required int fareCentavos,
    required int discountCentavos,
    required String boardingStop,
    required String destinationStop,
    required int occupancyNow,
    required int maxCapacity,
    String? seatMap,
    String? conductorId,
    String? conductorName,
    double? currentLat,
    double? currentLon,
  }) async {
    final Map<String, dynamic> event = {
      'event_type': eventType,
      'timestamp': DateTime.now().toIso8601String(),
      'vehicle_id': vehicleId,
      'route': route,
      'slot_number': slotNumber,
      'slave_uid': slaveUid,
      'passenger_type': passengerType,
      'fare_centavos': fareCentavos,
      'discount_centavos': discountCentavos,
      'boarding_stop': boardingStop,
      'destination_stop': destinationStop,
      'occupancy_now': occupancyNow,
      'max_capacity': maxCapacity,
      'seat_map': seatMap,
      'conductor_id': conductorId,
      'conductor_name': conductorName,
      'current_lat': currentLat,
      'current_lon': currentLon,
    };

    final payloadStr = jsonEncode(event);
    final timestampSecs = DateTime.now().millisecondsSinceEpoch ~/ 1000;

    // 1. Save to SQLite immediately (synced = 0)
    await db.insertEvent(eventType, timestampSecs, payloadStr);

    // 2. If online - try to push immediately
    if (connectivity.isOnline) {
      flushQueue();
    }
  }

  Future<void> flushQueue() async {
    if (_flushing) return;
    _flushing = true;

    try {
      final pending = await db.getUnsyncedEvents();
      _pendingSyncCount = pending.length;
      notifyListeners();

      if (pending.isEmpty) {
        _flushing = false;
        return;
      }

      final List<int> successfullySyncedIds = [];

      for (final item in pending) {
        final id = item['id'] as int;
        final payload = item['payload'] as String;

        final success = await _trySendNow(payload);
        if (success) {
          successfullySyncedIds.add(id);
        } else {
          // Stop on first failure to maintain chronological order
          break;
        }
      }

      if (successfullySyncedIds.isNotEmpty) {
        await db.markEventsSynced(successfullySyncedIds);
        _pendingSyncCount = pending.length - successfullySyncedIds.length;
        notifyListeners();
      }
    } catch (e) {
      debugPrint('Sync queue flush error: $e');
    } finally {
      _flushing = false;
    }
  }

  Future<bool> _trySendNow(String payload) async {
    try {
      final response = await http
          .post(
            Uri.parse(serverUrl),
            headers: {
              'Content-Type': 'application/json',
              'Authorization': 'Bearer $_authToken',
            },
            body: payload,
          )
          .timeout(const Duration(seconds: 5));

      return response.statusCode >= 200 && response.statusCode < 300;
    } catch (e) {
      debugPrint('Sync POST failed: $e');
      return false;
    }
  }

  @override
  void dispose() {
    _retryTimer?.cancel();
    super.dispose();
  }
}
