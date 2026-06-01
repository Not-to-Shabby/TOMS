import 'package:flutter_test/flutter_test.dart';
import 'package:toms_mobile/models/models.dart';

void main() {
  group('Phase 6c: Sync & Advanced Config Tests', () {
    test('DeviceStatus correctly parses maxCapacity and waypoints from status JSON', () {
      final json = {
        'mac': '00:11:22:33:44:55',
        'battery_mv': 3700,
        'battery_pct': 85,
        'storage_total': 204800,
        'storage_used': 10240,
        'pending_logs': 5,
        'nfc_state': 1,
        'max_capacity': 30,
        'waypoints': ['Buru-un', 'Fuentes', 'Nunucan'],
      };

      final status = DeviceStatus.fromJson(json);

      expect(status.mac, equals('00:11:22:33:44:55'));
      expect(status.batteryMv, equals(3700));
      expect(status.batteryPct, equals(85));
      expect(status.storageTotal, equals(204800));
      expect(status.storageUsed, equals(10240));
      expect(status.pendingLogs, equals(5));
      expect(status.nfcState, equals(1));
      expect(status.maxCapacity, equals(30));
      expect(status.waypoints, equals(['Buru-un', 'Fuentes', 'Nunucan']));
    });

    test('DeviceStatus handles missing maxCapacity and waypoints gracefully', () {
      final json = {
        'mac': '00:11:22:33:44:55',
        'battery_mv': 3700,
        'battery_pct': 85,
        'storage_total': 204800,
        'storage_used': 10240,
        'pending_logs': 5,
        'nfc_state': 1,
      };

      final status = DeviceStatus.fromJson(json);

      expect(status.maxCapacity, equals(20)); // default capacity
      expect(status.waypoints, isEmpty);
    });
  });
}
