/// TOMS data models for local database and USB protocol.

class PassengerLog {
  final int? id;
  final int timestamp;
  final int boardingType;
  final int fareCentavos;
  final int seatNumber;
  final int routeId;
  final String passengerId;
  final bool synced;

  PassengerLog({
    this.id,
    required this.timestamp,
    required this.boardingType,
    required this.fareCentavos,
    required this.seatNumber,
    required this.routeId,
    required this.passengerId,
    this.synced = false,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'timestamp': timestamp,
      'boarding_type': boardingType,
      'fare_centavos': fareCentavos,
      'seat_number': seatNumber,
      'route_id': routeId,
      'passenger_id': passengerId,
      'synced': synced ? 1 : 0,
    };
  }

  factory PassengerLog.fromMap(Map<String, dynamic> map) {
    return PassengerLog(
      id: map['id'] as int?,
      timestamp: map['timestamp'] as int,
      boardingType: map['boarding_type'] as int,
      fareCentavos: map['fare_centavos'] as int,
      seatNumber: map['seat_number'] as int,
      routeId: map['route_id'] as int,
      passengerId: map['passenger_id'] as String,
      synced: (map['synced'] as int) == 1,
    );
  }

  /// Parse from the Master's JSON USB event.
  factory PassengerLog.fromUsbJson(Map<String, dynamic> json) {
    return PassengerLog(
      timestamp: json['timestamp'] as int? ?? 0,
      boardingType: json['boarding_type'] as int? ?? 0,
      fareCentavos: json['fare_centavos'] as int? ?? 0,
      seatNumber: json['seat'] as int? ?? 0,
      routeId: json['route'] as int? ?? 0,
      passengerId: json['passenger_id'] as String? ?? '',
    );
  }

  String get fareFormatted {
    final pesos = fareCentavos ~/ 100;
    final cents = fareCentavos % 100;
    return '₱$pesos.${cents.toString().padLeft(2, '0')}';
  }
}

class DeviceStatus {
  final String mac;
  final int batteryMv;
  final int batteryPct;
  final int storageTotal;
  final int storageUsed;
  final int pendingLogs;
  final int uartState;

  DeviceStatus({
    required this.mac,
    required this.batteryMv,
    required this.batteryPct,
    required this.storageTotal,
    required this.storageUsed,
    required this.pendingLogs,
    required this.uartState,
  });

  factory DeviceStatus.fromJson(Map<String, dynamic> json) {
    return DeviceStatus(
      mac: json['mac'] as String? ?? '',
      batteryMv: json['battery_mv'] as int? ?? 0,
      batteryPct: json['battery_pct'] as int? ?? 0,
      storageTotal: json['storage_total'] as int? ?? 0,
      storageUsed: json['storage_used'] as int? ?? 0,
      pendingLogs: json['pending_logs'] as int? ?? 0,
      uartState: json['uart_state'] as int? ?? 0,
    );
  }
}
