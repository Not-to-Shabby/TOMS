import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../services/page_controller_service.dart';
import '../models/models.dart';
import 'queue_manager_screen.dart';
import 'shift_summary_screen.dart';
import 'debug_screen.dart';
import '../services/usb_service.dart';

// ── Root boarding screen ─────────────────────────────────────────────────────
class BoardingScreen extends StatefulWidget {
  const BoardingScreen({super.key});

  @override
  State<BoardingScreen> createState() => _BoardingScreenState();
}

class _BoardingScreenState extends State<BoardingScreen> {
  TransitStop? _boarding;
  TransitStop? _destination;
  PassengerType _type = PassengerType.regular;
  bool _gpsAutoFilled = false;

  bool _boardingExpanded = true;
  bool _destinationExpanded = false;

  // ── Fare helpers ────────────────────────────────────────────────────────────
  String _fmt(int c) => '₱${c ~/ 100}.${(c % 100).toString().padLeft(2, '0')}';

  int? _baseFare(AppState app) {
    if (_boarding == null || _destination == null) return null;
    return app.calculateBaseFare(_boarding!, _destination!);
  }

  int? _finalFare(AppState app) {
    final b = _baseFare(app);
    return b == null ? null : app.calculateFare(b, _type);
  }

  int? _discount(AppState app) {
    final b = _baseFare(app);
    return b == null ? null : app.calculateDiscount(b, _type);
  }

