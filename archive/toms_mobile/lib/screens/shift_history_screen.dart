import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import 'package:share_plus/share_plus.dart';
import 'dart:convert';
import '../theme/toms_theme.dart';
import '../services/database_service.dart';
import '../models/models.dart';

class ShiftHistoryScreen extends StatefulWidget {
  const ShiftHistoryScreen({super.key});

  @override
  State<ShiftHistoryScreen> createState() => _ShiftHistoryScreenState();
}

class _ShiftHistoryScreenState extends State<ShiftHistoryScreen> {
  List<_ShiftDay> _days = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final db = context.read<DatabaseService>();
    final all = await db.getAllLogs(limit: 200);

    // Group by calendar date
    final Map<String, List<PassengerLog>> grouped = {};
    for (final log in all) {
      final d = DateTime.fromMillisecondsSinceEpoch(log.timestamp * 1000);
      final key = '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
      grouped.putIfAbsent(key, () => []).add(log);
    }

    setState(() {
      _days = grouped.entries.map((e) => _ShiftDay(date: e.key, logs: e.value)).toList()
        ..sort((a, b) => b.date.compareTo(a.date));
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Shift History'),
        leading: IconButton(
          icon: const Icon(LucideIcons.arrowLeft),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator(color: TomsTheme.accent))
          : _days.isEmpty
              ? Center(
                  child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                    Icon(LucideIcons.history, size: 48, color: TomsTheme.textSecondary.withValues(alpha: 0.3)),
                    const SizedBox(height: 12),
                    const Text('No past shifts found', style: TextStyle(color: TomsTheme.textSecondary)),
                  ]),
                )
              : ListView.builder(
                  padding: const EdgeInsets.all(16),
                  itemCount: _days.length,
                  itemBuilder: (_, i) => _ShiftDayCard(day: _days[i]),
                ),
    );
  }
}

class _ShiftDay {
  final String date;
  final List<PassengerLog> logs;
  _ShiftDay({required this.date, required this.logs});

  int get totalRevenue => logs.fold(0, (s, l) => s + l.fareCentavos);
  int get passengerCount => logs.length;
  int get syncedCount => logs.where((l) => l.synced).length;
}

class _ShiftDayCard extends StatelessWidget {
  final _ShiftDay day;
  const _ShiftDayCard({required this.day});

  String _formatFare(int c) => '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  Future<void> _export(BuildContext context) async {
    final payload = jsonEncode({
      'shift_date': day.date,
      'total_passengers': day.passengerCount,
      'total_revenue_centavos': day.totalRevenue,
      'transactions': day.logs.map((l) => l.toMap()).toList(),
    });
    await Share.share(payload, subject: 'TOMS Shift Export — ${day.date}');
  }

  @override
  Widget build(BuildContext context) {
    final synced = day.syncedCount == day.passengerCount;

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Text(day.date, style: const TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w700, fontSize: 15)),
            const Spacer(),
            Icon(
              synced ? LucideIcons.uploadCloud : LucideIcons.cloudOff,
              size: 16,
              color: synced ? TomsTheme.success : TomsTheme.warning,
            ),
            const SizedBox(width: 4),
            Text(
              synced ? 'Synced' : '${day.passengerCount - day.syncedCount} pending',
              style: TextStyle(fontSize: 11, color: synced ? TomsTheme.success : TomsTheme.warning),
            ),
          ]),
          const SizedBox(height: 12),
          Row(children: [
            _Stat(label: 'Passengers', value: '${day.passengerCount}', icon: LucideIcons.users, color: TomsTheme.accent),
            const SizedBox(width: 24),
            _Stat(label: 'Revenue', value: _formatFare(day.totalRevenue), icon: LucideIcons.coins, color: TomsTheme.success),
          ]),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: () => _export(context),
              icon: const Icon(LucideIcons.share2, size: 14),
              label: const Text('Export JSON', style: TextStyle(fontSize: 12)),
              style: OutlinedButton.styleFrom(
                foregroundColor: TomsTheme.textSecondary,
                side: const BorderSide(color: TomsTheme.border),
                padding: const EdgeInsets.symmetric(vertical: 8),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
            ),
          ),
        ]),
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final Color color;
  const _Stat({required this.label, required this.value, required this.icon, required this.color});

  @override
  Widget build(BuildContext context) => Row(children: [
    Icon(icon, size: 14, color: color),
    const SizedBox(width: 6),
    Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(value, style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 14)),
      Text(label, style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 10)),
    ]),
  ]);
}
