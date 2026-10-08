
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/app_state.dart';
import '../services/auth_service.dart';
import '../services/sync_service.dart';
import '../services/usb_service.dart';
import '../services/page_controller_service.dart';
import '../models/models.dart';
import 'debug_screen.dart';
import 'shift_summary_screen.dart';
import 'shift_history_screen.dart';
import '../widgets/proximity_alarm_banner.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final pcs = context.read<PageControllerService>();
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 12,
        title: Row(children: [
          // Back to boarding chip
          GestureDetector(
            onTap: pcs.goToBoarding,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: TomsTheme.bgCardLight,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: TomsTheme.border),
              ),
              child: const Row(mainAxisSize: MainAxisSize.min, children: [
                Icon(LucideIcons.chevronLeft, size: 12, color: TomsTheme.textSecondary),
                SizedBox(width: 4),
                Text('Boarding', style: TextStyle(fontSize: 11, color: TomsTheme.textSecondary, fontWeight: FontWeight.w600)),
              ]),
            ),
          ),
          const SizedBox(width: 10),
          Container(
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              gradient: const LinearGradient(colors: [TomsTheme.accent, Color(0xFF0088FF)]),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(LucideIcons.bus, size: 16, color: Colors.white),
          ),
          const SizedBox(width: 8),
          const Text('Dashboard'),
        ]),
        actions: [
          Consumer<UsbService>(builder: (_, usb, __) => _NfcChip(connected: usb.isConnected)),
          const SizedBox(width: 4),
          PopupMenuButton<String>(
            icon: const Icon(LucideIcons.moreVertical, size: 20),
            color: TomsTheme.bgCard,
            onSelected: (v) {
              if (v == 'usb') {
                final usb = context.read<UsbService>();
                usb.isConnected ? usb.disconnect() : context.read<AppState>().connectUsb();
              } else if (v == 'history') {
                Navigator.push(context, MaterialPageRoute(builder: (_) => const ShiftHistoryScreen()));
              } else if (v == 'end_shift') {
                _confirmEndShift(context);
              } else if (v == 'debug') {
                Navigator.push(context, MaterialPageRoute(builder: (_) => const DebugScreen()));
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(value: 'usb', child: Consumer<UsbService>(builder: (_, usb, __) => Row(children: [
                Icon(usb.isConnected ? LucideIcons.unplug : LucideIcons.plug, size: 16, color: TomsTheme.textSecondary),
                const SizedBox(width: 10),
                Text(usb.isConnected ? 'Disconnect USB' : 'Connect USB', style: const TextStyle(color: TomsTheme.textPrimary)),
              ]))),
              const PopupMenuItem(value: 'history', child: Row(children: [
                Icon(LucideIcons.history, size: 16, color: TomsTheme.textSecondary),
                SizedBox(width: 10),
                Text('Shift History', style: TextStyle(color: TomsTheme.textPrimary)),
              ])),
              const PopupMenuItem(value: 'debug', child: Row(children: [
                Icon(LucideIcons.terminal, size: 16, color: TomsTheme.textSecondary),
                SizedBox(width: 10),
                Text('Debug Console', style: TextStyle(color: TomsTheme.textPrimary)),
              ])),
              const PopupMenuDivider(),
              const PopupMenuItem(value: 'end_shift', child: Row(children: [
                Icon(LucideIcons.logOut, size: 16, color: TomsTheme.danger),
                SizedBox(width: 10),
                Text('End Shift', style: TextStyle(color: TomsTheme.danger, fontWeight: FontWeight.w700)),
              ])),
            ],
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: RefreshIndicator(
        color: TomsTheme.accent,
        onRefresh: () => context.read<AppState>().refreshData(),
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 80),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            _SyncStatusBar(),
            _DeviceStatusCard(),
            const SizedBox(height: 16),
            _StatsRow(),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ShiftSummaryScreen())),
                icon: const Icon(LucideIcons.clipboardList, size: 18),
                label: const Text('VIEW SHIFT SUMMARY', style: TextStyle(fontWeight: FontWeight.w700, letterSpacing: 1.0)),
                style: ElevatedButton.styleFrom(
                  backgroundColor: TomsTheme.bgCardLight,
                  foregroundColor: TomsTheme.textPrimary,
                  elevation: 0,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: const BorderSide(color: TomsTheme.border)),
                ),
              ),
            ),
            const SizedBox(height: 16),
            const ProximityAlarmBanner(),
            const SizedBox(height: 16),
            _OccupancyPanel(),
            const SizedBox(height: 16),
            Text('RECENT TRANSACTIONS', style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 12),
            _TransactionList(),
          ]),
        ),
      ),
    );
  }
}