  // ── Queue action ────────────────────────────────────────────────────────────
  void _queuePassenger(AppState app) {
    app.queuePassenger(_boarding!, _destination!, _type);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('Queued → ${_destination!.name}. Waiting for slave tap…'),
      backgroundColor: TomsTheme.accent,
      behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.only(bottom: 104, left: 16, right: 16),
      duration: const Duration(seconds: 2),
    ));
    // Reset the form for the next passenger
    setState(() {
      _boarding = null;
      _destination = null;
      _gpsAutoFilled = false; // Triggers auto-fill on next frame if GPS is available
      _boardingExpanded = true;
      _destinationExpanded = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final app = context.read<AppState>();
    final pcs = context.read<PageControllerService>();
    final usb = context.watch<UsbService>(); // rarely updates
    
    // Listen only to GPS values to prevent full-screen rebuilds on every AppState notify
    final nearest = context.select<AppState, TransitStop?>((a) => a.nearestStop);
    final gpsTracking = context.select<AppState, bool>((a) => a.gpsTracking);
    final gpsError = context.select<AppState, String?>((a) => a.gpsError);

    final stops = app.stops;
    final baseFare = _baseFare(app);
    final finalFare = _finalFare(app);
    final discount = _discount(app);
    final canQueue = _boarding != null &&
        _destination != null &&
        _boarding != _destination;

    return Scaffold(
      backgroundColor: TomsTheme.bgDark,
      appBar: _buildAppBar(context, usb, pcs),
      body: SafeArea(
        child: Column(children: [
          // Scrollable form area
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(18, 8, 18, 0),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [

                // ── Boarding stop ──────────────────────────────────────────
                _StopMenu(
                  title: 'BOARDING STOP',
                  hint: 'Select boarding stop',
                  isExpanded: _boardingExpanded,
                  selectedValue: _boarding,
                  stops: stops,
                  exclude: _destination,
                  isGpsFilled: _gpsAutoFilled && _boarding == nearest,
                  onToggle: () => setState(() {
                    _boardingExpanded = !_boardingExpanded;
                    if (_boardingExpanded) _destinationExpanded = false;
                  }),
                  onSelect: (s) => setState(() {
                    _boarding = s;
                    _boardingExpanded = false;
                    if (_destination == null) _destinationExpanded = true;
                  }),
                  trailing: _GpsPill(
                    isTracking: gpsTracking,
                    onTap: () {
                      if (nearest != null) {
                        setState(() { 
                          _boarding = nearest; 
                          _gpsAutoFilled = true; 
                          _boardingExpanded = false;
                          if (_destination == null) _destinationExpanded = true;
                        });
                      }
                    },
                  ),
                ),
                if (_boardingExpanded) ...[
                  const SizedBox(height: 4),
                  Text(
                    nearest != null
                        ? 'GPS nearest: ${nearest.name} (tap to use)'
                        : gpsError ?? 'Acquiring GPS…',
                    style: const TextStyle(fontSize: 10, color: TomsTheme.textSecondary),
                  ),
                ],
                const SizedBox(height: 12),

                // ── Destination ───────────────────────────────────────────
                _StopMenu(
                  title: 'DESTINATION STOP',
                  hint: 'Select destination',
                  isExpanded: _destinationExpanded,
                  selectedValue: _destination,
                  stops: stops,
                  exclude: _boarding,
                  onToggle: () => setState(() {
                    _destinationExpanded = !_destinationExpanded;
                    if (_destinationExpanded) _boardingExpanded = false;
                  }),
                  onSelect: (s) => setState(() {
                    _destination = s;
                    _destinationExpanded = false;
                  }),
                ),
                const SizedBox(height: 20),

                // ── Passenger type chips ───────────────────────────────────
                _label('PASSENGER TYPE'),
                const SizedBox(height: 8),
                _TypeChipRow(
                  selected: _type,
                  onChanged: (t) => setState(() => _type = t),
                ),
                const SizedBox(height: 20),

                // ── Fare preview ──────────────────────────────────────────
                _FarePreview(
                  baseFare: baseFare,
                  finalFare: finalFare,
                  discount: discount,
                  type: _type,
                  fmt: _fmt,
                ),
                const SizedBox(height: 16),

                // ── Pending queue inline ──────────────────────────────────
                const _PendingQueueSection(),

                const SizedBox(height: 100), // space for pinned button
              ]),
            ),
          ),

          // ── Pinned [Queue Passenger] button ───────────────────────────────
          _PinnedQueueButton(
            canQueue: canQueue,
            onPressed: () => _queuePassenger(app),
          ),
        ]),
      ),
    );
  }

  // ── App bar ─────────────────────────────────────────────────────────────────
  PreferredSizeWidget _buildAppBar(
      BuildContext ctx, UsbService usb, PageControllerService pcs) {
    return AppBar(
      titleSpacing: 12,
      title: Row(children: [
        // Logo
        Container(
          padding: const EdgeInsets.all(6),
          decoration: BoxDecoration(
            gradient: const LinearGradient(colors: [TomsTheme.accent, Color(0xFF0088FF)]),
            borderRadius: BorderRadius.circular(8),
          ),
          child: const Icon(LucideIcons.bus, size: 16, color: Colors.white),
        ),
        const SizedBox(width: 8),
        const Text('New Passenger', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
        const Spacer(),
        // USB indicator
        _UsbPill(connected: usb.isConnected),
        const SizedBox(width: 8),
        // Dashboard shortcut
        GestureDetector(
          onTap: pcs.goToDashboard,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: TomsTheme.bgCardLight,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: TomsTheme.border),
            ),
            child: const Row(mainAxisSize: MainAxisSize.min, children: [
              Text('Dashboard', style: TextStyle(fontSize: 11, color: TomsTheme.textSecondary, fontWeight: FontWeight.w600)),
              SizedBox(width: 4),
              Icon(LucideIcons.chevronRight, size: 12, color: TomsTheme.textSecondary),
            ]),
          ),
        ),
        // Overflow menu
        PopupMenuButton<String>(
          icon: const Icon(LucideIcons.moreVertical, size: 20),
          color: TomsTheme.bgCard,
          onSelected: (v) {
            if (v == 'queue') {
              Navigator.push(ctx, MaterialPageRoute(builder: (_) => const QueueManagerScreen()));
            } else if (v == 'shift') {
              Navigator.push(ctx, MaterialPageRoute(builder: (_) => const ShiftSummaryScreen()));
            } else if (v == 'debug') {
              Navigator.push(ctx, MaterialPageRoute(builder: (_) => const DebugScreen()));
            } else if (v == 'usb') {
              usb.isConnected ? usb.disconnect() : ctx.read<AppState>().connectUsb();
            }
          },
          itemBuilder: (_) => [
            PopupMenuItem(value: 'queue', child: Row(children: const [
              Icon(LucideIcons.list, size: 16, color: TomsTheme.textSecondary),
              SizedBox(width: 10),
              Text('Queue Manager', style: TextStyle(color: TomsTheme.textPrimary)),
            ])),
            PopupMenuItem(value: 'shift', child: Row(children: const [
              Icon(LucideIcons.clipboardList, size: 16, color: TomsTheme.textSecondary),
              SizedBox(width: 10),
              Text('Shift Summary', style: TextStyle(color: TomsTheme.textPrimary)),
            ])),
            PopupMenuItem(value: 'debug', child: Row(children: const [
              Icon(LucideIcons.terminal, size: 16, color: TomsTheme.textSecondary),
              SizedBox(width: 10),
              Text('Debug Console', style: TextStyle(color: TomsTheme.textPrimary)),
            ])),
            PopupMenuItem(value: 'usb', child: Consumer<UsbService>(builder: (_, u, __) => Row(children: [
              Icon(u.isConnected ? LucideIcons.unplug : LucideIcons.plug, size: 16, color: TomsTheme.textSecondary),
              const SizedBox(width: 10),
              Text(u.isConnected ? 'Disconnect USB' : 'Connect USB', style: const TextStyle(color: TomsTheme.textPrimary)),
            ]))),
          ],
        ),
      ]),
    );
  }

  Widget _label(String text) => Text(
    text,
    style: const TextStyle(
      fontSize: 11,
      fontWeight: FontWeight.w700,
      color: TomsTheme.textSecondary,
      letterSpacing: 1.0,
    ),
  );
}

