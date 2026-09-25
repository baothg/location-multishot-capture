import 'package:flutter/services.dart';

import 'models/camera_config.dart';
import 'models/camera_result.dart';

abstract class NativeCameraBridge {
  Future<CameraResult> openCamera(CameraConfig config);
  Future<void> clearSessionImages();
}

class MethodChannelNativeCameraBridge implements NativeCameraBridge {
  const MethodChannelNativeCameraBridge([MethodChannel? channel])
    : _channel =
          channel ??
          const MethodChannel('location_multishot_capture/native_camera');

  final MethodChannel _channel;

  @override
  Future<CameraResult> openCamera(CameraConfig config) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'openCamera',
      config.toMap(),
    );
    return CameraResult.fromMap(result ?? const {'status': 'error'});
  }

  @override
  Future<void> clearSessionImages() {
    return _channel.invokeMethod<void>('clearSessionImages');
  }
}
