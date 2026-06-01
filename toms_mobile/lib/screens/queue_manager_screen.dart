import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../models/models.dart';
import 'boarding_sheet.dart';

/// Full-screen queue manager — opened from BoardingSheet expand button.
class QueueManagerScreen extends StatelessWidget {
  const QueueManagerScreen({super.key});

  String _formatFare(int c) => '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Queue Manager'),
        leading: IconButton(
          icon: const Icon(LucideIcons.arrowLeft),
          onPressed: () => Navigator.pop(context),
        ),
        actions: [
          TextButton.icon(
            icon: const Icon(LucideIcons.plus, size: 16),
            label: const Text('Add Passenger'),
            style: TextButton.styleFrom(foregroundColor: TomsTheme.accent),
            onPressed: () => showModalBottomSheet(
              context: context,
              isScrollControlled: true,
              backgroundColor: Colors.transparent,
              builder: (_) => const BoardingSheet(),
            ),
          ),
        ],
      ),
      body: Consumer<AppState>(builder: (_, app, __) {
        final queue = app.pendingQueue;
        if (queue.isEmpty) {
          return Center(child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(LucideIcons.clipboardList, size: 56, color: TomsTheme.textSecondary.withValues(alpha: 0.3)),
            const SizedBox(height: 16),
            const Text('Queue is empty', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 16)),
            const SizedBox(height: 8),
            const Text('Add passengers using the button above', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 13)),
          ]));
        }
        return ListView.builder(
          padding: const EdgeInsets.all(16),
          itemCount: queue.length,
          itemBuilder: (_, i) {
            final p = queue[i];
            final isTarget = app.manualAssignTarget == p.id;
            return Container(
              margin: const EdgeInsets.only(bottom: 10),
              decoration: BoxDecoration(
                color: isTarget ? TomsTheme.accent.withValues(alpha: 0.08) : TomsTheme.bgCard,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: isTarget ? TomsTheme.accent : TomsTheme.border, width: isTarget ? 1.5 : 1),
              ),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(children: [
                  Container(
                    width: 44, height: 44,
                    decoration: BoxDecoration(
                      color: TomsTheme.warning.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Center(child: Text('${i + 1}',
                        style: const TextStyle(color: TomsTheme.warning, fontWeight: FontWeight.w800, fontSize: 18))),
                  ),
                  const SizedBox(width: 14),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('→ ${p.destination.name}',
                        style: const TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w700, fontSize: 15)),
                    const SizedBox(height: 4),
                    Text('From: ${p.boarding.name}', style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 12)),
                    const SizedBox(height: 2),
                    Row(children: [
                      _TypeBadge(p.type),
                      const SizedBox(width: 8),
                      Text(_formatFare(p.fareCentavos),
                          style: const TextStyle(color: TomsTheme.accent, fontWeight: FontWeight.w700, fontSize: 14)),
                    ]),
                  ])),
                  const SizedBox(width: 12),
                  GestureDetector(
                    onTap: () => isTarget ? app.clearManualTarget() : app.setManualTarget(p.id),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                      decoration: BoxDecoration(
                        color: isTarget ? TomsTheme.accent.withValues(alpha: 0.2) : TomsTheme.bgCardLight,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: isTarget ? TomsTheme.accent : TomsTheme.border),
                      ),
                      child: Column(children: [
                        Icon(isTarget ? LucideIcons.checkCircle : LucideIcons.arrowRight,
                            size: 20, color: isTarget ? TomsTheme.accent : TomsTheme.textSecondary),
                        const SizedBox(height: 4),
                        Text(isTarget ? 'Targeted' : 'Assign',
                            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700,
                                color: isTarget ? TomsTheme.accent : TomsTheme.textSecondary)),
                      ]),
                    ),
                  ),
                ]),
              ),
            );
          },
        );
      }),
    );
  }
}

class _TypeBadge extends StatelessWidget {
  final PassengerType type;
  const _TypeBadge(this.type);

  Color get _color => switch (type) {
    PassengerType.regular => TomsTheme.textSecondary,
    PassengerType.student => const Color(0xFF74B9FF),
    PassengerType.pwd => TomsTheme.warning,
    PassengerType.senior => TomsTheme.success,
  };

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
    decoration: BoxDecoration(
      color: _color.withValues(alpha: 0.15),
      borderRadius: BorderRadius.circular(6),
    ),
    child: Text(type.name[0].toUpperCase() + type.name.substring(1),
        style: TextStyle(color: _color, fontSize: 10, fontWeight: FontWeight.w700)),
  );
}