// ── Pending queue inline section ─────────────────────────────────────────────
class _PendingQueueSection extends StatelessWidget {
  const _PendingQueueSection();

  String _fmt(int c) => '₱${c ~/ 100}.${(c % 100).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final app = context.watch<AppState>();
    final queue = app.pendingQueue;
    if (queue.isEmpty) return const SizedBox.shrink();

    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Divider(color: TomsTheme.border),
      const SizedBox(height: 8),
      Row(children: [
        const Text('PENDING QUEUE', style: TextStyle(
          fontSize: 11, fontWeight: FontWeight.w700,
          color: TomsTheme.textSecondary, letterSpacing: 1.0,
        )),
        const SizedBox(width: 8),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
          decoration: BoxDecoration(
            color: TomsTheme.warning.withValues(alpha: 0.15),
            borderRadius: BorderRadius.circular(20),
          ),
          child: Text('${queue.length}', style: const TextStyle(
            fontSize: 11, color: TomsTheme.warning, fontWeight: FontWeight.w800,
          )),
        ),
        const Spacer(),
        GestureDetector(
          onTap: () => Navigator.push(context,
              MaterialPageRoute(builder: (_) => const QueueManagerScreen())),
          child: const Text('Manage →', style: TextStyle(fontSize: 11, color: TomsTheme.accent)),
        ),
      ]),
      const SizedBox(height: 8),
      ...queue.take(3).toList().asMap().entries.map((entry) {
        final i = entry.key;
        final p = entry.value;
        final isTarget = app.manualAssignTarget == p.id;
        return Container(
          margin: const EdgeInsets.only(bottom: 6),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: isTarget ? TomsTheme.accent.withValues(alpha: 0.08) : TomsTheme.bgCard,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: isTarget ? TomsTheme.accent.withValues(alpha: 0.5) : TomsTheme.border,
              width: isTarget ? 1.5 : 1,
            ),
          ),
          child: Row(children: [
            Container(
              width: 28, height: 28,
              decoration: BoxDecoration(
                color: TomsTheme.warning.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Center(child: Text('${i + 1}',
                  style: const TextStyle(color: TomsTheme.warning, fontWeight: FontWeight.w800, fontSize: 12))),
            ),
            const SizedBox(width: 10),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('→ ${p.destination.name}',
                  style: const TextStyle(color: TomsTheme.textPrimary, fontSize: 12, fontWeight: FontWeight.w600)),
              Text('${p.type.label} · ${_fmt(p.fareCentavos)}',
                  style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 11)),
            ])),
            GestureDetector(
              onTap: () => isTarget ? app.clearManualTarget() : app.setManualTarget(p.id),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: isTarget ? TomsTheme.accent.withValues(alpha: 0.15) : TomsTheme.bgCardLight,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: isTarget ? TomsTheme.accent : TomsTheme.border),
                ),
                child: Text(isTarget ? 'Targeted' : 'Assign',
                    style: TextStyle(
                      fontSize: 11, fontWeight: FontWeight.w600,
                      color: isTarget ? TomsTheme.accent : TomsTheme.textSecondary,
                    )),
              ),
            ),
          ]),
        );
      }),
      if (queue.length > 3)
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Text('+${queue.length - 3} more in queue',
              style: const TextStyle(fontSize: 11, color: TomsTheme.textSecondary)),
        ),
      const SizedBox(height: 8),
    ]);
  }
}

