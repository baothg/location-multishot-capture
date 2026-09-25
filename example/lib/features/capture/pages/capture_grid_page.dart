import 'dart:async';

import 'package:flutter/material.dart';
import 'package:location_multishot_capture/location_multishot_capture.dart';
import 'package:provider/provider.dart';

import '../controllers/capture_controller.dart';
import '../services/session_image_store.dart';
import '../widgets/add_photo_grid_tile.dart';
import '../widgets/captured_image_tile.dart';
import '../widgets/delete_confirmation_dialog.dart';
import 'image_viewer_page.dart';

class CaptureGridPage extends StatelessWidget {
  const CaptureGridPage({
    super.key,
    this.cameraBridge = const MethodChannelNativeCameraBridge(),
    this.imageStore = const SessionImageStore(),
    this.autoOpenCamera = true,
  });

  final NativeCameraBridge cameraBridge;
  final SessionImageStore imageStore;
  final bool autoOpenCamera;

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<CaptureController>(
      create: (_) {
        final controller = CaptureController(
          cameraBridge: cameraBridge,
          imageStore: imageStore,
          autoOpenCamera: autoOpenCamera,
        );
        unawaited(controller.initialize());
        return controller;
      },
      lazy: false,
      child: const _CaptureGridView(),
    );
  }
}

class _CaptureGridView extends StatelessWidget {
  const _CaptureGridView();

  Future<void> _deleteImage(BuildContext context, CapturedImage image) async {
    final confirmed = await showDeleteConfirmationDialog(
      context,
      title: 'Xóa ảnh',
      message: 'Bạn có chắc muốn xóa ảnh này không?',
    );
    if (!confirmed || !context.mounted) {
      return;
    }
    await context.read<CaptureController>().deleteImage(image);
  }

  Future<void> _deleteAllImages(BuildContext context) async {
    final controller = context.read<CaptureController>();
    if (controller.images.isEmpty) {
      return;
    }

    final confirmed = await showDeleteConfirmationDialog(
      context,
      title: 'Xóa tất cả ảnh',
      message: 'Bạn có chắc muốn xóa toàn bộ ảnh đã chụp không?',
    );
    if (!confirmed || !context.mounted) {
      return;
    }
    await controller.deleteAllImages();
  }

  void _openViewer(BuildContext context, int index) {
    final images = context.read<CaptureController>().images;
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ImageViewerPage(
          images: List.unmodifiable(images),
          initialIndex: index,
        ),
      ),
    );
  }

  void _showNoticeIfNeeded(BuildContext context, String? message) {
    if (message == null) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!context.mounted) {
        return;
      }
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(message)));
      context.read<CaptureController>().consumeNotice();
    });
  }

  @override
  Widget build(BuildContext context) {
    final message = context.select<CaptureController, String?>(
      (controller) => controller.noticeMessage,
    );
    _showNoticeIfNeeded(context, message);

    final controller = context.watch<CaptureController>();
    final images = controller.images;
    final shortestSide = MediaQuery.sizeOf(context).shortestSide;
    final columnCount = shortestSide >= 600 ? 4 : 2;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Ảnh đã chụp'),
        actions: [
          IconButton(
            tooltip: 'Xóa tất cả',
            onPressed: images.isEmpty ? null : () => _deleteAllImages(context),
            icon: const Icon(Icons.delete_outline, color: Colors.red),
          ),
        ],
      ),
      body: SafeArea(
        child: GridView.builder(
          padding: const EdgeInsets.all(16),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: columnCount,
            crossAxisSpacing: 12,
            mainAxisSpacing: 12,
            childAspectRatio: 1,
          ),
          itemCount: images.length + 1,
          itemBuilder: (context, index) {
            if (index == 0) {
              return AddPhotoGridTile(onTap: controller.openCamera);
            }

            final imageIndex = index - 1;
            final image = images[imageIndex];
            return CapturedImageTile(
              image: image,
              onTap: () => _openViewer(context, imageIndex),
              onDelete: () => _deleteImage(context, image),
            );
          },
        ),
      ),
    );
  }
}