// ── NFC Status Chip ──────────────────────────────────────────────────────────
class _NfcChip extends StatelessWidget {
  final bool connected;
  const _NfcChip({required this.connected});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: connected ? TomsTheme.success.withValues(alpha: 0.15) : TomsTheme.danger.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: connected ? TomsTheme.success : TomsTheme.danger),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 8, height: 8, decoration: BoxDecoration(shape: BoxShape.circle, color: connected ? TomsTheme.success : TomsTheme.danger)),
        const SizedBox(width: 6),
        Text(connected ? 'NFC' : 'OFFLINE',
            style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: connected ? TomsTheme.success : TomsTheme.danger, letterSpacing: 1)),
      ]),
    );
  }
}

// ── Device Status Card ───────────────────────────────────────────────────────
class _DeviceStatusCard extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer2<UsbService, AppState>(builder: (context, usb, app, _) {
      final status = usb.lastStatus;
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(LucideIcons.cpu, size: 16, color: TomsTheme.accent),
              const SizedBox(width: 8),
              Text('MASTER DEVICE', style: Theme.of(context).textTheme.labelLarge),
            ]),
            const SizedBox(height: 16),
            if (!usb.isConnected)
              Center(child: Column(children: [
                Icon(LucideIcons.usb, size: 40, color: TomsTheme.textSecondary.withValues(alpha: 0.3)),
                const SizedBox(height: 8),
                const Text('Connect via USB OTG to view device status', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 13)),
              ]))
            else if (status != null)
              Row(children: [
                _StatusItem(icon: LucideIcons.battery, label: 'Battery', value: '${status.batteryPct}%',
                    color: status.batteryPct > 20 ? TomsTheme.success : TomsTheme.danger),
                const SizedBox(width: 24),
                _StatusItem(icon: LucideIcons.hardDrive, label: 'Pending', value: '${status.pendingLogs}', color: TomsTheme.warning),
                const SizedBox(width: 24),
                _StatusItem(icon: LucideIcons.waves, label: 'NFC', value: status.uartState == 3 ? 'Ready' : 'Idle', color: TomsTheme.accent),
              ])
            else
              const Center(child: SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2, color: TomsTheme.accent))),
            if (usb.lastError != null) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(color: TomsTheme.danger.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(8)),
                child: Text(usb.lastError!, style: const TextStyle(color: TomsTheme.danger, fontSize: 12)),
              ),
            ],
          ]),
        ),
      );
    });
  }
}

class _StatusItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final Color color;
  const _StatusItem({required this.icon, required this.label, required this.value, required this.color});

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      Icon(icon, size: 20, color: color),
      const SizedBox(height: 6),
      Text(value, style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label, style: const TextStyle(fontSize: 11, color: TomsTheme.textSecondary)),
    ]);
  }
}

// ── Stats Row ────────────────────────────────────────────────────────────────
class _StatsRow extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<AppState>(builder: (_, app, __) {
      final occupancyPct = (app.occupancyRate * 100).round();
      return Row(children: [
        Expanded(child: _StatCard(icon: LucideIcons.coins, label: "TODAY'S REVENUE", value: app.todayRevenueFormatted, gradient: const [Color(0xFF00D4AA), Color(0xFF0088FF)])),
        const SizedBox(width: 8),
        Expanded(child: _StatCard(icon: LucideIcons.users, label: 'PASSENGERS', value: '${app.todayPassengers}', gradient: const [Color(0xFFFF9F43), Color(0xFFFF6348)])),
        const SizedBox(width: 8),
        Expanded(child: _StatCard(icon: LucideIcons.barChart, label: 'OCCUPANCY', value: '$occupancyPct%', gradient: [
          occupancyPct > 80 ? TomsTheme.danger : occupancyPct > 50 ? TomsTheme.warning : TomsTheme.success,
          const Color(0xFF0088FF),
        ])),
      ]);
    });
  }
}

