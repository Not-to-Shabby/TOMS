import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import 'package:share_plus/share_plus.dart';
import 'dart:convert';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../services/auth_service.dart';
import '../models/models.dart';

class ShiftSummaryScreen extends StatelessWidget {
  const ShiftSummaryScreen({super.key});

  String _formatFare(int c) => '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  void _confirmEndShift(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: TomsTheme.bgCard,
        title: const Text('End Shift?', style: TextStyle(color: TomsTheme.textPrimary)),
        content: const Text(
          'This will clear all active sessions and return you to vehicle selection. Make sure all passengers have been released.',
          style: TextStyle(color: TomsTheme.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel', style: TextStyle(color: TomsTheme.textSecondary)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: TomsTheme.danger),
            onPressed: () {
              Navigator.pop(ctx); // Close dialog
              Navigator.pop(context); // Close ShiftSummaryScreen
              context.read<AppState>().endShift();
              context.read<AuthService>().assignVehicle('');
            },
            child: const Text('End Shift', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final app = context.watch<AppState>();
    final logs = app.recentLogs;

    // Compute breakdown by type
    final Map<int, int> countByType = {};
    final Map<int, int> fareByType = {};
    for (final log in logs) {
      countByType[log.passengerType] = (countByType[log.passengerType] ?? 0) + 1;
      fareByType[log.passengerType] = (fareByType[log.passengerType] ?? 0) + log.fareCentavos;
    }
    final totalDiscount = logs.fold<int>(0, (sum, l) => sum + l.discountCentavos);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Shift Summary'),
        leading: IconButton(
          icon: const Icon(LucideIcons.arrowLeft),
          onPressed: () => Navigator.pop(context),
        ),
        actions: [
          IconButton(
            icon: const Icon(LucideIcons.share2),
            tooltip: 'Export JSON',
            onPressed: () {
              final payload = jsonEncode({
                'date': DateTime.now().toIso8601String().substring(0, 10),
                'total_passengers': app.todayPassengers,
                'total_revenue_centavos': app.todayRevenue,
                'total_discount_centavos': totalDiscount,
                'transactions': logs.map((l) => l.toMap()).toList(),
              });
              Share.share(payload, subject: 'TOMS Shift Summary');
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          // Totals
          Text('SHIFT OVERVIEW', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(child: _SummaryCard(icon: LucideIcons.coins, label: "Total Revenue", value: app.todayRevenueFormatted, color: TomsTheme.accent)),
            const SizedBox(width: 12),
            Expanded(child: _SummaryCard(icon: LucideIcons.users, label: "Total Passengers", value: '${app.todayPassengers}', color: TomsTheme.success)),
          ]),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(child: _SummaryCard(icon: LucideIcons.tag, label: "Total Discounts", value: _formatFare(totalDiscount), color: TomsTheme.warning)),
            const SizedBox(width: 12),
            Expanded(child: _SummaryCard(icon: LucideIcons.barChart, label: "Peak Occupancy", value: '${app.slavesDeployed}/${app.maxCapacity}', color: const Color(0xFF74B9FF))),
          ]),
          const SizedBox(height: 24),

          // Breakdown by type
          Text('PASSENGER TYPE BREAKDOWN', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 12),
          Card(child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(children: PassengerType.values.map((t) {
              final count = countByType[t.index] ?? 0;
              final fare = fareByType[t.index] ?? 0;
              return Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Row(children: [
                  _TypeDot(t),
                  const SizedBox(width: 12),
                  Text(t.label, style: const TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  Text('$count pax', style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 13)),
                  const SizedBox(width: 16),
                  Text(_formatFare(fare), style: const TextStyle(color: TomsTheme.accent, fontWeight: FontWeight.w700)),
                ]),
              );
            }).toList()),
          )),
          const SizedBox(height: 24),

          // Recent logs
          Text('TRIP LOG (last ${logs.length})', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 12),
          ...logs.map((log) {
            final t = DateTime.fromMillisecondsSinceEpoch(log.timestamp * 1000);
            final timeStr = '${t.hour.toString().padLeft(2,'0')}:${t.minute.toString().padLeft(2,'0')}';
            return Card(
              margin: const EdgeInsets.only(bottom: 8),
              child: ListTile(
                dense: true,
                leading: Container(
                  width: 36, height: 36,
                  decoration: BoxDecoration(color: TomsTheme.accent.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(8)),
                  child: Center(child: Text('S${log.seatNumber}', style: const TextStyle(color: TomsTheme.accent, fontSize: 11, fontWeight: FontWeight.w700))),
                ),
                title: Text(log.destinationStop.isNotEmpty ? log.destinationStop : 'Route ${log.routeId}',
                    style: const TextStyle(color: TomsTheme.textPrimary, fontSize: 13)),
                subtitle: Text(timeStr, style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 11)),
                trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                  Text(_formatFare(log.fareCentavos), style: const TextStyle(color: TomsTheme.success, fontWeight: FontWeight.w700)),
                  const SizedBox(width: 6),
                  Icon(log.synced ? LucideIcons.uploadCloud : LucideIcons.cloudOff, size: 14,
                      color: log.synced ? TomsTheme.success : TomsTheme.textSecondary),
                ]),
              ),
            );
          }),
          const SizedBox(height: 32),
          
          SizedBox(
            width: double.infinity,
            height: 56,
            child: ElevatedButton.icon(
              icon: const Icon(LucideIcons.logOut, size: 18),
              label: const Text('END SHIFT', style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 1.2)),
              style: ElevatedButton.styleFrom(
                backgroundColor: TomsTheme.danger,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: () => _confirmEndShift(context),
            ),
          ),
          const SizedBox(height: 40),
        ]),
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final Color color;
  const _SummaryCard({required this.icon, required this.label, required this.value, required this.color});

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, size: 18, color: color),
        const SizedBox(height: 10),
        Text(value, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: color)),
        const SizedBox(height: 2),
        Text(label, style: const TextStyle(fontSize: 11, color: TomsTheme.textSecondary)),
      ]),
    ),
  );
}

class _TypeDot extends StatelessWidget {
  final PassengerType type;
  const _TypeDot(this.type);

  Color get _color => switch (type) {
    PassengerType.regular => TomsTheme.textSecondary,
    PassengerType.student => const Color(0xFF74B9FF),
    PassengerType.pwd => TomsTheme.warning,
    PassengerType.senior => TomsTheme.success,
  };

  @override
  Widget build(BuildContext context) => Container(width: 10, height: 10,
      decoration: BoxDecoration(shape: BoxShape.circle, color: _color));
}

