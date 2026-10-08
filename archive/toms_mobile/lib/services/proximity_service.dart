
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import '../models/models.dart';
import 'session_service.dart';
import 'gps_service.dart';

/// Monitors every active [PassengerSession]'s destination stop against the
/// device's current GPS position. When a session's destination is within
/// [alarmRadiusMeters], the session is flagged `alarmTriggered = true`,
/// which causes [OccupancyService] to set that slot's state to ALARMING.
///
/// The [ProximityAlarmBanner] on the dashboard surfaces ALARMING slots
/// automatically; no additional wiring needed.
class ProximityService extends ChangeNotifier {
  final SessionService sessionService;
  final GpsService gpsService;

  ProximityService({
    required this.sessionService,
    required this.gpsService,
  }) {
    // Re-evaluate whenever GPS updates
    gpsService.addListener(_checkProximity);
  }

  // ── Internal state ─────────────────────────────────────────────────────────

  /// UIDs of slots that have already fired an alarm this session, so we
  /// don't fire the callback repeatedly for the same slot.
  final Set<String> _alreadyAlarmed = {};

  /// Callback invoked the first time a slot enters alarm range.
  /// Used by AppState to trigger vibration + local notification.
  void Function(PassengerSession session)? onAlarmTriggered;

  // ── Core check ─────────────────────────────────────────────────────────────

  void _checkProximity() {
    final pos = gpsService.currentPosition;
    if (pos == null) return;

    bool changed = false;

    for (final session in sessionService.activeSessions) {
      if (session.paid) continue; // paid → no need to alarm

      final distToDestination = Geolocator.distanceBetween(
        pos.latitude,
        pos.longitude,
        session.destination.lat,
        session.destination.lon,
      );

      final double dynamicRadius = session.destination.radiusM.toDouble();
      final withinRange = distToDestination <= dynamicRadius;

      if (withinRange && !session.alarmTriggered) {
        session.alarmTriggered = true;
        changed = true;

        // Fire callback only once per slot per trip
        if (!_alreadyAlarmed.contains(session.slaveUid)) {
          _alreadyAlarmed.add(session.slaveUid);
          onAlarmTriggered?.call(session);
        }
      }

      // If the bus moves AWAY (e.g. missed stop), clear alarm so conductor
      // can be re-alerted if they circle back. Hysteresis = 1.5× dynamicRadius.
      if (!withinRange &&
          session.alarmTriggered &&
          distToDestination > dynamicRadius * 1.5) {
        session.alarmTriggered = false;
        _alreadyAlarmed.remove(session.slaveUid);
        changed = true;
      }
    }

    if (changed) {
      sessionService.notifyListeners();
      notifyListeners();
    }
  }

  /// Clear alarm history for a specific slot (e.g. after conductor marks paid).
  void clearAlarm(String slaveUid) {
    _alreadyAlarmed.remove(slaveUid);
  }

  /// Reset for a new trip.
  void reset() {
    _alreadyAlarmed.clear();
    notifyListeners();
  }

  @override
  void dispose() {
    gpsService.removeListener(_checkProximity);
    super.dispose();
  }
}
