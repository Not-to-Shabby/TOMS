class Env {
  /// Base API URL for backend services.
  /// Defaults to Android emulator localhost alias if not provided via compile-time definition.
  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:3000',
  );
}
