import 'captured_image.dart';

enum CameraResultStatus { confirmed, cancelled, error }

class CameraResult {
  const CameraResult({
    required this.status,
    this.images = const [],
    this.errorCode,
    this.errorMessage,
  });

  final CameraResultStatus status;
  final List<CapturedImage> images;
  final String? errorCode;
  final String? errorMessage;

  bool get isConfirmed => status == CameraResultStatus.confirmed;

  factory CameraResult.fromMap(Map<dynamic, dynamic> map) {
    final statusName = map['status'] as String? ?? 'error';
    final rawImages = map['images'] as List<dynamic>? ?? const [];

    return CameraResult(
      status: CameraResultStatus.values.firstWhere(
        (status) => status.name == statusName,
        orElse: () => CameraResultStatus.error,
      ),
      images: rawImages
          .whereType<Map<dynamic, dynamic>>()
          .map(CapturedImage.fromMap)
          .toList(growable: false),
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
    );
  }
}
