import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../models/models.dart';
import 'queue_manager_screen.dart';

class BoardingSheet extends StatefulWidget {
  const BoardingSheet({super.key});

  @override
  State<BoardingSheet> createState() => _BoardingSheetState();
}

class _BoardingSheetState extends State<BoardingSheet>
    with SingleTickerProviderStateMixin {
  late TabController _tabs;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      initialChildSize: 0.88,
      minChildSize: 0.5,
      maxChildSize: 0.96,
      builder: (_, scrollCtrl) => Container(
        decoration: const BoxDecoration(
          color: TomsTheme.bgCard,
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: Column(children: [
          // Handle
          Container(
            margin: const EdgeInsets.only(top: 12, bottom: 4),
            width: 40, height: 4,
            decoration: BoxDecoration(color: TomsTheme.border, borderRadius: BorderRadius.circular(2)),
          ),
          // Header
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            child: Row(children: [
              const Icon(LucideIcons.userPlus, size: 20, color: TomsTheme.accent),
              const SizedBox(width: 10),
              const Text('New Passenger', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: TomsTheme.textPrimary)),
              const Spacer(),
              IconButton(
                icon: const Icon(LucideIcons.maximize2, size: 18, color: TomsTheme.textSecondary),
                tooltip: 'Expand Queue',
                onPressed: () {
                  Navigator.pop(context);
                  Navigator.push(context, MaterialPageRoute(builder: (_) => const QueueManagerScreen()));
                },
              ),
              IconButton(
                icon: const Icon(LucideIcons.x, size: 18, color: TomsTheme.textSecondary),
                onPressed: () => Navigator.pop(context),
              ),
            ]),
          ),
          // Tabs
          TabBar(
            controller: _tabs,
            labelColor: TomsTheme.accent,
            unselectedLabelColor: TomsTheme.textSecondary,
            indicatorColor: TomsTheme.accent,
            indicatorSize: TabBarIndicatorSize.tab,
            tabs: const [Tab(text: 'Quick Boarding'), Tab(text: 'Queue')],
          ),
          const Divider(color: TomsTheme.border, height: 1),
          Expanded(child: TabBarView(controller: _tabs, children: [
            _QuickTab(scrollCtrl: scrollCtrl),
            _QueueTab(onSwitchToQuick: () => _tabs.animateTo(0)),
          ])),
        ]),
      ),
    );
  }
}

// ── Quick Boarding Tab ───────────────────────────────────────────────────────
class _QuickTab extends StatefulWidget {
  final ScrollController scrollCtrl;
  const _QuickTab({required this.scrollCtrl});

  @override
  State<_QuickTab> createState() => _QuickTabState();
}

class _QuickTabState extends State<_QuickTab> {
  TransitStop? _boarding;
  TransitStop? _destination;
  PassengerType _type = PassengerType.regular;
  bool _gpsAutoFilled = false;

