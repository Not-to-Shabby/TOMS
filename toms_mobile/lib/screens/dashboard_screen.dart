import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../services/usb_service.dart';
import '../models/models.dart';
import 'debug_screen.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(6),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [TomsTheme.accent, Color(0xFF0088FF)],
                ),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(LucideIcons.bus, size: 18, color: Colors.white),
            ),
            const SizedBox(width: 10),
            const Text('TOMS'),
          ],
        ),
        actions: [
          Consumer<UsbService>(
            builder: (_, usb, __) => _ConnectionChip(connected: usb.isConnected),
          ),
          const SizedBox(width: 8),
          IconButton(
            icon: const Icon(LucideIcons.terminal, size: 20),
            tooltip: 'Debug Console',
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const DebugScreen()),
              );
            },
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: RefreshIndicator(
        color: TomsTheme.accent,
        onRefresh: () => context.read<AppState>().refreshData(),
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _DeviceStatusCard(),
              const SizedBox(height: 16),
              _StatsRow(),
              const SizedBox(height: 16),
              Text(
                'RECENT TRANSACTIONS',
                style: Theme.of(context).textTheme.labelLarge,
              ),
              const SizedBox(height: 12),
              _TransactionList(),
            ],
          ),
        ),
      ),
      floatingActionButton: Consumer<UsbService>(
        builder: (context, usb, _) {
          return FloatingActionButton.extended(
            backgroundColor: usb.isConnected ? TomsTheme.bgCardLight : TomsTheme.accent,
            icon: Icon(
              usb.isConnected ? LucideIcons.unplug : LucideIcons.plug,
              size: 20,
            ),
            label: Text(usb.isConnected ? 'Connected' : 'Connect USB'),
            onPressed: () {
              if (!usb.isConnected) {
                context.read<AppState>().connectUsb();
              } else {
                usb.disconnect();
              }
            },
          );
        },
      ),
    );
  }
}

/// USB connection indicator chip.
class _ConnectionChip extends StatelessWidget {
  final bool connected;
  const _ConnectionChip({required this.connected});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: connected
            ? TomsTheme.success.withOpacity(0.15)
            : TomsTheme.danger.withOpacity(0.15),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: connected ? TomsTheme.success : TomsTheme.danger,
          width: 1,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: connected ? TomsTheme.success : TomsTheme.danger,
            ),
          ),
          const SizedBox(width: 6),
          Text(
            connected ? 'ONLINE' : 'OFFLINE',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: connected ? TomsTheme.success : TomsTheme.danger,
              letterSpacing: 1,
            ),
          ),
        ],
      ),
    );
  }
}

/// Device status card: battery, dock, storage.
class _DeviceStatusCard extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer2<UsbService, AppState>(
      builder: (context, usb, app, _) {
        final status = usb.lastStatus;

        return Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(LucideIcons.cpu, size: 16, color: TomsTheme.accent),
                    const SizedBox(width: 8),
                    Text(
                      'MASTER DEVICE',
                      style: Theme.of(context).textTheme.labelLarge,
                    ),
                    const Spacer(),
                    if (app.isDocked)
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                        decoration: BoxDecoration(
                          color: TomsTheme.warning.withOpacity(0.15),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: const Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(LucideIcons.link, size: 12, color: TomsTheme.warning),
                            SizedBox(width: 4),
                            Text(
                              'DOCKED',
                              style: TextStyle(
                                fontSize: 10,
                                fontWeight: FontWeight.w700,
                                color: TomsTheme.warning,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                if (!usb.isConnected)
                  Center(
                    child: Column(
                      children: [
                        Icon(LucideIcons.usb, size: 40, color: TomsTheme.textSecondary.withOpacity(0.3)),
                        const SizedBox(height: 8),
                        const Text(
                          'Connect via USB OTG to view device status',
                          style: TextStyle(color: TomsTheme.textSecondary, fontSize: 13),
                        ),
                      ],
                    ),
                  )
                else if (status != null)
                  Row(
                    children: [
                      _StatusItem(
                        icon: LucideIcons.battery,
                        label: 'Battery',
                        value: '${status.batteryPct}%',
                        color: status.batteryPct > 20 ? TomsTheme.success : TomsTheme.danger,
                      ),
                      const SizedBox(width: 24),
                      _StatusItem(
                        icon: LucideIcons.hardDrive,
                        label: 'Pending',
                        value: '${status.pendingLogs}',
                        color: TomsTheme.warning,
                      ),
                      const SizedBox(width: 24),
                      _StatusItem(
                        icon: LucideIcons.wifi,
                        label: 'UART',
                        value: status.uartState == 3 ? 'Ready' : 'Idle',
                        color: TomsTheme.accent,
                      ),
                    ],
                  )
                else
                  const Center(
                    child: SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2, color: TomsTheme.accent),
                    ),
                  ),
                if (usb.lastError != null) ...[
                  const SizedBox(height: 12),
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: TomsTheme.danger.withOpacity(0.1),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      usb.lastError!,
                      style: const TextStyle(color: TomsTheme.danger, fontSize: 12),
                    ),
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }
}

class _StatusItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final Color color;

  const _StatusItem({
    required this.icon,
    required this.label,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(height: 6),
        Text(
          value,
          style: TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.w700,
            color: color,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          label,
          style: const TextStyle(fontSize: 11, color: TomsTheme.textSecondary),
        ),
      ],
    );
  }
}

