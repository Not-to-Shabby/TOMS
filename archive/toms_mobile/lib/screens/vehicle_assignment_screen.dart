import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../services/auth_service.dart';
import '../services/app_state.dart';
import '../theme/toms_theme.dart';
import 'package:lucide_icons/lucide_icons.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;

class VehicleAssignmentScreen extends StatefulWidget {
  const VehicleAssignmentScreen({super.key});

  @override
  State<VehicleAssignmentScreen> createState() => _VehicleAssignmentScreenState();
}

class _VehicleAssignmentScreenState extends State<VehicleAssignmentScreen> {
  bool _isLoading = true;
  List<String> _vehicles = [];
  String? _selectedVehicle;

  @override
  void initState() {
    super.initState();
    _loadVehicles();
  }

  Future<void> _loadVehicles() async {
    try {
      final response = await http.get(Uri.parse('http://10.0.2.2:3000/api/vehicles')).timeout(const Duration(seconds: 5));
      if (response.statusCode == 200) {
        final List<dynamic> vehiclesList = jsonDecode(response.body);
        setState(() {
          _vehicles = vehiclesList.map((v) => v['id'] as String).toList();
          _isLoading = false;
        });
      } else {
        throw Exception('Failed to load vehicles');
      }
    } catch (e) {
      debugPrint('Error loading vehicles: $e');
      setState(() => _isLoading = false);
    }
  }

  void _assign() async {
    if (_selectedVehicle == null) return;
    
    // Assign in AuthService
    await context.read<AuthService>().assignVehicle(_selectedVehicle!);
    
    // Also notify AppState so it reloads its dependencies if needed
    if (mounted) {
      context.read<AppState>().occupancyService.vehicleId = _selectedVehicle!;
      await context.read<AppState>().initialize(); // Assuming we expose _init() as initialize()
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthService>();

    return Scaffold(
      backgroundColor: TomsTheme.bgDark,
      appBar: AppBar(
        backgroundColor: TomsTheme.bgDark,
        elevation: 0,
        title: const Text('Shift Setup', style: TextStyle(color: TomsTheme.textPrimary)),
        actions: [
          IconButton(
            icon: const Icon(LucideIcons.logOut, color: TomsTheme.textSecondary),
            onPressed: () => context.read<AuthService>().logout(),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator(color: TomsTheme.accent))
          : Padding(
              padding: const EdgeInsets.all(24.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(LucideIcons.bus, size: 48, color: TomsTheme.textSecondary),
                  const SizedBox(height: 24),
                  const Text(
                    'Select Vehicle',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 24,
                      fontWeight: FontWeight.w800,
                      color: TomsTheme.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Welcome back, ${auth.conductorName}.\nWhich bus are you operating today?',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 14,
                      color: TomsTheme.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 48),
                  
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    decoration: BoxDecoration(
                      color: TomsTheme.bgCard,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: TomsTheme.border),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _selectedVehicle,
                        hint: const Text('Select a vehicle...', style: TextStyle(color: TomsTheme.textSecondary)),
                        dropdownColor: TomsTheme.bgCard,
                        icon: const Icon(LucideIcons.chevronDown, color: TomsTheme.textSecondary),
                        isExpanded: true,
                        items: _vehicles.map((v) => DropdownMenuItem(
                          value: v,
                          child: Text(v, style: const TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w600)),
                        )).toList(),
                        onChanged: (val) => setState(() => _selectedVehicle = val),
                      ),
                    ),
                  ),
                  
                  const Spacer(),
                  
                  SizedBox(
                    height: 56,
                    child: ElevatedButton(
                      onPressed: _selectedVehicle == null ? null : _assign,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: TomsTheme.accent,
                        foregroundColor: TomsTheme.bgDark,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                        elevation: 0,
                      ),
                      child: const Text(
                        'START SHIFT',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w800,
                          letterSpacing: 1.5,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
    );
  }
}