  String _formatFare(int c) => '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  int? _baseFare(AppState app) {
    if (_boarding == null || _destination == null) return null;
    return app.calculateBaseFare(_boarding!, _destination!);
  }

  int? _finalFare(AppState app) {
    final base = _baseFare(app);
    if (base == null) return null;
    return app.calculateFare(base, _type);
  }

  int? _discount(AppState app) {
    final base = _baseFare(app);
    if (base == null) return null;
    return app.calculateDiscount(base, _type);
  }

  @override
  Widget build(BuildContext context) {
    final app = context.watch<AppState>();
    final stops = app.stops;
    final baseFare = _baseFare(app);
    final finalFare = _finalFare(app);
    final discount = _discount(app);
    final canQueue = _boarding != null && _destination != null && _boarding != _destination;

    // Auto-fill boarding stop from GPS the first time a nearest stop is known
    final nearest = app.nearestStop;
    if (!_gpsAutoFilled && nearest != null && _boarding == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() { _boarding = nearest; _gpsAutoFilled = true; });
      });
    }

    return ListView(controller: widget.scrollCtrl, padding: const EdgeInsets.all(20), children: [
      // Boarding stop
      _SectionLabel('Boarding Stop'),
      const SizedBox(height: 4),
      Row(children: [
        Expanded(child: _StopDropdown(
          hint: 'Select boarding stop',
          stops: stops,
          value: _boarding,
          exclude: _destination,
          onChanged: (s) => setState(() => _boarding = s),
        )),
        const SizedBox(width: 8),
        GestureDetector(
          onTap: () {
            final nearest = app.nearestStop;
            if (nearest != null) setState(() { _boarding = nearest; _gpsAutoFilled = true; });
          },
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            decoration: BoxDecoration(
              color: app.gpsTracking
                  ? TomsTheme.accent.withValues(alpha: 0.12)
                  : TomsTheme.bgCardLight,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(
                color: app.gpsTracking ? TomsTheme.accent.withValues(alpha: 0.5) : TomsTheme.border,
              ),
            ),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Icon(LucideIcons.mapPin, size: 14,
                  color: app.gpsTracking ? TomsTheme.accent : TomsTheme.textSecondary),
              const SizedBox(width: 4),
              Text(
                app.gpsTracking ? 'GPS' : 'No GPS',
                style: TextStyle(
                  fontSize: 11,
                  color: app.gpsTracking ? TomsTheme.accent : TomsTheme.textSecondary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ]),
          ),
        ),
      ]),
      const SizedBox(height: 4),
      Text(
        app.nearestStop != null
            ? 'GPS nearest: ${app.nearestStop!.name} (tap GPS to use)'
            : app.gpsError ?? 'Acquiring GPS…',
        style: const TextStyle(fontSize: 10, color: TomsTheme.textSecondary),
      ),
      const SizedBox(height: 16),

      // Destination
      _SectionLabel('Destination Stop'),
      const SizedBox(height: 4),
      _StopDropdown(
        hint: 'Select destination',
        stops: stops,
        value: _destination,
        exclude: _boarding,
        onChanged: (s) => setState(() => _destination = s),
      ),
      const SizedBox(height: 20),

      // Passenger type chips
      _SectionLabel('Passenger Type'),
      const SizedBox(height: 8),
      Row(children: PassengerType.values.map((t) {
        final selected = _type == t;
        return Expanded(child: GestureDetector(
          onTap: () => setState(() => _type = t),
          child: Container(
            margin: const EdgeInsets.only(right: 6),
            padding: const EdgeInsets.symmetric(vertical: 10),
            decoration: BoxDecoration(
              color: selected ? TomsTheme.accent.withValues(alpha: 0.18) : TomsTheme.bgCardLight,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: selected ? TomsTheme.accent : TomsTheme.border, width: selected ? 1.5 : 1),
            ),
            child: Column(children: [
              Text(t.label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: selected ? TomsTheme.accent : TomsTheme.textSecondary)),
              Text(t == PassengerType.regular ? 'Full Fare' : t.discountLabel, style: TextStyle(fontSize: 9, color: selected ? TomsTheme.accent.withValues(alpha: 0.7) : TomsTheme.textSecondary)),
            ]),
          ),
        ));
      }).toList()),
      const SizedBox(height: 24),

      // Live fare preview
      if (baseFare != null)
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            gradient: LinearGradient(colors: [TomsTheme.accent.withValues(alpha: 0.1), TomsTheme.bgCardLight]),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: TomsTheme.accent.withValues(alpha: 0.3)),
          ),
          child: Column(children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              const Text('Base Fare', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 13)),
              Text(_formatFare(baseFare), style: const TextStyle(color: TomsTheme.textPrimary, fontSize: 13)),
            ]),
            if (discount != null && discount > 0) ...[
              const SizedBox(height: 6),
              Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Text('${_type.label} Discount', style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 13)),
                Text('- ${_formatFare(discount)}', style: const TextStyle(color: TomsTheme.success, fontSize: 13)),
              ]),
              const Divider(color: TomsTheme.border, height: 20),
            ] else
              const SizedBox(height: 8),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              const Text('Final Fare', style: TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w700, fontSize: 16)),
              Text(_formatFare(finalFare ?? 0), style: const TextStyle(color: TomsTheme.accent, fontWeight: FontWeight.w800, fontSize: 22)),
            ]),
          ]),
        ),
      if (baseFare == null)
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(color: TomsTheme.bgCardLight, borderRadius: BorderRadius.circular(14), border: Border.all(color: TomsTheme.border)),
          child: const Center(child: Text('Select boarding + destination to see fare', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 13))),
        ),
      const SizedBox(height: 24),

      // Queue button
      SizedBox(
        width: double.infinity,
        child: ElevatedButton.icon(
          onPressed: canQueue ? () {
            app.queuePassenger(_boarding!, _destination!, _type);
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text('Passenger queued → ${_destination!.name}. Waiting for slave tap…'),
                backgroundColor: TomsTheme.accent,
                behavior: SnackBarBehavior.floating,
              ),
            );
            Navigator.pop(context);
          } : null,
          icon: const Icon(LucideIcons.plus),
          label: const Text('Queue Passenger', style: TextStyle(fontWeight: FontWeight.w700)),
          style: ElevatedButton.styleFrom(
            backgroundColor: TomsTheme.accent,
            foregroundColor: Colors.black,
            padding: const EdgeInsets.symmetric(vertical: 16),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
        ),
      ),
      const SizedBox(height: 8),
      const Text('After queuing, hand a Slave terminal to the passenger and ask them to press the button.',
          style: TextStyle(fontSize: 11, color: TomsTheme.textSecondary), textAlign: TextAlign.center),
    ]);
  }
}

// ── Queue Tab ────────────────────────────────────────────────────────────────
class _QueueTab extends StatelessWidget {
  final VoidCallback onSwitchToQuick;
  const _QueueTab({required this.onSwitchToQuick});