/// Stats row: revenue, passengers, total logs.
class _StatsRow extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<AppState>(
      builder: (context, app, _) {
        return Row(
          children: [
            Expanded(
              child: _StatCard(
                icon: LucideIcons.coins,
                label: "TODAY'S REVENUE",
                value: app.todayRevenueFormatted,
                gradient: const [Color(0xFF00D4AA), Color(0xFF0088FF)],
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _StatCard(
                icon: LucideIcons.users,
                label: 'PASSENGERS',
                value: '${app.todayPassengers}',
                gradient: const [Color(0xFFFF9F43), Color(0xFFFF6348)],
              ),
            ),
          ],
        );
      },
    );
  }
}

class _StatCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final List<Color> gradient;

  const _StatCard({
    required this.icon,
    required this.label,
    required this.value,
    required this.gradient,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                gradient: LinearGradient(colors: gradient),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(icon, size: 18, color: Colors.white),
            ),
            const SizedBox(height: 12),
            Text(
              value,
              style: const TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w800,
                color: TomsTheme.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: const TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w600,
                color: TomsTheme.textSecondary,
                letterSpacing: 1,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Recent transaction list.
class _TransactionList extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<AppState>(
      builder: (context, app, _) {
        if (app.recentLogs.isEmpty) {
          return Card(
            child: Padding(
              padding: const EdgeInsets.all(40),
              child: Center(
                child: Column(
                  children: [
                    Icon(LucideIcons.inbox, size: 40, color: TomsTheme.textSecondary.withOpacity(0.3)),
                    const SizedBox(height: 12),
                    const Text(
                      'No transactions yet',
                      style: TextStyle(color: TomsTheme.textSecondary),
                    ),
                    const SizedBox(height: 4),
                    const Text(
                      'Boarding events will appear here',
                      style: TextStyle(color: TomsTheme.textSecondary, fontSize: 12),
                    ),
                  ],
                ),
              ),
            ),
          );
        }

        return Column(
          children: app.recentLogs.map((log) => _TransactionTile(log: log)).toList(),
        );
      },
    );
  }
}

class _TransactionTile extends StatelessWidget {
  final PassengerLog log;
  const _TransactionTile({required this.log});

  @override
  Widget build(BuildContext context) {
    final time = DateTime.fromMillisecondsSinceEpoch(log.timestamp * 1000);
    final timeStr = '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: TomsTheme.accent.withOpacity(0.1),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Center(
                child: Text(
                  'S${log.seatNumber}',
                  style: const TextStyle(
                    color: TomsTheme.accent,
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Route ${log.routeId} • Seat ${log.seatNumber}',
                    style: const TextStyle(
                      color: TomsTheme.textPrimary,
                      fontWeight: FontWeight.w600,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    timeStr,
                    style: const TextStyle(
                      color: TomsTheme.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
            Text(
              log.fareFormatted,
              style: const TextStyle(
                color: TomsTheme.success,
                fontWeight: FontWeight.w700,
                fontSize: 16,
              ),
            ),
            const SizedBox(width: 8),
            Icon(
              log.synced ? LucideIcons.uploadCloud : LucideIcons.cloudOff,
              size: 16,
              color: log.synced ? TomsTheme.success : TomsTheme.textSecondary,
            ),
          ],
        ),
      ),
    );
  }
}
