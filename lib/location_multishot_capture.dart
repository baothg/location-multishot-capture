/// Flutter plugin that opens a native Android Camera2 capture session.
///
/// The session lets the user take multiple photos, each optionally validated
/// against a target location, and returns normalized JPEG files that are
/// already rotated to match the user's view.
library;

import 'src/models/camera_config.dart';
import 'src/models/camera_result.dart';
import 'src/native_camera_bridge.dart';

export 'src/models/camera_config.dart';
export 'src/models/camera_result.dart';
export 'src/models/captured_image.dart';
export 'src/native_camera_bridge.dart';

/// Entry point for the native multi-shot camera.
class LocationMultishotCapture {
  const LocationMultishotCapture([NativeCameraBridge? bridge])
    : _bridge = bridge ?? const MethodChannelNativeCameraBridge();

  final NativeCameraBridge _bridge;

  /// Opens the native camera screen and resolves when the session ends.
  ///
  /// See [CameraConfig] for the supported options. Returns a
  /// [CameraResult] describing `confirmed`, `cancelled`, or `error` outcomes.
  /// When [CameraConfig] includes a location target, every photo is checked
  /// against it; photos outside the radius are discarded.
  Future<CameraResult> openCamera(CameraConfig config) {
    return _bridge.openCamera(config);
  }

  /// Deletes all files left by previous capture sessions.
  Future<void> clearSessionImages() {
    return _bridge.clearSessionImages();
  }
}
