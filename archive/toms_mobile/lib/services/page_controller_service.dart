import 'package:flutter/material.dart';

/// Shared [PageController] wrapper exposed via [ChangeNotifierProvider].
///
/// Allows any widget in the tree (e.g. [ProximityAlarmBanner]) to
/// programmatically jump between the Boarding (page 0) and Dashboard (page 1)
/// pages without needing a direct reference to the [PageView].
class PageControllerService extends ChangeNotifier {
  final PageController pageController = PageController(initialPage: 0);

  int _currentPage = 0;
  int get currentPage => _currentPage;

  bool get isOnBoarding => _currentPage == 0;
  bool get isOnDashboard => _currentPage == 1;

  void onPageChanged(int page) {
    _currentPage = page;
    notifyListeners();
  }

  void goToBoarding() {
    if (pageController.hasClients) {
      pageController.animateToPage(
        0,
        duration: const Duration(milliseconds: 350),
        curve: Curves.easeInOut,
      );
    }
  }

  void goToDashboard() {
    if (pageController.hasClients) {
      pageController.animateToPage(
        1,
        duration: const Duration(milliseconds: 350),
        curve: Curves.easeInOut,
      );
    }
  }

  @override
  void dispose() {
    pageController.dispose();
    super.dispose();
  }
}
