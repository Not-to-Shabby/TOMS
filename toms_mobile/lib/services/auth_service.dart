import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../config/env.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/foundation.dart';

class AuthService extends ChangeNotifier {
  final _secureStorage = const FlutterSecureStorage();
  
  String? _token;
  String? _companyId;
  String? _conductorId;
  String? _conductorName;
  String? _vehicleId; // Current vehicle assignment

  bool get isAuthenticated => _token != null && _token!.isNotEmpty;
  bool get hasVehicleAssigned => _vehicleId != null && _vehicleId!.isNotEmpty;

  String? get token => _token;
  String? get companyId => _companyId;
  String? get conductorId => _conductorId;
  String? get conductorName => _conductorName;
  String? get vehicleId => _vehicleId;

  Future<void> init() async {
    _token = await _secureStorage.read(key: 'auth_token');
    
    final prefs = await SharedPreferences.getInstance();
    _companyId = prefs.getString('company_id');
    _conductorId = prefs.getString('conductor_id');
    _conductorName = prefs.getString('conductor_name');
    _vehicleId = prefs.getString('vehicle_id');
    
    notifyListeners();
  }

  Future<bool> login(String username, String password) async {
    try {
      final response = await http.post(
        Uri.parse('${Env.apiBaseUrl}/api/conductors/login'),
        headers: {'Content-Type': 'application/json'},
        body: json.encode({'username': username, 'password': password}),
      ).timeout(const Duration(seconds: 5));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        _token = data['token'];
        final user = data['user'];
        _companyId = user['company_id'];
        _conductorId = user['id'].toString();
        _conductorName = user['name'];
        _vehicleId = user['assigned_vehicle'];

        await _secureStorage.write(key: 'auth_token', value: _token!);
        
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('company_id', _companyId!);
        await prefs.setString('conductor_id', _conductorId!);
        await prefs.setString('conductor_name', _conductorName!);
        if (_vehicleId != null && _vehicleId!.isNotEmpty) {
          await prefs.setString('vehicle_id', _vehicleId!);
        }

        notifyListeners();
        return true;
      }
    } catch (e) {
      debugPrint('Login API error: $e');
    }
    return false;
  }

  Future<void> assignVehicle(String vehicleId) async {
    _vehicleId = vehicleId;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('vehicle_id', vehicleId);
    notifyListeners();
  }

  Future<void> logout() async {
    _token = null;
    _companyId = null;
    _conductorName = null;
    _vehicleId = null;
    
    await _secureStorage.delete(key: 'auth_token');
    
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('company_id');
    await prefs.remove('conductor_name');
    await prefs.remove('vehicle_id');
    
    notifyListeners();
  }
}
