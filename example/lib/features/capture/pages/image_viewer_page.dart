import 'dart:io';

import 'package:flutter/material.dart';
import 'package:location_multishot_capture/location_multishot_capture.dart';
import 'package:provider/provider.dart';

import '../controllers/image_viewer_controller.dart';

class ImageViewerPage extends StatelessWidget {
  const ImageViewerPage({
    required this.images,
    required this.initialIndex,
    super.key,
  });

  final List<CapturedImage> images;
  final int initialIndex;

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<ImageViewerController>(
      create: (_) => ImageViewerController(
        initialIndex: initialIndex,
        imageCount: images.length,
      ),
      child: _ImageViewerView(images: images),
    );
  }
}

class _ImageViewerView extends StatelessWidget {
  const _ImageViewerView({required this.images});

  final List<CapturedImage> images;

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<ImageViewerController>();
    final currentIndex = controller.currentIndex;

    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Stack(
          children: [
            PageView.builder(
              controller: controller.pageController,
              itemCount: images.length,
              onPageChanged: controller.onPageChanged,
              itemBuilder: (context, index) {
                final image = images[index];
                return InteractiveViewer(
                  child: Center(
                    child: Image.file(
                      File(image.filePath),
                      fit: BoxFit.contain,
                      errorBuilder: (context, error, stackTrace) {
                        return const Icon(
                          Icons.broken_image_outlined,
                          color: Colors.white,
                          size: 64,
                        );
                      },
                    ),
                  ),
                );
              },
            ),
            Positioned(
              top: 8,
              left: 8,
              child: IconButton.filled(
                style: IconButton.styleFrom(backgroundColor: Colors.black54),
                onPressed: () => Navigator.of(context).pop(),
                icon: const Icon(Icons.arrow_back, color: Colors.white),
              ),
            ),
            if (controller.canGoPrevious)
              Align(
                alignment: Alignment.centerLeft,
                child: IconButton.filled(
                  style: IconButton.styleFrom(backgroundColor: Colors.black54),
                  onPressed: () => controller.goTo(currentIndex - 1),
                  icon: const Icon(Icons.chevron_left, color: Colors.white),
                ),
              ),
            if (controller.canGoNext)
              Align(
                alignment: Alignment.centerRight,
                child: IconButton.filled(
                  style: IconButton.styleFrom(backgroundColor: Colors.black54),
                  onPressed: () => controller.goTo(currentIndex + 1),
                  icon: const Icon(Icons.chevron_right, color: Colors.white),
                ),
              ),
            Positioned(
              right: 0,
              bottom: 24,
              left: 0,
              child: Center(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Colors.black54,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 8,
                    ),
                    child: Text(
                      '${currentIndex + 1} / ${images.length}',
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
