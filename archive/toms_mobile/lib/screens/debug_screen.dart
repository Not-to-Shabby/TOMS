import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../theme/toms_theme.dart';
import '../services/usb_service.dart';

/// Real-time debug console for monitoring USB communication with the Master.
class DebugScreen extends StatefulWidget {
  const DebugScreen({super.key});

  @override
  State<DebugScreen> createState() => _DebugScreenState();
}

class _DebugScreenState extends State<DebugScreen> {
  final TextEditingController _cmdController = TextEditingController();
  bool _autoScroll = true;

  // Quick-access debug commands
  static const _quickCommands = [
    ('Handshake', '{"cmd":"handshake"}'),
    ('Status', '{"cmd":"get_status"}'),
    ('Ping', '{"cmd":"ping"}'),
    ('Fare Sync', '{"cmd":"sync_fare_table"}'),
  ];

  @override
  void dispose() {
    _cmdController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(5),
              decoration: BoxDecoration(
                color: TomsTheme.accent.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(6),
              ),
              child: const Icon(LucideIcons.terminal, size: 16, color: TomsTheme.accent),
            ),
            const SizedBox(width: 10),
            const Text('Debug Console'),
          ],
        ),
        actions: [
          Consumer<UsbService>(
            builder: (_, usb, __) {
              return IconButton(
                icon: const Icon(LucideIcons.trash2, size: 20),
                tooltip: 'Clear log',
                onPressed: () => usb.clearLog(),
              );
            },
          ),
          IconButton(
            icon: Icon(
              _autoScroll ? LucideIcons.arrowDownToLine : LucideIcons.pause,
              size: 20,
            ),
            tooltip: _autoScroll ? 'Auto-scroll ON' : 'Auto-scroll OFF',
            onPressed: () => setState(() => _autoScroll = !_autoScroll),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        children: [
          // Connection status bar
          Consumer<UsbService>(
            builder: (_, usb, __) {
              return Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                decoration: BoxDecoration(
                  color: usb.isConnected
                      ? TomsTheme.success.withValues(alpha: 0.08)
                      : TomsTheme.danger.withValues(alpha: 0.08),
                  border: Border(
                    bottom: BorderSide(
                      color: usb.isConnected ? TomsTheme.success : TomsTheme.danger,
                      width: 1,
                    ),
                  ),
                ),
                child: Row(
                  children: [
                    Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: usb.isConnected ? TomsTheme.success : TomsTheme.danger,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Text(
                      usb.isConnected
                          ? 'Connected: ${usb.deviceName}'
                          : 'Disconnected',
                      style: TextStyle(
                        color: usb.isConnected ? TomsTheme.success : TomsTheme.danger,
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                      ),
                    ),
                    const Spacer(),
                    if (usb.lastStatus != null)
                      Text(
                        'BAT ${usb.lastStatus!.batteryPct}% • ${usb.rawLog.length} msgs',
                        style: const TextStyle(
                          color: TomsTheme.textSecondary,
                          fontSize: 11,
                        ),
                      ),
                  ],
                ),
              );
            },
          ),

          // Quick command chips
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              children: _quickCommands.map((cmd) {
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ActionChip(
                    label: Text(cmd.$1),
                    labelStyle: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: TomsTheme.accent,
                    ),
                    backgroundColor: TomsTheme.bgCard,
                    side: const BorderSide(color: TomsTheme.border),
                    avatar: const Icon(LucideIcons.play, size: 14, color: TomsTheme.accent),
                    onPressed: () {
                      context.read<UsbService>().sendRawDebug(cmd.$2);
                    },
                  ),
                );
              }).toList(),
            ),
          ),

          const Divider(height: 1, color: TomsTheme.border),

          // Log viewer
          Expanded(
            child: Consumer<UsbService>(
              builder: (_, usb, __) {
                final logs = usb.rawLog;
                if (logs.isEmpty) {
                  return Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          LucideIcons.terminal,
                          size: 48,
                          color: TomsTheme.textSecondary.withValues(alpha: 0.2),
                        ),
                        const SizedBox(height: 12),
                        const Text(
                          'No messages yet',
                          style: TextStyle(color: TomsTheme.textSecondary),
                        ),
                        const SizedBox(height: 4),
                        const Text(
                          'Connect USB and send commands to see logs',
                          style: TextStyle(color: TomsTheme.textSecondary, fontSize: 12),
                        ),
                      ],
                    ),
                  );
                }

                return ListView.builder(
                  reverse: false,
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  itemCount: logs.length,
                  itemBuilder: (context, index) {
                    final entry = logs[index];
                    final isTx = entry.contains('] TX');
                    final isRx = entry.contains('] RX');
                    final isError = entry.toLowerCase().contains('error');

                    Color lineColor;
                    IconData lineIcon;
                    if (isError) {
                      lineColor = TomsTheme.danger;
                      lineIcon = LucideIcons.alertCircle;
                    } else if (isTx) {
                      lineColor = TomsTheme.warning;
                      lineIcon = LucideIcons.arrowUpRight;
                    } else if (isRx) {
                      lineColor = TomsTheme.accent;
                      lineIcon = LucideIcons.arrowDownLeft;
                    } else {
                      lineColor = TomsTheme.textSecondary;
                      lineIcon = LucideIcons.minus;
                    }

                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(lineIcon, size: 14, color: lineColor),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              entry,
                              style: TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 11,
                                color: lineColor,
                                height: 1.4,
                              ),
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                );
              },
            ),
          ),

          // Command input bar
          Container(
            padding: const EdgeInsets.all(12),
            decoration: const BoxDecoration(
              color: TomsTheme.bgCard,
              border: Border(top: BorderSide(color: TomsTheme.border)),
            ),
            child: Row(
              children: [
                const Text(
                  '\$ ',
                  style: TextStyle(
                    color: TomsTheme.accent,
                    fontFamily: 'monospace',
                    fontWeight: FontWeight.w700,
                    fontSize: 16,
                  ),
                ),
                Expanded(
                  child: TextField(
                    controller: _cmdController,
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 13,
                      color: TomsTheme.textPrimary,
                    ),
                    decoration: const InputDecoration(
                      hintText: '{"cmd":"handshake"}',
                      hintStyle: TextStyle(color: TomsTheme.textSecondary, fontSize: 13),
                      border: InputBorder.none,
                      isDense: true,
                      contentPadding: EdgeInsets.symmetric(vertical: 8),
                    ),
                    onSubmitted: _sendCommand,
                  ),
                ),
                const SizedBox(width: 8),
                Material(
                  color: TomsTheme.accent,
                  borderRadius: BorderRadius.circular(8),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(8),
                    onTap: () => _sendCommand(_cmdController.text),
                    child: const Padding(
                      padding: EdgeInsets.all(8),
                      child: Icon(LucideIcons.send, size: 18, color: Colors.white),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _sendCommand(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return;

    context.read<UsbService>().sendRawDebug(trimmed);
    _cmdController.clear();
  }
}
