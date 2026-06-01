import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../services/usb_service.dart';
import '../services/sync_service.dart';
import '../models/models.dart';

class SyncReconciliationScreen extends StatefulWidget {
  const SyncReconciliationScreen({super.key});

  @override
  State<SyncReconciliationScreen> createState() => _SyncReconciliationScreenState();
}

class _SyncReconciliationScreenState extends State<SyncReconciliationScreen> {
  bool _isRefreshingLocal = false;

  Future<void> _refreshDiagnostics(BuildContext context) async {
    final usb = context.read<UsbService>();
    final messenger = ScaffoldMessenger.of(context);
    if (usb.isConnected) {
      await usb.sendCommand('get_status', {});
      if (!mounted) return;
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Requested fresh diagnostics from Master device.'),
          backgroundColor: TomsTheme.accentDim,
        ),
      );
    } else {
      if (!mounted) return;
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Master device not connected via USB.'),
          backgroundColor: TomsTheme.danger,
        ),
      );
    }
  }

  Future<void> _triggerCloudSync(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isRefreshingLocal = true);
    try {
      final app = context.read<AppState>();
      if (!app.connectivityService.isOnline) {
        if (!mounted) return;
        messenger.showSnackBar(
          const SnackBar(
            content: Text('Device is offline. Please check internet connection.'),
            backgroundColor: TomsTheme.warning,
          ),
        );
        return;
      }
      await app.syncService.flushQueue();
      if (!mounted) return;
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Cloud synchronization sequence executed.'),
          backgroundColor: TomsTheme.success,
        ),
      );
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('Sync failed: $e'),
          backgroundColor: TomsTheme.danger,
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _isRefreshingLocal = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = Provider.of<AppState>(context);
    final usb = Provider.of<UsbService>(context);
    final syncServ = Provider.of<SyncService>(context);
    final lastStatus = usb.lastStatus;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Sync Reconciliation'),
        actions: [
          IconButton(
            icon: const Icon(LucideIcons.refreshCw, size: 20),
            onPressed: () {
              _refreshDiagnostics(context);
              setState(() {});
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Connection Info Header
            _buildConnectionBanner(app, usb),
            const SizedBox(height: 20),

            // Side-by-Side Queue Cards
            LayoutBuilder(
              builder: (context, constraints) {
                if (constraints.maxWidth > 600) {
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: _buildMasterCard(usb, lastStatus)),
                      const SizedBox(width: 16),
                      Expanded(child: _buildMobileCard(syncServ)),
                    ],
                  );
                } else {
                  return Column(
                    children: [
                      _buildMasterCard(usb, lastStatus),
                      const SizedBox(height: 16),
                      _buildMobileCard(syncServ),
                    ],
                  );
                }
              },
            ),
            const SizedBox(height: 30),

            // Section Title
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Unsynced SQLite Logs',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                TextButton.icon(
                  onPressed: () => _triggerCloudSync(context),
                  icon: _isRefreshingLocal
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: TomsTheme.accent,
                          ),
                        )
                      : const Icon(LucideIcons.cloudLightning, size: 16),
                  label: const Text('Sync Queue Now'),
                  style: TextButton.styleFrom(foregroundColor: TomsTheme.accent),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Unsynced Logs List
            _buildUnsyncedLogsList(app),
          ],
        ),
      ),
    );
  }

  Widget _buildConnectionBanner(AppState app, UsbService usb) {
    final isOnline = app.connectivityService.isOnline;
    final isUsbConnected = usb.isConnected;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: TomsTheme.bgCard,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: TomsTheme.border),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          Row(
            children: [
              Icon(
                isOnline ? LucideIcons.wifi : LucideIcons.wifiOff,
                color: isOnline ? TomsTheme.success : TomsTheme.danger,
                size: 18,
              ),
              const SizedBox(width: 8),
              Text(
                isOnline ? 'Cloud: Online' : 'Cloud: Offline',
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 13,
                ),
              ),
            ],
          ),
          Container(
            width: 1,
            height: 24,
            color: TomsTheme.border,
          ),
          Row(
            children: [
              Icon(
                isUsbConnected ? LucideIcons.usb : LucideIcons.unplug,
                color: isUsbConnected ? TomsTheme.accent : TomsTheme.textSecondary,
                size: 18,
              ),
              const SizedBox(width: 8),
              Text(
                isUsbConnected ? 'Master: USB Connected' : 'Master: Disconnected',
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildMasterCard(UsbService usb, DeviceStatus? lastStatus) {
    final isUsbConnected = usb.isConnected;
    final pendingCount = isUsbConnected ? (lastStatus?.pendingLogs ?? 0) : 0;
    final used = isUsbConnected ? (lastStatus?.storageUsed ?? 0) : 0;
    final total = isUsbConnected ? (lastStatus?.storageTotal ?? 0) : 0;
    final pct = total > 0 ? (used / total) : 0.0;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Row(
                  children: [
                    Icon(LucideIcons.cpu, color: TomsTheme.accent, size: 22),
                    SizedBox(width: 10),
                    Text(
                      'Master Hardware',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: TomsTheme.textPrimary,
                      ),
                    ),
                  ],
                ),
                _buildStatusDot(isUsbConnected ? TomsTheme.success : TomsTheme.textSecondary),
              ],
            ),
            const SizedBox(height: 20),
            _buildStatRow('SPIFFS Pending Logs', '$pendingCount files', color: TomsTheme.warning),
            const SizedBox(height: 16),
            const Text(
              'SPIFFS Memory Utilization',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: TomsTheme.textSecondary,
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: pct,
                      backgroundColor: TomsTheme.bgDark,
                      valueColor: const AlwaysStoppedAnimation(TomsTheme.accent),
                      minHeight: 8,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Text(
                  '${(pct * 100).toStringAsFixed(1)}%',
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: TomsTheme.textPrimary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              '${(used / 1024).toStringAsFixed(1)} KB of ${(total / 1024).toStringAsFixed(1)} KB used',
              style: const TextStyle(fontSize: 11, color: TomsTheme.textSecondary),
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: isUsbConnected ? () => _refreshDiagnostics(context) : null,
                icon: const Icon(LucideIcons.searchCode, size: 16),
                label: const Text('Fetch Diagnostics'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: TomsTheme.bgCardLight,
                  foregroundColor: TomsTheme.accent,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                    side: const BorderSide(color: TomsTheme.border),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMobileCard(SyncService syncServ) {
    final pendingCount = syncServ.pendingSyncCount;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Row(
                  children: [
                    Icon(LucideIcons.smartphone, color: TomsTheme.accent, size: 22),
                    SizedBox(width: 10),
                    Text(
                      'Mobile SQLite App',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: TomsTheme.textPrimary,
                      ),
                    ),
                  ],
                ),
                _buildStatusDot(TomsTheme.success),
              ],
            ),
            const SizedBox(height: 20),
            _buildStatRow('SQLite Pending Logs', '$pendingCount events', color: TomsTheme.warning),
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(
                color: TomsTheme.bgDark,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: TomsTheme.border),
              ),
              child: const Row(
                children: [
                  Icon(LucideIcons.lock, color: TomsTheme.success, size: 14),
                  SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'Local DB Encrypted (SQLCipher)',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: TomsTheme.success,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 38), // match alignment/height to master card
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: () => _triggerCloudSync(context),
                icon: const Icon(LucideIcons.uploadCloud, size: 16),
                label: const Text('Flush Sync Queue'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: TomsTheme.accent,
                  foregroundColor: TomsTheme.bgDark,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatRow(String label, String value, {required Color color}) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: const TextStyle(
            fontSize: 13,
            color: TomsTheme.textSecondary,
            fontWeight: FontWeight.w500,
          ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(
            value,
            style: TextStyle(
              fontSize: 13,
              color: color,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildStatusDot(Color color) {
    return Container(
      width: 10,
      height: 10,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        boxShadow: [
          BoxShadow(
            color: color.withValues(alpha: 0.4),
            blurRadius: 6,
            spreadRadius: 2,
          ),
        ],
      ),
    );
  }

  Widget _buildUnsyncedLogsList(AppState app) {
    return FutureBuilder<List<PassengerLog>>(
      future: app.dbService.getUnsyncedLogs(),
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(40.0),
              child: CircularProgressIndicator(color: TomsTheme.accent),
            ),
          );
        }

        if (snapshot.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(20.0),
              child: Text(
                'Error loading pending logs: ${snapshot.error}',
                style: const TextStyle(color: TomsTheme.danger),
              ),
            ),
          );
        }

        final logs = snapshot.data ?? [];
        if (logs.isEmpty) {
          return Container(
            width: double.infinity,
            padding: const EdgeInsets.all(32.0),
            decoration: BoxDecoration(
              color: TomsTheme.bgCard,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: TomsTheme.border),
            ),
            child: const Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(LucideIcons.checkCircle2, color: TomsTheme.success, size: 48),
                SizedBox(height: 16),
                Text(
                  'Local Database Reconciled',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: TomsTheme.textPrimary,
                  ),
                ),
                SizedBox(height: 6),
                Text(
                  'All passenger transactions in SQLite are synced with cloud.',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: TomsTheme.textSecondary,
                  ),
                ),
              ],
            ),
          );
        }

        return ListView.separated(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: logs.length,
          separatorBuilder: (_, __) => const SizedBox(height: 10),
          itemBuilder: (context, index) {
            final log = logs[index];
            final timeStr = DateTime.fromMillisecondsSinceEpoch(log.timestamp * 1000)
                .toLocal()
                .toString()
                .substring(11, 16);

            return Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              decoration: BoxDecoration(
                color: TomsTheme.bgCard,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: TomsTheme.border),
              ),
              child: Row(
                children: [
                  Container(
                    width: 38,
                    height: 38,
                    decoration: BoxDecoration(
                      color: TomsTheme.warning.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: const Icon(LucideIcons.clock, color: TomsTheme.warning, size: 18),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          log.destinationStop.isNotEmpty
                              ? log.destinationStop
                              : 'Route ${log.routeId}',
                          style: const TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: TomsTheme.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Seat ${log.seatNumber} · $timeStr',
                          style: const TextStyle(
                            fontSize: 11,
                            color: TomsTheme.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        log.fareFormatted,
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: TomsTheme.accent,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          Container(
                            width: 6,
                            height: 6,
                            decoration: const BoxDecoration(
                              color: TomsTheme.warning,
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 4),
                          const Text(
                            'Unsynced',
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.w600,
                              color: TomsTheme.warning,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }
}
