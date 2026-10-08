class CapturedImage {
  const CapturedImage({
    required this.id,
    required this.filePath,
    required this.width,
    required this.height,
    required this.orientation,
    required this.capturedAt,
    required this.latitude,
    required this.longitude,
    required this.distanceToTargetMeters,
    this.locationCapturedAt,
  });

  final String id;
  final String filePath;
  final int width;
  final int height;
  final int orientation;
  final DateTime capturedAt;
  final double latitude;
  final double longitude;
  final double distanceToTargetMeters;
  final DateTime? locationCapturedAt;

  factory CapturedImage.fromMap(Map<dynamic, dynamic> map) {
    final locationTimestamp = map['locationTimestamp'];
    return CapturedImage(
      id: map['id'] as String? ?? '',
      filePath: map['filePath'] as String? ?? '',
      width: (map['width'] as num?)?.toInt() ?? 0,
      height: (map['height'] as num?)?.toInt() ?? 0,
      orientation: (map['orientation'] as num?)?.toInt() ?? 0,
      capturedAt: DateTime.fromMillisecondsSinceEpoch(
        (map['timestamp'] as num?)?.toInt() ?? 0,
      ),
      latitude: (map['latitude'] as num?)?.toDouble() ?? 0,
      longitude: (map['longitude'] as num?)?.toDouble() ?? 0,
      distanceToTargetMeters:
          (map['distanceToTargetMeters'] as num?)?.toDouble() ?? 0,
      locationCapturedAt: locationTimestamp is num
          ? DateTime.fromMillisecondsSinceEpoch(locationTimestamp.toInt())
          : null,
    );
  }
}