class _StatCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final List<Color> gradient;
  const _StatCard({required this.icon, required this.label, required this.value, required this.gradient});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(
            padding: const EdgeInsets.all(7),
            decoration: BoxDecoration(gradient: LinearGradient(colors: gradient), borderRadius: BorderRadius.circular(8)),
            child: Icon(icon, size: 16, color: Colors.white),
          ),
          const SizedBox(height: 10),
          Text(value, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: TomsTheme.textPrimary)),
          const SizedBox(height: 2),
          Text(label, style: const TextStyle(fontSize: 9, fontWeight: FontWeight.w600, color: TomsTheme.textSecondary, letterSpacing: 1)),
        ]),
      ),
    );
  }
}

// ── Occupancy Panel ──────────────────────────────────────────────────────────
class _OccupancyPanel extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<AppState>(builder: (_, app, __) {
      final slots = app.activeSlots;
      final rate = app.occupancyRate;
      final rateColor = rate > 0.8 ? TomsTheme.danger : rate > 0.5 ? TomsTheme.warning : TomsTheme.success;

      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Text('ACTIVE PASSENGERS', style: Theme.of(context).textTheme.labelLarge),
          const Spacer(),
          Text('${app.slavesDeployed}/${app.maxCapacity}',
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: rateColor)),
          const SizedBox(width: 6),
          Text('• ${app.unpaidCount} unpaid',
              style: const TextStyle(fontSize: 11, color: TomsTheme.textSecondary)),
        ]),
        const SizedBox(height: 8),
        // Capacity bar
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(
            value: rate.clamp(0.0, 1.0),
            minHeight: 8,
            backgroundColor: TomsTheme.border,
            valueColor: AlwaysStoppedAnimation<Color>(rateColor),
          ),
        ),
        const SizedBox(height: 12),
        if (slots.isEmpty)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Center(child: Column(children: [
                Icon(LucideIcons.armchair, size: 36, color: TomsTheme.textSecondary.withValues(alpha: 0.3)),
                const SizedBox(height: 8),
                const Text('No active passengers', style: TextStyle(color: TomsTheme.textSecondary)),
                const SizedBox(height: 4),
                const Text('Swipe left to begin boarding', style: TextStyle(color: TomsTheme.textSecondary, fontSize: 12)),
              ])),
            ),
          )
        else
          ...slots.map((slot) => _PassengerSlotCard(slot: slot)),
      ]);
    });
  }
}

// ── Passenger Slot Card ──────────────────────────────────────────────────────
class _PassengerSlotCard extends StatefulWidget {
  final PassengerSlot slot;
  const _PassengerSlotCard({required this.slot});

  @override
  State<_PassengerSlotCard> createState() => _PassengerSlotCardState();
}

