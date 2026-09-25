import 'package:flutter/material.dart';

class ImageViewerController extends ChangeNotifier {
  ImageViewerController({required int initialIndex, required this.imageCount})
    : currentIndex = initialIndex < 0
          ? 0
          : initialIndex >= imageCount
          ? imageCount - 1
          : initialIndex {
    pageController = PageController(initialPage: currentIndex);
  }

  late final PageController pageController;
  final int imageCount;
  int currentIndex;

  bool get canGoPrevious => currentIndex > 0;
  bool get canGoNext => currentIndex < imageCount - 1;

  void onPageChanged(int index) {
    currentIndex = index;
    notifyListeners();
  }

  void goTo(int index) {
    if (index < 0 || index >= imageCount || index == currentIndex) {
      return;
    }
    pageController.animateToPage(
      index,
      duration: const Duration(milliseconds: 220),
      curve: Curves.easeOut,
    );
  }

  @override
  void dispose() {
    pageController.dispose();
    super.dispose();
  }
}
