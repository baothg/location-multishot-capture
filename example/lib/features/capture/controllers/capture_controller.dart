import 'dart:collection';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:location_multishot_capture/location_multishot_capture.dart';

import '../services/session_image_store.dart';

class CaptureController extends ChangeNotifier {
  CaptureController({
    required NativeCameraBridge cameraBridge,
    required SessionImageStore imageStore,
    this.autoOpenCamera = true,
    this.maxImages = 5,
  }) : _cameraBridge = cameraBridge,
       _imageStore = imageStore;

  final NativeCameraBridge _cameraBridge;
  final SessionImageStore _imageStore;
  final bool autoOpenCamera;
  final int maxImages;

  final List<CapturedImage> _images = [];
  bool _initialized = false;
  bool _isOpeningCamera = false;
  bool _disposed = false;
  String? _noticeMessage;

  UnmodifiableListView<CapturedImage> get images =>
      UnmodifiableListView(_images);
  bool get isOpeningCamera => _isOpeningCamera;
  String? get noticeMessage => _noticeMessage;

  Future<void> initialize() async {
    if (_initialized) {
      return;
    }
    _initialized = true;

    try {
      await _cameraBridge.clearSessionImages();
    } on Object {
      _setNotice('Không thể dọn dữ liệu phiên trước.');
    }
    if (autoOpenCamera && _images.isEmpty) {
      await openCamera();
    }
  }

  Future<void> openCamera() async {
    if (_isOpeningCamera) {
      return;
    }

    final config = CameraConfig(
      maxImages: maxImages,
      existingImageCount: _images.length,
    );
    if (config.remainingImageCount <= 0) {
      _setNotice('Bạn đã đạt giới hạn $maxImages ảnh.');
      return;
    }

    _isOpeningCamera = true;
    _notifySafely();
    try {
      final result = await _cameraBridge.openCamera(config);
      if (_disposed) {
        return;
      }

      switch (result.status) {
        case CameraResultStatus.confirmed:
          _appendImages(result.images);
        case CameraResultStatus.cancelled:
          break;
        case CameraResultStatus.error:
          _setNotice(
            result.errorMessage ?? 'Camera gặp lỗi (${result.errorCode}).',
          );
      }
    } on PlatformException catch (error) {
      _setNotice(error.message ?? 'Không thể mở camera.');
    } on Object {
      _setNotice('Không thể mở camera.');
    } finally {
      _isOpeningCamera = false;
      _notifySafely();
    }
  }

  Future<void> deleteImage(CapturedImage image) async {
    final index = _images.indexOf(image);
    if (index < 0) {
      return;
    }

    _images.removeAt(index);
    _notifySafely();
    try {
      await _imageStore.deleteImage(image);
    } on Object {
      if (_disposed) {
        return;
      }
      _images.insert(index, image);
      _setNotice('Không thể xóa ảnh.');
    }
  }

  Future<void> deleteAllImages() async {
    if (_images.isEmpty) {
      return;
    }

    final deletedImages = List<CapturedImage>.of(_images);
    _images.clear();
    _notifySafely();
    try {
      await _imageStore.deleteAll(deletedImages);
    } on Object {
      if (_disposed) {
        return;
      }
      _images.addAll(deletedImages);
      _setNotice('Không thể xóa tất cả ảnh.');
    }
  }

  void consumeNotice() {
    _noticeMessage = null;
    _notifySafely();
  }

  void _appendImages(List<CapturedImage> capturedImages) {
    final remaining = maxImages - _images.length;
    final accepted = capturedImages.take(remaining).toList(growable: false);
    if (accepted.isNotEmpty) {
      _images.addAll(accepted);
      _notifySafely();
    }
    if (accepted.length < capturedImages.length) {
      _setNotice(
        'Một số ảnh không được thêm vì đã đạt giới hạn $maxImages ảnh.',
      );
    }
  }

  void _setNotice(String message) {
    _noticeMessage = message;
    _notifySafely();
  }

  void _notifySafely() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
