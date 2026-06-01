import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../services/auth_service.dart';
import '../theme/toms_theme.dart';
import 'package:lucide_icons/lucide_icons.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isLoading = false;
  String? _error;

  Future<void> _login() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });

    final success = await context.read<AuthService>().login(
      _usernameController.text.trim(),
      _passwordController.text,
    );

    if (mounted) {
      setState(() => _isLoading = false);
      if (!success) {
        setState(() => _error = 'Invalid credentials. Please Try Again.');
      }
    }
  }

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TomsTheme.bgDark,
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Icon(LucideIcons.bus, size: 64, color: TomsTheme.accent),
              const SizedBox(height: 16),
              const Text(
                'TOMS',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 2.0,
                  color: TomsTheme.textPrimary,
                ),
              ),
              const Text(
                'Transportation Occupancy Monitoring System',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 12,
                  color: TomsTheme.textSecondary,
                  letterSpacing: 0.5,
                ),
              ),
              const SizedBox(height: 48),

              if (_error != null)
                Container(
                  padding: const EdgeInsets.all(12),
                  margin: const EdgeInsets.only(bottom: 16),
                  decoration: BoxDecoration(
                    color: TomsTheme.danger.withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                      color: TomsTheme.danger.withValues(alpha: 0.5),
                    ),
                  ),
                  child: Row(
                    children: [
                      const Icon(
                        LucideIcons.alertCircle,
                        color: TomsTheme.danger,
                        size: 20,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          _error!,
                          style: const TextStyle(
                            color: TomsTheme.danger,
                            fontSize: 13,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

              TextField(
                controller: _usernameController,
                style: const TextStyle(color: TomsTheme.textPrimary),
                decoration: InputDecoration(
                  labelText: 'Username (Conductor Name)',
                  labelStyle: const TextStyle(color: TomsTheme.textSecondary),
                  prefixIcon: const Icon(
                    LucideIcons.user,
                    color: TomsTheme.textSecondary,
                  ),
                  filled: true,
                  fillColor: TomsTheme.bgCard,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide.none,
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(color: TomsTheme.accent),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _passwordController,
                obscureText: true,
                style: const TextStyle(color: TomsTheme.textPrimary),
                decoration: InputDecoration(
                  labelText: 'Password',
                  labelStyle: const TextStyle(color: TomsTheme.textSecondary),
                  prefixIcon: const Icon(
                    LucideIcons.lock,
                    color: TomsTheme.textSecondary,
                  ),
                  filled: true,
                  fillColor: TomsTheme.bgCard,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide.none,
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(color: TomsTheme.accent),
                  ),
                ),
              ),
              const SizedBox(height: 32),
              SizedBox(
                height: 56,
                child: ElevatedButton(
                  onPressed: _isLoading ? null : _login,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: TomsTheme.accent,
                    foregroundColor: TomsTheme.bgDark,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    elevation: 0,
                  ),
                  child: _isLoading
                      ? const SizedBox(
                          width: 24,
                          height: 24,
                          child: CircularProgressIndicator(
                            color: TomsTheme.bgDark,
                            strokeWidth: 2,
                          ),
                        )
                      : const Text(
                          'SIGN IN',
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
      ),
    );
  }
}
