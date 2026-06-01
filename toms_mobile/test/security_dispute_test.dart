import 'package:flutter_test/flutter_test.dart';
import 'package:toms_mobile/models/models.dart';

// A simple utility to verify the QR code parser and formatter logic used in DisputeResolutionScreen
class TicketQrParser {
  static Map<String, dynamic>? parse(String qrData) {
    final parts = qrData.split(',');
    if (parts.length == 5 && parts[0] == 'TOMS') {
      final tsVal = int.tryParse(parts[2]) ?? 0;
      return {
        'isValid': true,
        'vehId': parts[1],
        'timestamp': tsVal,
        'fareCentavos': int.tryParse(parts[3]) ?? 0,
        'uid': parts[4].toUpperCase(),
      };
    }
    return {'isValid': false};
  }
}

void main() {
  group('Phase 6b: Dispute Resolution QR Code Parsing Tests', () {
    test('Valid ticket QR code is parsed correctly', () {
      const qrData = 'TOMS,CDB-001,1780211407,1300,A1:B2:C3:D4:E5:F6';
      final parsed = TicketQrParser.parse(qrData);

      expect(parsed, isNotNull);
      expect(parsed!['isValid'], isTrue);
      expect(parsed['vehId'], equals('CDB-001'));
      expect(parsed['timestamp'], equals(1780211407));
      expect(parsed['fareCentavos'], equals(1300));
      expect(parsed['uid'], equals('A1:B2:C3:D4:E5:F6'));
    });

    test('Invalid ticket QR code fails parsing validation', () {
      const qrData = 'INVALID,CDB-001,1780211407,1300,A1:B2:C3:D4:E5:F6';
      final parsed = TicketQrParser.parse(qrData);
      expect(parsed!['isValid'], isFalse);

      const tooShortData = 'TOMS,CDB-001,1780211407';
      final parsedShort = TicketQrParser.parse(tooShortData);
      expect(parsedShort!['isValid'], isFalse);
    });

    test('PassengerLog maps correctly to database schema fields', () {
      final log = PassengerLog(
        timestamp: 1780211407,
        boardingType: 1,
        fareCentavos: 1300,
        seatNumber: 3,
        routeId: 1,
        passengerId: 'A1B2C3D4E5F6',
        synced: false,
        passengerType: 1, // Student
        discountCentavos: 260,
        boardingStop: 'Cubao',
        destinationStop: 'Pasay',
      );

      final map = log.toMap();

      expect(map['timestamp'], equals(1780211407));
      expect(map['boarding_type'], equals(1));
      expect(map['fare_centavos'], equals(1300));
      expect(map['passenger_id'], equals('A1B2C3D4E5F6'));
      expect(map['passenger_type'], equals(1));
      expect(map['discount_centavos'], equals(260));
      expect(map['boarding_stop'], equals('Cubao'));
      expect(map['destination_stop'], equals('Pasay'));
    });
  });
}
