import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../services/page_controller_service.dart';
import 'boarding_screen.dart';
import 'dashboard_screen.dart';
import '../theme/toms_theme.dart';

/// Root shell that holds the [PageView] with two pages:
///   - Page 0 (default): [BoardingScreen]
///   - Page 1 (swipe right): [DashboardScreen]
///
/// The [PageControllerService] is provided here so all descendants
/// can navigate programmatically.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  late final PageControllerService _pcs;
  bool _hintDismissed = false;

  @override
  void initState() {
    super.initState();
    _pcs = PageControllerService();
  }

  @override
  void dispose() {
    _pcs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<PageControllerService>.value(
      value: _pcs,
      child: Scaffold(
        backgroundColor: TomsTheme.bgDark,
        resizeToAvoidBottomInset: false,
        body: Stack(children: [
        PageView(
          controller: _pcs.pageController,
          physics: const ClampingScrollPhysics(),
          onPageChanged: (p) {
            _pcs.onPageChanged(p);
            if (p == 1 && !_hintDismissed) setState(() => _hintDismissed = true);
          },
          children: const [
            BoardingScreen(),
            DashboardScreen(),
          ],
        ),

        // Page dot indicator — centered at bottom
        Positioned(
          bottom: 0,
          left: 0,
          right: 0,
          child: _PageIndicator(pcs: _pcs),
        ),

        // Swipe hint — only on boarding page, fades after first swipe
        if (!_hintDismissed)
          Positioned(
            bottom: 28,
            left: 0,
            right: 0,
            child: AnimatedOpacity(
              opacity: _hintDismissed ? 0 : 1,
              duration: const Duration(milliseconds: 400),
              child: const _SwipeHint(),
            ),
          ),
      ]),
      ),
    );
  }
}

// ── Page dot indicator ────────────────────────────────────────────────────────
class _PageIndicator extends StatelessWidget {
  final PageControllerService pcs;
  const _PageIndicator({required this.pcs});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: pcs,
      builder: (_, __) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          _Dot(active: pcs.currentPage == 0),
          const SizedBox(width: 6),
          _Dot(active: pcs.currentPage == 1),
        ]),
      ),
    );
  }
}

class _Dot extends StatelessWidget {
  final bool active;
  const _Dot({required this.active});

  @override
  Widget build(BuildContext context) => AnimatedContainer(
    duration: const Duration(milliseconds: 250),
    width: active ? 20 : 6,
    height: 6,
    decoration: BoxDecoration(
      color: active ? TomsTheme.accent : TomsTheme.textSecondary.withValues(alpha: 0.3),
      borderRadius: BorderRadius.circular(3),
    ),
  );
}

// ── Swipe hint ─────────────────────────────────────────────────────────────────
class _SwipeHint extends StatelessWidget {
  const _SwipeHint();

  @override
  Widget build(BuildContext context) => Center(
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
      decoration: BoxDecoration(
        color: TomsTheme.bgCard.withValues(alpha: 0.85),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: TomsTheme.border),
      ),
      child: const Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.swipe_right_alt, size: 14, color: TomsTheme.textSecondary),
        SizedBox(width: 5),
        Text('Swipe for Dashboard', style: TextStyle(fontSize: 11, color: TomsTheme.textSecondary)),
      ]),
    ),
  );
}