// ── Pinned queue button ───────────────────────────────────────────────────────
class _PinnedQueueButton extends StatelessWidget {
  final bool canQueue;
  final VoidCallback onPressed;
  const _PinnedQueueButton({required this.canQueue, required this.onPressed});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: TomsTheme.bgDark,
        border: Border(top: BorderSide(color: TomsTheme.border)),
        // Removed heavy boxShadow to prevent raster jank
      ),
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      child: SizedBox(
        width: double.infinity,
        height: 56,
        child: ElevatedButton.icon(
          onPressed: canQueue ? onPressed : null,
          icon: const Icon(LucideIcons.plus, size: 22),
          label: const Text('QUEUE PASSENGER', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15, letterSpacing: 1.0)),
          style: ElevatedButton.styleFrom(
            backgroundColor: canQueue ? TomsTheme.accent : TomsTheme.bgCardLight,
            foregroundColor: canQueue ? TomsTheme.bgDark : TomsTheme.textSecondary,
            elevation: canQueue ? 4 : 0,
            shadowColor: TomsTheme.accent.withValues(alpha: 0.5),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          ),
        ),
      ),
    );
  }
}

// ── Type chip row ─────────────────────────────────────────────────────────────
class _TypeChipRow extends StatelessWidget {
  final PassengerType selected;
  final ValueChanged<PassengerType> onChanged;
  const _TypeChipRow({required this.selected, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Row(children: PassengerType.values.map((t) {
      final sel = selected == t;
      final discountText = t == PassengerType.regular ? 'Full Fare' : switch (t) {
        PassengerType.student => '-20%',
        PassengerType.pwd => '-20%',
        PassengerType.senior => '-20%',
        _ => '',
      };
      return Expanded(child: GestureDetector(
        onTap: () => onChanged(t),
        child: Container(
          margin: const EdgeInsets.only(right: 6),
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: sel ? TomsTheme.accent.withValues(alpha: 0.18) : TomsTheme.bgCardLight,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: sel ? TomsTheme.accent : TomsTheme.border, width: sel ? 1.5 : 1),
          ),
          child: Column(children: [
            Text(t.label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700,
                color: sel ? TomsTheme.accent : TomsTheme.textSecondary)),
            Text(discountText, style: TextStyle(fontSize: 9,
                color: sel ? TomsTheme.accent.withValues(alpha: 0.7) : TomsTheme.textSecondary)),
          ]),
        ),
      ));
    }).toList());
  }
}

