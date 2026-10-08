import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../models/models.dart';

/// Overlay banner that appears at the top of the dashboard whenever one or
/// more passenger slots are in the ALARMING state (i.e. approaching their
/// destination stop but not yet paid). Collapses automatically when all
/// alarming slots are resolved.
class ProximityAlarmBanner extends StatefulWidget {
  const ProximityAlarmBanner({super.key});

  @override
  State<ProximityAlarmBanner> createState() => _ProximityAlarmBannerState();
}

class _ProximityAlarmBannerState extends State<ProximityAlarmBanner>
    with SingleTickerProviderStateMixin {
  late AnimationController _blink;
  late Animation<double> _opacity;

  @override
  void initState() {
    super.initState();
    _blink = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    )..repeat(reverse: true);
    _opacity = Tween<double>(begin: 0.6, end: 1.0).animate(_blink);
  }

  @override
  void dispose() {
    _blink.dispose();
    super.dispose();
  }



  @override
  Widget build(BuildContext context) {
    return Consumer<AppState>(builder: (context, app, _) {
      final alarming = app.activeSlots
          .where((s) => s.state == PassengerSlotState.alarming)
          .toList();

      if (alarming.isEmpty) return const SizedBox.shrink();

      return AnimatedBuilder(
        animation: _opacity,
        builder: (_, __) => Opacity(
          opacity: _opacity.value,
          child: Container(
            width: double.infinity,
            margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [
                  TomsTheme.danger.withValues(alpha: 0.15),
                  TomsTheme.danger.withValues(alpha: 0.05),
                ],
              ),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: TomsTheme.danger.withValues(alpha: 0.6), width: 1.5),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Header row
                Padding(
                  padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
                  child: Row(children: [
                    Container(
                      padding: const EdgeInsets.all(6),
                      decoration: BoxDecoration(
                        color: TomsTheme.danger.withValues(alpha: 0.2),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Icon(LucideIcons.bellRing, size: 16, color: TomsTheme.danger),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(
                          '${alarming.length} Passenger${alarming.length > 1 ? 's' : ''} Near Stop',
                          style: const TextStyle(
                            color: TomsTheme.danger,
                            fontWeight: FontWeight.w700,
                            fontSize: 14,
                          ),
                        ),
                        const Text(
                          'Approaching destination — collect payment now',
                          style: TextStyle(color: TomsTheme.textSecondary, fontSize: 11),
                        ),
                      ]),
                    ),
                  ]),
                ),
                // Per-slot action rows
                ...alarming.map((slot) => _AlarmSlotRow(slot: slot)),
                const SizedBox(height: 6),
              ],
            ),
          ),
        ),
      );
    });
  }
}

class _AlarmSlotRow extends StatelessWidget {
  final PassengerSlot slot;
  const _AlarmSlotRow({required this.slot});

  String _formatFare(int c) =>
      '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final app = context.read<AppState>();

    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 0, 14, 8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: TomsTheme.danger.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: TomsTheme.danger.withValues(alpha: 0.25)),
        ),
        child: Row(children: [
          // Slot badge
          Container(
            width: 32, height: 32,
            decoration: BoxDecoration(
              color: TomsTheme.danger.withValues(alpha: 0.2),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Center(
              child: Text(
                '#${slot.slotNumber}',
                style: const TextStyle(
                  color: TomsTheme.danger,
                  fontWeight: FontWeight.w800,
                  fontSize: 11,
                ),
              ),
            ),
          ),
          const SizedBox(width: 10),
          // Destination + fare
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(
              slot.destination,
              style: const TextStyle(color: TomsTheme.textPrimary, fontSize: 12, fontWeight: FontWeight.w600),
              overflow: TextOverflow.ellipsis,
            ),
            Text(
              '${slot.passengerType.label} · ${_formatFare(slot.fareCentavos)}',
              style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 11),
            ),
          ])),
          const SizedBox(width: 8),
          // Mark Paid button
          GestureDetector(
            onTap: () {
              app.markPaidManual(slot.slaveUid);
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text('Slot #${slot.slotNumber} marked as paid'),
                  backgroundColor: TomsTheme.success,
                  behavior: SnackBarBehavior.floating,
                  duration: const Duration(seconds: 2),
                ),
              );
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
              decoration: BoxDecoration(
                color: TomsTheme.success.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: TomsTheme.success.withValues(alpha: 0.5)),
              ),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                const Icon(LucideIcons.checkCircle, size: 13, color: TomsTheme.success),
                const SizedBox(width: 5),
                const Text('Mark Paid', style: TextStyle(color: TomsTheme.success, fontSize: 11, fontWeight: FontWeight.w700)),
              ]),
            ),
          ),
          const SizedBox(width: 6),
          // Dismiss (release slot without payment — conductor decision)
          GestureDetector(
            onTap: () => _confirmRelease(context, app, slot),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
              decoration: BoxDecoration(
                color: TomsTheme.bgCardLight,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: TomsTheme.border),
              ),
              child: const Icon(LucideIcons.x, size: 14, color: TomsTheme.textSecondary),
            ),
          ),
        ]),
      ),
    );
  }

  void _confirmRelease(BuildContext context, AppState app, PassengerSlot slot) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: TomsTheme.bgCard,
        title: const Text('Release Slot?', style: TextStyle(color: TomsTheme.textPrimary)),
        content: Text(
          'This will release Slot #${slot.slotNumber} without collecting payment. '
          'Only do this if the passenger already paid or alighted without paying.',
          style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 13),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel', style: TextStyle(color: TomsTheme.textSecondary)),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              app.releaseSlotManual(slot.slaveUid);
            },
            child: const Text('Release', style: TextStyle(color: TomsTheme.danger)),
          ),
        ],
      ),
    );
  }
}