class _PassengerSlotCardState extends State<_PassengerSlotCard>
    with SingleTickerProviderStateMixin {
  bool _expanded = false;
  bool _showCalc = false;
  late AnimationController _pulse;
  final _tenderedCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _pulse = AnimationController(vsync: this, duration: const Duration(milliseconds: 700));
    if (widget.slot.state == PassengerSlotState.alarming) _pulse.repeat(reverse: true);
  }

  @override
  void didUpdateWidget(_PassengerSlotCard old) {
    super.didUpdateWidget(old);
    if (widget.slot.state == PassengerSlotState.alarming) {
      _pulse.repeat(reverse: true);
    } else {
      _pulse.stop();
      _pulse.value = 0;
    }
  }

  @override
  void dispose() {
    _pulse.dispose();
    _tenderedCtrl.dispose();
    super.dispose();
  }

  Color get _stateColor {
    return switch (widget.slot.state) {
      PassengerSlotState.paid => TomsTheme.success,
      PassengerSlotState.alarming => TomsTheme.danger,
      _ => TomsTheme.warning,
    };
  }

  String get _stateLabel {
    return switch (widget.slot.state) {
      PassengerSlotState.paid => 'PAID',
      PassengerSlotState.alarming => 'ALARM',
      _ => 'UNPAID',
    };
  }

  String _formatFare(int c) => '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final slot = widget.slot;
    final app = context.read<AppState>();
    final session = app.getSessionBySlaveUid(slot.slaveUid);

    return AnimatedBuilder(
      animation: _pulse,
      builder: (_, __) {
        final pulseOpacity = widget.slot.state == PassengerSlotState.alarming
            ? 0.08 + (_pulse.value * 0.12)
            : 0.0;
        return Container(
          margin: const EdgeInsets.only(bottom: 8),
          decoration: BoxDecoration(
            color: TomsTheme.bgCard,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: _stateColor.withValues(alpha: 0.4)),
            boxShadow: widget.slot.state == PassengerSlotState.alarming
                // Reduced blurRadius from 16 to 8 to prevent raster jank during animation
                ? [BoxShadow(color: TomsTheme.danger.withValues(alpha: pulseOpacity), blurRadius: 8, spreadRadius: 2)]
                : [],
          ),
          child: Column(children: [
            // ── Header row ───
            InkWell(
              onTap: () => setState(() => _expanded = !_expanded),
              borderRadius: BorderRadius.circular(16),
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Row(children: [
                  // Slot badge
                  Container(
                    width: 42, height: 42,
                    decoration: BoxDecoration(
                      color: _stateColor.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Center(child: Text('#${slot.slotNumber}',
                        style: TextStyle(color: _stateColor, fontWeight: FontWeight.w800, fontSize: 14))),
                  ),
                  const SizedBox(width: 12),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(slot.destination, style: const TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w600, fontSize: 14)),
                    const SizedBox(height: 2),
                    Text('${slot.passengerType.label} · ${_formatFare(slot.fareCentavos)}',
                        style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 12)),
                  ])),
                  // State badge
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: _stateColor.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(_stateLabel, style: TextStyle(color: _stateColor, fontSize: 10, fontWeight: FontWeight.w700)),
                  ),
                  const SizedBox(width: 8),
                  Icon(_expanded ? LucideIcons.chevronUp : LucideIcons.chevronDown, size: 16, color: TomsTheme.textSecondary),
                ]),
              ),
            ),
            // ── Expanded Details ───
            if (_expanded) ...[
              const Divider(color: TomsTheme.border, height: 1),
              Padding(
                padding: const EdgeInsets.all(14),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  if (session != null) ...[
                    _detailRow('From', session.boarding.name),
                    _detailRow('Boarded', _timeAgo(session.boardedAt)),
                    _detailRow('Base Fare', _formatFare(session.baseFareCentavos)),
                    if (session.baseFareCentavos != session.finalFareCentavos)
                      _detailRow('Discount', '- ${_formatFare(session.baseFareCentavos - session.finalFareCentavos)}', valueColor: TomsTheme.success),
                    _detailRow('Final Fare', _formatFare(session.finalFareCentavos), valueColor: TomsTheme.accent),
                    const SizedBox(height: 12),
                  ],
                  // Change calculator
                  if (_showCalc) ...[
                    TextField(
                      controller: _tenderedCtrl,
                      keyboardType: TextInputType.number,
                      style: const TextStyle(color: TomsTheme.textPrimary),
                      decoration: InputDecoration(
                        labelText: 'Tendered Amount (₱)',
                        labelStyle: const TextStyle(color: TomsTheme.textSecondary),
                        prefixText: '₱ ',
                        prefixStyle: const TextStyle(color: TomsTheme.accent),
                        enabledBorder: OutlineInputBorder(borderSide: const BorderSide(color: TomsTheme.border), borderRadius: BorderRadius.circular(10)),
                        focusedBorder: OutlineInputBorder(borderSide: const BorderSide(color: TomsTheme.accent), borderRadius: BorderRadius.circular(10)),
                        filled: true, fillColor: TomsTheme.bgCardLight,
                      ),
                      onChanged: (_) => setState(() {}),
                    ),
                    const SizedBox(height: 8),
                    if (_tenderedCtrl.text.isNotEmpty) Builder(builder: (_) {
                      final tendered = (double.tryParse(_tenderedCtrl.text) ?? 0) * 100;
                      final change = tendered - slot.fareCentavos;
                      return Text(
                        change >= 0 ? 'Change: ${_formatFare(change.round())}' : 'Insufficient ₱${_formatFare((-change).round())} short',
                        style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: change >= 0 ? TomsTheme.success : TomsTheme.danger),
                      );
                    }),
                    const SizedBox(height: 12),
                  ],
                  // Action buttons
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    if (slot.state != PassengerSlotState.paid) ...[
                      _ActionBtn(label: _showCalc ? 'Confirm Paid' : 'Collect Payment', color: TomsTheme.success, icon: LucideIcons.checkCircle,
                          onTap: () {
                            if (!_showCalc) {
                              setState(() => _showCalc = true);
                            } else {
                              app.markPaidManual(slot.slaveUid);
                              setState(() { _showCalc = false; _tenderedCtrl.clear(); });
                            }
                          }),
                    ],
                    _ActionBtn(label: 'Release Slave', color: TomsTheme.danger, icon: LucideIcons.logOut,
                        onTap: () {
                          app.releaseSlotManual(slot.slaveUid);
                        }),
                  ]),
                ]),
              ),
            ],
          ]),
        );
      },
    );
  }

  Widget _detailRow(String label, String value, {Color? valueColor}) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Row(children: [
      Text(label, style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 12)),
      const Spacer(),
      Text(value, style: TextStyle(color: valueColor ?? TomsTheme.textPrimary, fontSize: 12, fontWeight: FontWeight.w600)),
    ]),
  );

  String _timeAgo(DateTime t) {
    final diff = DateTime.now().difference(t);
    if (diff.inMinutes < 1) return 'just now';
    if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
    return '${diff.inHours}h ${diff.inMinutes % 60}m ago';
  }
}

