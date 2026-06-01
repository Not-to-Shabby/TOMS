import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';
import '../models/models.dart';

class SessionService extends ChangeNotifier {
  final List<PassengerSession> _activeSessions = [];
  final List<PendingPassenger> _pendingQueue = [];

  List<PassengerSession> get activeSessions => _activeSessions;
  List<PendingPassenger> get pendingQueue => _pendingQueue;

  double routeBaseFare = 15.0;
  double routePerKmFare = 2.5;

  static const _uuid = Uuid();

  void updateFareMatrix(double base, double perKm) {
    routeBaseFare = base;
    routePerKmFare = perKm;
    notifyListeners();
  }

  /// Calculates Haversine distance in meters.
  double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
    const r = 6371000;
    final phi1 = lat1 * pi / 180;
    final phi2 = lat2 * pi / 180;
    final deltaPhi = (lat2 - lat1) * pi / 180;
    final deltaLambda = (lon2 - lon1) * pi / 180;

    final a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
        cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2);
    final c = 2 * atan2(sqrt(a), sqrt(1 - a));

    return r * c;
  }

  int calculateBaseFare(TransitStop origin, TransitStop destination) {
    final distMeters = calculateDistance(origin.lat, origin.lon, destination.lat, destination.lon);
    final distKm = distMeters / 1000.0;
    
    // Dynamic Matrix
    double rawFare = routeBaseFare;
    if (distKm > 4.0) {
      // Use exact integer km distance for pricing tiers according to standard matrices
      // (or ceiling if it's fractional, but we use the exact calculation as shown in the table)
      final extraKm = (distKm - 4.0).ceil();
      rawFare += (extraKm * routePerKmFare);
    }
    
    // Convert to centavos, we don't round the base fare yet because discount is applied to raw computation
    return (rawFare * 100).round();
  }

  int calculateFare(int baseFareCentavos, PassengerType type) {
    double rawFare = baseFareCentavos / 100.0;
    
    if (type != PassengerType.regular) {
      rawFare = rawFare * 0.80; // 20% discount
    }
    
    // LTFRB rules: round to the nearest whole Peso
    return rawFare.round() * 100;
  }

  int calculateDiscount(int baseFareCentavos, PassengerType type) {
    return baseFareCentavos - calculateFare(baseFareCentavos, type);
  }

  void addToQueue(TransitStop boarding, TransitStop destination, PassengerType type) {
    final baseFare = calculateBaseFare(boarding, destination);
    final finalFare = calculateFare(baseFare, type);
    
    final pending = PendingPassenger(
      id: _uuid.v4(),
      boarding: boarding,
      destination: destination,
      fareId: destination.id,
      fareCentavos: finalFare,
      type: type,
      queuedAt: DateTime.now(),
    );

    _pendingQueue.add(pending);
    notifyListeners();
  }

  void removeFromQueue(String id) {
    _pendingQueue.removeWhere((p) => p.id == id);
    notifyListeners();
  }

  PassengerSession? assignSlave(String queueId, String slaveUid) {
    final idx = _pendingQueue.indexWhere((p) => p.id == queueId);
    if (idx == -1) return null;

    final pending = _pendingQueue.removeAt(idx);
    
    // Remove any existing active session for this slave UID (recycled device)
    _activeSessions.removeWhere((s) => s.slaveUid == slaveUid);

    final session = PassengerSession(
      id: pending.id,
      slaveUid: slaveUid,
      boarding: pending.boarding,
      destination: pending.destination,
      fareId: pending.fareId,
      baseFareCentavos: calculateBaseFare(pending.boarding, pending.destination),
      finalFareCentavos: pending.fareCentavos,
      type: pending.type,
      boardedAt: DateTime.now(),
      paid: false,
      alarmTriggered: false,
    );

    _activeSessions.add(session);
    notifyListeners();
    return session;
  }

  PassengerSession? getSessionBySlave(String slaveUid) {
    try {
      return _activeSessions.firstWhere((s) => s.slaveUid == slaveUid);
    } catch (_) {
      return null;
    }
  }

  bool markPaid(String slaveUid) {
    final session = getSessionBySlave(slaveUid);
    if (session != null) {
      session.paid = true;
      notifyListeners();
      return true;
    }
    return false;
  }

  PassengerSession? releaseSlot(String slaveUid) {
    final idx = _activeSessions.indexWhere((s) => s.slaveUid == slaveUid);
    if (idx == -1) return null;
    final session = _activeSessions.removeAt(idx);
    notifyListeners();
    return session;
  }

  void clearAll() {
    _activeSessions.clear();
    _pendingQueue.clear();
    notifyListeners();
  }
}
