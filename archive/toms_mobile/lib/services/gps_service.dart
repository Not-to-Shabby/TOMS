import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import '../models/models.dart';

/// Continuously tracks the device's GPS position and finds the nearest
/// transit stop from the route's stop list. Consumed by BoardingSheet
/// (auto-fill boarding stop) and ProximityService (geofence monitoring).
class GpsService extends ChangeNotifier {
  // ── State ──────────────────────────────────────────────────────────────────
  Position? _currentPosition;
  TransitStop? _nearestStop;
  bool _permissionGranted = false;
  bool _isTracking = false;
  String? _error;

  Position? get currentPosition => _currentPosition;
  TransitStop? get nearestStop => _nearestStop;
  bool get permissionGranted => _permissionGranted;
  bool get isTracking => _isTracking;
  String? get error => _error;

  // ── Private ────────────────────────────────────────────────────────────────
  StreamSubscription<Position>? _positionSub;
  List<TransitStop> _stops = [];

  // Position stream settings — balanced for battery vs. accuracy
  static const _locationSettings = LocationSettings(
    accuracy: LocationAccuracy.high,
    distanceFilter: 20, // update every 20 m moved
  );

  // ── Initialise ─────────────────────────────────────────────────────────────

  /// Call once from AppState after stops are loaded.
  Future<void> init(List<TransitStop> stops) async {
    _stops = stops;
    await _requestPermission();
    if (_permissionGranted) await startTracking();
  }

  Future<void> _requestPermission() async {
    bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      _error = 'Location services are disabled on this device.';
      notifyListeners();
      return;
    }

    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }

    if (permission == LocationPermission.denied ||
        permission == LocationPermission.deniedForever) {
      _error = 'Location permission denied. Boarding stop auto-fill is unavailable.';
      notifyListeners();
      return;
    }

    _permissionGranted = true;
    _error = null;
    notifyListeners();
  }

  // ── Tracking ───────────────────────────────────────────────────────────────

  Future<void> startTracking() async {
    if (_isTracking) return;
    _isTracking = true;
    notifyListeners();

    _positionSub = Geolocator.getPositionStream(
      locationSettings: _locationSettings,
    ).listen(
      (pos) {
        _currentPosition = pos;
        _updateNearestStop();
        notifyListeners();
      },
      onError: (e) {
        _error = 'GPS error: $e';
        _isTracking = false;
        notifyListeners();
      },
    );
  }

  void stopTracking() {
    _positionSub?.cancel();
    _positionSub = null;
    _isTracking = false;
    notifyListeners();
  }

  // ── Nearest stop logic ─────────────────────────────────────────────────────

  void _updateNearestStop() {
    if (_currentPosition == null || _stops.isEmpty) return;

    TransitStop? closest;
    double minDist = double.infinity;

    for (final stop in _stops) {
      final d = Geolocator.distanceBetween(
        _currentPosition!.latitude,
        _currentPosition!.longitude,
        stop.lat,
        stop.lon,
      );
      if (d < minDist) {
        minDist = d;
        closest = stop;
      }
    }

    if (closest != null && closest != _nearestStop) {
      _nearestStop = closest;
    }
  }

  /// Returns distance in meters from current position to a given stop.
  /// Returns null if position not yet known.
  double? distanceTo(TransitStop stop) {
    if (_currentPosition == null) return null;
    return Geolocator.distanceBetween(
      _currentPosition!.latitude,
      _currentPosition!.longitude,
      stop.lat,
      stop.lon,
    );
  }

  @override
  void dispose() {
    stopTracking();
    super.dispose();
  }
}
