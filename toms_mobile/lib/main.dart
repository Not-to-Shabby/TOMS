import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'theme/toms_theme.dart';
import 'services/usb_service.dart';
import 'services/database_service.dart';
import 'services/app_state.dart';
import 'screens/dashboard_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  final dbService = DatabaseService();
  final usbService = UsbService();

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: usbService),
        ChangeNotifierProvider(
          create: (_) => AppState(
            usbService: usbService,
            dbService: dbService,
          ),
        ),
      ],
      child: const TomsApp(),
    ),
  );
}

class TomsApp extends StatelessWidget {
  const TomsApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TOMS',
      debugShowCheckedModeBanner: false,
      theme: TomsTheme.darkTheme,
      home: const DashboardScreen(),
    );
  }
}
