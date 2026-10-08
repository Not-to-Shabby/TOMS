import 'package:flutter/material.dart';

/// TOMS data models for local database and USB protocol.

enum PassengerType { regular, student, pwd, senior }

extension PassengerTypeX on PassengerType {
  String get label => ['Regular', 'Student', 'PWD', 'Senior'][index];
  double get multiplier => [1.0, 0.80, 0.80, 0.80][index];
}

class TransitStop {
  final int id;
  final String name;
  final double lat;
  final double lon;
  final int radiusM;

  TransitStop({
    required this.id,
    required this.name,
    required this.lat,
    required this.lon,
    this.radiusM = 100,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'lat': lat,
      'lon': lon,
      'radius_m': radiusM,
    };
  }

  factory TransitStop.fromMap(Map<String, dynamic> map) {
    return TransitStop(
      id: map['id'] as int,
      name: map['name'] as String,
      lat: (map['lat'] as num).toDouble(),
      lon: (map['lon'] as num).toDouble(),
      radiusM: (map['radius_m'] as num?)?.toInt() ?? 100,
    );
  }

  factory TransitStop.fromJson(Map<String, dynamic> json) {
    return TransitStop(
      id: json['id'] as int,
      name: json['name'] as String,
      lat: (json['lat'] as num).toDouble(),
      lon: (json['lon'] as num).toDouble(),
      radiusM: (json['radius_m'] as num?)?.toInt() ?? 100,
    );
  }
}

class PassengerSession {
  final String id;              // UUID
  final String slaveUid;        // eFuse MAC of assigned slave
  final TransitStop boarding;
  final TransitStop destination;
  final int fareId;
  final int baseFareCentavos;
  final int finalFareCentavos;  // after discount
  final PassengerType type;
  final DateTime boardedAt;
  bool paid;
  bool alarmTriggered;

  PassengerSession({
    required this.id,
    required this.slaveUid,
    required this.boarding,
    required this.destination,
    required this.fareId,
    required this.baseFareCentavos,
    required this.finalFareCentavos,
    required this.type,
    required this.boardedAt,
    this.paid = false,
    this.alarmTriggered = false,
  });
}

class PendingPassenger {
  final String id;
  final TransitStop boarding;
  final TransitStop destination;
  final int fareId;
  final int fareCentavos;
  final PassengerType type;
  final DateTime queuedAt;

  PendingPassenger({
    required this.id,
    required this.boarding,
    required this.destination,
    required this.fareId,
    required this.fareCentavos,
    required this.type,
    required this.queuedAt,
  });
}

enum PassengerSlotState { active, paid, alarming }

extension PassengerSlotStateX on PassengerSlotState {
  Color get color => [
    const Color(0xFFFF9F43),   // active   → orange
    const Color(0xFF2ED573),   // paid     → green
    const Color(0xFFFF6B6B),   // alarming → red
  ][index];
  String get label => ['Unpaid', 'Paid', 'ALARM'][index];
}

class PassengerSlot {
  final int slotNumber;           // 1-based, order boarded
  final String slaveUid;          // eFuse MAC
  final PassengerSlotState state;
  final String destination;
  final int fareCentavos;
  final PassengerType passengerType;
  final DateTime boardedAt;

  PassengerSlot({
    required this.slotNumber,
    required this.slaveUid,
    required this.state,
    required this.destination,
    required this.fareCentavos,
    required this.passengerType,
    required this.boardedAt,
  });

  Map<String, dynamic> toJson() {
    return {
      'slot': slotNumber,
      'uid': slaveUid,
      'state': state.name,
      'dest': destination,
      'type': passengerType.name,
    };
  }
}

class OccupancySnapshot {
  final String vehicleId;
  final DateTime timestamp;
  final int maxCapacity;          // configured max (e.g. 20)
  final int slavesDeployed;       // currently handed out
  final int paidCount;
  final int unpaidCount;
  final int alarmingCount;
  double get occupancyRate => maxCapacity > 0 ? slavesDeployed / maxCapacity : 0.0;
  final List<PassengerSlot> slots;

  OccupancySnapshot({
    required this.vehicleId,
    required this.timestamp,
    required this.maxCapacity,
    required this.slavesDeployed,
    required this.paidCount,
    required this.unpaidCount,
    required this.alarmingCount,
    required this.slots,
  });
}

class NfcTapEvent {
  final String slaveUid;
  final DateTime timestamp;
  final int slotNumber;

  NfcTapEvent({
    required this.slaveUid,
    required this.timestamp,
    required this.slotNumber,
  });

  factory NfcTapEvent.fromJson(Map<String, dynamic> json) {
    return NfcTapEvent(
      slaveUid: json['uid'] as String? ?? '',
      timestamp: DateTime.now(),
      slotNumber: json['seat'] as int? ?? 0,
    );
  }
}

class PassengerLog {
  final int? id;
  final int timestamp;
  final int boardingType;
  final int fareCentavos;
  final int seatNumber;
  final int routeId;
  final String passengerId;
  final bool synced;

  // New fields:
  final int passengerType;        // PassengerType.index
  final int discountCentavos;
  final String boardingStop;
  final String destinationStop;
  final double destinationLat;
  final double destinationLon;

  PassengerLog({
    this.id,
    required this.timestamp,
    required this.boardingType,
    required this.fareCentavos,
    required this.seatNumber,
    required this.routeId,
    required this.passengerId,
    this.synced = false,
    this.passengerType = 0,
    this.discountCentavos = 0,
    this.boardingStop = '',
    this.destinationStop = '',
    this.destinationLat = 0.0,
    this.destinationLon = 0.0,
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
      'passenger_type': passengerType,
      'discount_centavos': discountCentavos,
      'boarding_stop': boardingStop,
      'destination_stop': destinationStop,
      'destination_lat': destinationLat,
      'destination_lon': destinationLon,
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
      passengerType: map['passenger_type'] as int? ?? 0,
      discountCentavos: map['discount_centavos'] as int? ?? 0,
      boardingStop: map['boarding_stop'] as String? ?? '',
      destinationStop: map['destination_stop'] as String? ?? '',
      destinationLat: (map['destination_lat'] as num?)?.toDouble() ?? 0.0,
      destinationLon: (map['destination_lon'] as num?)?.toDouble() ?? 0.0,
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
      passengerType: json['passenger_type'] as int? ?? 0,
      discountCentavos: json['discount_centavos'] as int? ?? 0,
      boardingStop: json['boarding_stop'] as String? ?? '',
      destinationStop: json['destination_stop'] as String? ?? '',
      destinationLat: (json['destination_lat'] as num?)?.toDouble() ?? 0.0,
      destinationLon: (json['destination_lon'] as num?)?.toDouble() ?? 0.0,
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
  final int nfcState;

  int get uartState => nfcState;

  DeviceStatus({
    required this.mac,
    required this.batteryMv,
    required this.batteryPct,
    required this.storageTotal,
    required this.storageUsed,
    required this.pendingLogs,
    required this.nfcState,
  });

  factory DeviceStatus.fromJson(Map<String, dynamic> json) {
    return DeviceStatus(
      mac: json['mac'] as String? ?? '',
      batteryMv: json['battery_mv'] as int? ?? 0,
      batteryPct: json['battery_pct'] as int? ?? 0,
      storageTotal: json['storage_total'] as int? ?? 0,
      storageUsed: json['storage_used'] as int? ?? 0,
      pendingLogs: json['pending_logs'] as int? ?? 0,
      nfcState: json['nfc_state'] as int? ?? json['uart_state'] as int? ?? 0,
    );
  }
}