// ── Fare preview card ─────────────────────────────────────────────────────────
class _FarePreview extends StatelessWidget {
  final int? baseFare, finalFare, discount;
  final PassengerType type;
  final String Function(int) fmt;
  const _FarePreview({this.baseFare, this.finalFare, this.discount, required this.type, required this.fmt});

  @override
  Widget build(BuildContext context) {
    if (baseFare == null) {
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 24),
        width: double.infinity,
        decoration: BoxDecoration(
          color: TomsTheme.bgCardLight.withValues(alpha: 0.5),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: TomsTheme.border),
        ),
        child: const Column(children: [
          Icon(LucideIcons.receipt, size: 28, color: TomsTheme.textSecondary),
          SizedBox(height: 12),
          Text('Select stops to calculate fare', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 13, fontWeight: FontWeight.w500)),
        ]),
      );
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            TomsTheme.accent.withValues(alpha: 0.15),
            TomsTheme.bgCardLight,
          ]
        ),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: TomsTheme.accent.withValues(alpha: 0.4), width: 1.5),
        // Used a smaller, cheaper shadow to avoid raster jank
        boxShadow: [
          BoxShadow(color: TomsTheme.accent.withValues(alpha: 0.1), blurRadius: 8, offset: const Offset(0, 4)),
        ],
      ),
      child: Column(children: [
        // Large fare amount
        Text(fmt(finalFare ?? 0), style: const TextStyle(
          color: TomsTheme.accent,
          fontSize: 48,
          fontWeight: FontWeight.w800,
          letterSpacing: -1.5,
          height: 1.1,
        )),
        const SizedBox(height: 4),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
            color: TomsTheme.accent.withValues(alpha: 0.2),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(type.label.toUpperCase(), style: const TextStyle(
            color: TomsTheme.accent, fontSize: 10, fontWeight: FontWeight.w800, letterSpacing: 1.0
          )),
        ),
        if (discount != null && discount! > 0) ...[
          const SizedBox(height: 16),
          const Divider(color: TomsTheme.border, height: 1),
          const SizedBox(height: 12),
          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            const Text('Base Fare', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 13, fontWeight: FontWeight.w500)),
            Text(fmt(baseFare!), style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 13, fontWeight: FontWeight.w600)),
          ]),
          const SizedBox(height: 6),
          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text('${type.label} Discount', style: const TextStyle(color: TomsTheme.success, fontSize: 13, fontWeight: FontWeight.w500)),
            Text('− ${fmt(discount!)}', style: const TextStyle(color: TomsTheme.success, fontSize: 13, fontWeight: FontWeight.w700)),
          ]),
        ],
      ]),
    );
  }
}

// ── GPS pill ──────────────────────────────────────────────────────────────────
class _GpsPill extends StatelessWidget {
  final bool isTracking;
  final VoidCallback onTap;
  const _GpsPill({required this.isTracking, required this.onTap});

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: onTap,
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
      decoration: BoxDecoration(
        color: isTracking ? TomsTheme.accent.withValues(alpha: 0.12) : TomsTheme.bgCardLight,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: isTracking ? TomsTheme.accent.withValues(alpha: 0.5) : TomsTheme.border),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(LucideIcons.mapPin, size: 14, color: isTracking ? TomsTheme.accent : TomsTheme.textSecondary),
        const SizedBox(width: 4),
        Text(isTracking ? 'GPS' : 'No GPS', style: TextStyle(
          fontSize: 11, fontWeight: FontWeight.w600,
          color: isTracking ? TomsTheme.accent : TomsTheme.textSecondary,
        )),
      ]),
    ),
  );
}