  String _formatFare(int c) => '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final app = context.watch<AppState>();
    final queue = app.pendingQueue;

    return Column(children: [
      Expanded(child: queue.isEmpty
          ? Center(child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
              Icon(LucideIcons.clipboardList, size: 40, color: TomsTheme.textSecondary.withValues(alpha: 0.3)),
              const SizedBox(height: 12),
              const Text('Queue is empty', style: TextStyle(color: TomsTheme.textSecondary)),
              const SizedBox(height: 8),
              TextButton.icon(
                icon: const Icon(LucideIcons.plus, size: 14),
                label: const Text('Add Passenger'),
                onPressed: onSwitchToQuick,
              ),
            ]))
          : ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: queue.length,
              itemBuilder: (_, i) {
                final p = queue[i];
                final isTarget = app.manualAssignTarget == p.id;
                return Container(
                  margin: const EdgeInsets.only(bottom: 8),
                  decoration: BoxDecoration(
                    color: isTarget ? TomsTheme.accent.withValues(alpha: 0.1) : TomsTheme.bgCard,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: isTarget ? TomsTheme.accent : TomsTheme.border, width: isTarget ? 1.5 : 1),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Row(children: [
                      Container(
                        width: 36, height: 36,
                        decoration: BoxDecoration(
                          color: TomsTheme.warning.withValues(alpha: 0.15),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Center(child: Text('${i + 1}', style: const TextStyle(color: TomsTheme.warning, fontWeight: FontWeight.w800))),
                      ),
                      const SizedBox(width: 12),
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('→ ${p.destination.name}', style: const TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w600, fontSize: 13)),
                        const SizedBox(height: 2),
                        Text('${p.type.label} · ${_formatFare(p.fareCentavos)}', style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 11)),
                      ])),
                      // Assign button
                      GestureDetector(
                        onTap: () => isTarget ? app.clearManualTarget() : app.setManualTarget(p.id),
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          decoration: BoxDecoration(
                            color: isTarget ? TomsTheme.accent.withValues(alpha: 0.2) : TomsTheme.bgCardLight,
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(color: isTarget ? TomsTheme.accent : TomsTheme.border),
                          ),
                          child: Row(mainAxisSize: MainAxisSize.min, children: [
                            Icon(isTarget ? LucideIcons.checkCircle : LucideIcons.arrowRight, size: 14,
                                color: isTarget ? TomsTheme.accent : TomsTheme.textSecondary),
                            const SizedBox(width: 4),
                            Text(isTarget ? 'Targeted' : 'Assign →',
                                style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600,
                                    color: isTarget ? TomsTheme.accent : TomsTheme.textSecondary)),
                          ]),
                        ),
                      ),
                    ]),
                  ),
                );
              },
            )),
      Padding(
        padding: const EdgeInsets.all(16),
        child: SizedBox(
          width: double.infinity,
          child: OutlinedButton.icon(
            icon: const Icon(LucideIcons.plus, size: 16),
            label: const Text('Add Another Passenger'),
            onPressed: onSwitchToQuick,
            style: OutlinedButton.styleFrom(
              foregroundColor: TomsTheme.accent,
              side: const BorderSide(color: TomsTheme.accent),
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ),
      ),
    ]);
  }
}

// ── Helpers ──────────────────────────────────────────────────────────────────
class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);
  @override
  Widget build(BuildContext context) =>
      Text(text, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: TomsTheme.textSecondary, letterSpacing: 0.5));
}

class _StopDropdown extends StatelessWidget {
  final String hint;
  final List<TransitStop> stops;
  final TransitStop? value;
  final TransitStop? exclude;
  final ValueChanged<TransitStop?> onChanged;
  const _StopDropdown({required this.hint, required this.stops, required this.value, this.exclude, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final available = stops.where((s) => s != exclude).toList();
    return InputDecorator(
      decoration: InputDecoration(
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        enabledBorder: OutlineInputBorder(borderSide: const BorderSide(color: TomsTheme.border), borderRadius: BorderRadius.circular(10)),
        focusedBorder: OutlineInputBorder(borderSide: const BorderSide(color: TomsTheme.accent), borderRadius: BorderRadius.circular(10)),
        filled: true, fillColor: TomsTheme.bgCardLight,
      ),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<TransitStop>(
          value: available.contains(value) ? value : null,
          hint: Text(hint, style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 13)),
          dropdownColor: TomsTheme.bgCard,
          style: const TextStyle(color: TomsTheme.textPrimary, fontSize: 13),
          isExpanded: true,
          icon: const Icon(LucideIcons.chevronDown, size: 16),
          items: available.map((s) => DropdownMenuItem(value: s, child: Text(s.name))).toList(),
          onChanged: onChanged,
        ),
      ),
    );
  }
}

extension _PassengerTypeDiscount on PassengerType {
  String get discountLabel => switch (this) {
    PassengerType.student => '-20%', PassengerType.pwd => '-20%',
    PassengerType.senior => '-20%', _ => '',
  };
}

