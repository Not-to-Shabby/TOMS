import 'package:flutter_test/flutter_test.dart';
import 'package:toms_mobile/main.dart';

void main() {
  testWidgets('TOMS app launches', (WidgetTester tester) async {
    await tester.pumpWidget(const TomsApp());
    expect(find.text('TOMS'), findsWidgets);
  });
}