class _ActionBtn extends StatelessWidget {
  final String label;
  final Color color;
  final IconData icon;
  final VoidCallback onTap;
  const _ActionBtn({required this.label, required this.color, required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: color.withValues(alpha: 0.4)),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 6),
          Text(label, style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
        ]),
      ),
    );
  }
}

// ── Transaction List ─────────────────────────────────────────────────────────
class _TransactionList extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<AppState>(builder: (_, app, __) {
      if (app.recentLogs.isEmpty) {
        return Card(child: Padding(padding: const EdgeInsets.all(40),
          child: Center(child: Column(children: [
            Icon(LucideIcons.inbox, size: 40, color: TomsTheme.textSecondary.withValues(alpha: 0.3)),
            const SizedBox(height: 12),
            const Text('No transactions yet', style: TextStyle(color: TomsTheme.textSecondary)),
          ]))));
      }
      return Column(children: app.recentLogs.map((log) => _TxTile(log: log)).toList());
    });
  }
}

class _TxTile extends StatelessWidget {
  final PassengerLog log;
  const _TxTile({required this.log});

  String _formatFare(int c) => '₱${(c ~/ 100)}.${(c % 100).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final time = DateTime.fromMillisecondsSinceEpoch(log.timestamp * 1000);
    final timeStr = '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
    final type = PassengerType.values[log.passengerType.clamp(0, PassengerType.values.length - 1)];

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(children: [
          Container(
            width: 40, height: 40,
            decoration: BoxDecoration(color: TomsTheme.accent.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(10)),
            child: Center(child: Text('S${log.seatNumber}', style: const TextStyle(color: TomsTheme.accent, fontWeight: FontWeight.w700, fontSize: 13))),
          ),
          const SizedBox(width: 12),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${log.destinationStop.isNotEmpty ? log.destinationStop : 'Route ${log.routeId}'} · ${type.label}',
                style: const TextStyle(color: TomsTheme.textPrimary, fontWeight: FontWeight.w600, fontSize: 13)),
            const SizedBox(height: 2),
            Text(timeStr, style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 12)),
          ])),
          Text(_formatFare(log.fareCentavos), style: const TextStyle(color: TomsTheme.success, fontWeight: FontWeight.w700, fontSize: 15)),
          const SizedBox(width: 8),
          Icon(log.synced ? LucideIcons.uploadCloud : LucideIcons.cloudOff, size: 14,
              color: log.synced ? TomsTheme.success : TomsTheme.textSecondary),
        ]),
      ),
    );
  }
}

// ── End Shift Confirmation ────────────────────────────────────────────────────
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
            Navigator.pop(ctx);
            context.read<AppState>().endShift();
            context.read<AuthService>().assignVehicle('');
          },
          child: const Text('End Shift', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700)),
        ),
      ],
    ),
  );
}

// ── Sync Status Bar ───────────────────────────────────────────────────────────
class _SyncStatusBar extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<SyncService>(
      builder: (_, sync, __) {
        if (sync.pendingSyncCount == 0) return const SizedBox.shrink();
        return Container(
          margin: const EdgeInsets.only(bottom: 12),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: TomsTheme.warning.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: TomsTheme.warning.withValues(alpha: 0.4)),
          ),
          child: Row(children: [
            const Icon(LucideIcons.cloudOff, size: 14, color: TomsTheme.warning),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '${sync.pendingSyncCount} event${sync.pendingSyncCount == 1 ? '' : 's'} queued — will sync when online',
                style: const TextStyle(fontSize: 12, color: TomsTheme.warning),
              ),
            ),
          ]),
        );
      },
    );
  }
}
