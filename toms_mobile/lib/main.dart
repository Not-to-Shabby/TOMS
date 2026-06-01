import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'theme/toms_theme.dart';
import 'services/usb_service.dart';
import 'services/database_service.dart';
import 'services/app_state.dart';
import 'services/auth_service.dart';
import 'services/sync_service.dart';
import 'services/connectivity_service.dart';
import 'screens/home_shell.dart';
import 'screens/login_screen.dart';
import 'screens/vehicle_assignment_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final dbService = DatabaseService();
  final usbService = UsbService();
  final authService = AuthService();
  
  await authService.init();

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: usbService),
        ChangeNotifierProvider.value(value: authService),
        Provider.value(value: dbService),
        ChangeNotifierProvider(
          create: (context) => AppState(
            usbService: usbService,
            dbService: dbService,
            tokenGetter: () => authService.token ?? '',
          ),
        ),
        // Expose SyncService for the sync badge widget
        ChangeNotifierProxyProvider<AppState, SyncService>(
          create: (_) => SyncService(db: dbService, connectivity: ConnectivityService()),
          update: (_, appState, __) => appState.syncService,
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
      home: const AuthWrapper(),
    );
  }
}

class AuthWrapper extends StatelessWidget {
  const AuthWrapper({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<AuthService>(
      builder: (context, auth, _) {
        if (!auth.isAuthenticated) {
          return const LoginScreen();
        }
        
        if (!auth.hasVehicleAssigned) {
          return const VehicleAssignmentScreen();
        }
        
        return const HomeShell();
      },
    );
  }
}

