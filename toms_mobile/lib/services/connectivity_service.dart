import 'dart:async';
import 'package:connectivity_plus/connectivity_plus.dart';

class ConnectivityService {
  final Connectivity _connectivity = Connectivity();
  bool _isOnline = false;
  bool _forceOffline = false;

  bool get forceOffline => _forceOffline;

  set forceOffline(bool value) {
    if (_forceOffline != value) {
      _forceOffline = value;
      _controller.add(isOnline);
    }
  }

  bool get isOnline => _forceOffline ? false : _isOnline;

  final _controller = StreamController<bool>.broadcast();
  Stream<bool> get onConnectivityChanged => _controller.stream;

  StreamSubscription<List<ConnectivityResult>>? _subscription;

  Future<void> init() async {
    final results = await _connectivity.checkConnectivity();
    _isOnline = _checkIsOnline(results);

    _subscription = _connectivity.onConnectivityChanged.listen((results) {
      final online = _checkIsOnline(results);
      if (online != _isOnline) {
        _isOnline = online;
        _controller.add(online);
      }
    });
  }

  bool _checkIsOnline(List<ConnectivityResult> results) {
    return results.any((r) => r != ConnectivityResult.none);
  }

  void dispose() {
    _subscription?.cancel();
    _controller.close();
  }
}