// ── USB pill ──────────────────────────────────────────────────────────────────
class _UsbPill extends StatelessWidget {
  final bool connected;
  const _UsbPill({required this.connected});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    decoration: BoxDecoration(
      color: connected
          ? TomsTheme.success.withValues(alpha: 0.12)
          : TomsTheme.border.withValues(alpha: 0.3),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: connected ? TomsTheme.success.withValues(alpha: 0.4) : TomsTheme.border),
    ),
    child: Row(mainAxisSize: MainAxisSize.min, children: [
      Container(
        width: 6, height: 6,
        decoration: BoxDecoration(
          color: connected ? TomsTheme.success : TomsTheme.textSecondary,
          shape: BoxShape.circle,
        ),
      ),
      const SizedBox(width: 5),
      Text(connected ? 'USB' : 'No USB', style: TextStyle(
        fontSize: 10, fontWeight: FontWeight.w600,
        color: connected ? TomsTheme.success : TomsTheme.textSecondary,
      )),
    ]),
  );
}

// ── Stop inline accordion menu ────────────────────────────────────────────────
class _StopMenu extends StatelessWidget {
  final String title;
  final String hint;
  final bool isExpanded;
  final TransitStop? selectedValue;
  final List<TransitStop> stops;
  final TransitStop? exclude;
  final ValueChanged<TransitStop> onSelect;
  final VoidCallback onToggle;
  final Widget? trailing;
  final bool isGpsFilled;

  const _StopMenu({
    required this.title,
    required this.hint,
    required this.isExpanded,
    required this.selectedValue,
    required this.stops,
    this.exclude,
    required this.onSelect,
    required this.onToggle,
    this.trailing,
    this.isGpsFilled = false,
  });

  @override
  Widget build(BuildContext context) {
    final available = stops.where((s) => s != exclude).toList();
    
    return AnimatedContainer(
      duration: const Duration(milliseconds: 250),
      decoration: BoxDecoration(
        color: isExpanded ? TomsTheme.bgCard : TomsTheme.bgCardLight.withValues(alpha: 0.7),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isExpanded ? TomsTheme.accent.withValues(alpha: 0.5) : (isGpsFilled ? TomsTheme.accent : TomsTheme.border),
          width: isExpanded || isGpsFilled ? 2 : 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Header (tappable to toggle)
          InkWell(
            onTap: onToggle,
            borderRadius: isExpanded 
                ? const BorderRadius.vertical(top: Radius.circular(14))
                : BorderRadius.circular(14),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(title, style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: TomsTheme.textSecondary, letterSpacing: 1.0)),
                        const SizedBox(height: 4),
                        Text(
                          selectedValue?.name ?? hint,
                          style: TextStyle(
                            fontSize: 16, 
                            fontWeight: FontWeight.w700, 
                            color: selectedValue != null ? TomsTheme.textPrimary : TomsTheme.textSecondary
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (trailing != null) ...[
                    trailing!,
                    const SizedBox(width: 8),
                  ],
                  Icon(isExpanded ? LucideIcons.chevronUp : LucideIcons.chevronDown, size: 20, color: TomsTheme.textSecondary),
                ],
              ),
            ),
          ),
          
          // Expanded content
          AnimatedCrossFade(
            firstChild: const SizedBox(width: double.infinity, height: 0),
            secondChild: Container(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: available.map((s) {
                  final isSelected = s == selectedValue;
                  return GestureDetector(
                    onTap: () => onSelect(s),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                      decoration: BoxDecoration(
                        color: isSelected ? TomsTheme.accent : TomsTheme.bgCardLight,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: isSelected ? TomsTheme.accent : TomsTheme.border),
                      ),
                      child: Text(
                        s.name,
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: isSelected ? TomsTheme.bgDark : TomsTheme.textPrimary,
                        ),
                      ),
                    ),
                  );
                }).toList(),
              ),
            ),
            crossFadeState: isExpanded ? CrossFadeState.showSecond : CrossFadeState.showFirst,
            duration: const Duration(milliseconds: 250),
          ),
        ],
      ),
    );
  }
}
