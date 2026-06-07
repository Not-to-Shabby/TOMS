import 'package:flutter/foundation.dart';
import '../models/models.dart';
import 'session_service.dart';

class OccupancyService extends ChangeNotifier {
  final SessionService sessionService;
  int maxCapacity = 20; // Default capacity, configurable
  String vehicleId = '';

  // Map of slaveUid to slotNumber
  final Map<String, int> _slotMappings = {};
  int _nextSlotNumber = 1;

  OccupancyService({required this.sessionService}) {
    sessionService.addListener(_onSessionsChanged);
  }

  void _onSessionsChanged() {
    // Clean up slot mappings for any sessions that were removed (released)
    final activeUids = sessionService.activeSessions.map((s) => s.slaveUid).toSet();
    _slotMappings.removeWhere((uid, _) => !activeUids.contains(uid));
    
    // Assign slot numbers to any new sessions
    for (final session in sessionService.activeSessions) {
      if (!_slotMappings.containsKey(session.slaveUid)) {
        _slotMappings[session.slaveUid] = _nextSlotNumber++;
      }
    }
    
    if (sessionService.activeSessions.isEmpty) {
      // Reset slot numbers when vehicle becomes empty
      _nextSlotNumber = 1;
    }
    
    notifyListeners();
  }

  int getSlotNumber(String slaveUid) {
    return _slotMappings[slaveUid] ?? 0;
  }

  int get slavesDeployed => sessionService.activeSessions.length;
  int get paidCount => sessionService.activeSessions.where((s) => s.paid).length;
  int get unpaidCount => sessionService.activeSessions.where((s) => !s.paid).length;
  int get alarmingCount => sessionService.activeSessions.where((s) => s.alarmTriggered && !s.paid).length;

  double get occupancyRate => maxCapacity > 0 ? slavesDeployed / maxCapacity : 0.0;

  List<PassengerSlot> getPassengerSlots({
    Map<String, int>? batteryMap,
  }) {
    return sessionService.activeSessions.map((session) {
      final slotNum = getSlotNumber(session.slaveUid);
      PassengerSlotState state = PassengerSlotState.active;
      if (session.paid) {
        state = PassengerSlotState.paid;
      } else if (session.alarmTriggered) {
        state = PassengerSlotState.alarming;
      }

      // Normalise UID key to match the format stored by AppState's battery map
      final uidKey = session.slaveUid.replaceAll(':', '').toUpperCase();
      final batt = batteryMap?[uidKey] ?? -1;

      return PassengerSlot(
        slotNumber: slotNum,
        slaveUid: session.slaveUid,
        state: state,
        destination: session.destination.name,
        fareCentavos: session.finalFareCentavos,
        passengerType: session.type,
        boardedAt: session.boardedAt,
        batteryPct: batt,
      );
    }).toList()
      ..sort((a, b) => a.slotNumber.compareTo(b.slotNumber));
  }

  OccupancySnapshot getSnapshot() {
    return OccupancySnapshot(
      vehicleId: vehicleId,
      timestamp: DateTime.now(),
      maxCapacity: maxCapacity,
      slavesDeployed: slavesDeployed,
      paidCount: paidCount,
      unpaidCount: unpaidCount,
      alarmingCount: alarmingCount,
      slots: getPassengerSlots(),
    );
  }

  void reset() {
    _slotMappings.clear();
    _nextSlotNumber = 1;
    sessionService.clearAll();
    notifyListeners();
  }
}
