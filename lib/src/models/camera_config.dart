/// Configuration for a single native camera session.
///
/// When [targetLatitude], [targetLongitude] and [targetRadiusMeters] are all
/// provided, every captured photo is validated against that target location:
/// photos taken farther than [targetRadiusMeters] are discarded. If any of
/// them is omitted, the session runs in capture-only mode and no location
/// permission or validation is required.
class CameraConfig {
  const CameraConfig({
    this.maxImages = 5,
    this.existingImageCount = 0,
    this.targetLatitude,
    this.targetLongitude,
    this.targetRadiusMeters,
    this.maxMegapixels = 0x7fffffff,
  });

  /// Total number of photos the session may hold.
  final int maxImages;

  /// Photos that already exist outside this session.
  final int existingImageCount;

  /// Latitude of the location photos must be taken near, or null to disable
  /// location validation.
  final double? targetLatitude;

  /// Longitude of the location photos must be taken near, or null to disable
  /// location validation.
  final double? targetLongitude;

  /// Allowed radius around the target, in meters.
  final double? targetRadiusMeters;

  /// Optional cap for captured JPEG resolution.
  final int maxMegapixels;

  int get remainingImageCount => maxImages - existingImageCount;

  Map<String, Object> toMap() {
    return {
      'maxImages': maxImages,
      'existingImageCount': existingImageCount,
      'maxMegapixels': maxMegapixels,
      'targetLatitude': ?targetLatitude,
      'targetLongitude': ?targetLongitude,
      'targetRadiusMeters': ?targetRadiusMeters,
    };
  }
}
