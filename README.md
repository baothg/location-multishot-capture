# location_multishot_capture

Flutter plugin that opens a native Android Camera2 screen for capturing
multiple photos in one session, optionally validating every photo against a
target GPS location. All returned JPEGs are pixel-normalized to match exactly
what the user saw on the preview, on both phones and tablets.

- **Platform:** Android only (`minSdk 24`, Camera2).
- **Entry point:** `LocationMultishotCapture.openCamera(config)`.
- **Session storage:** images are written to app-private storage and owned by
  the caller after `confirmed`.

## Install

```yaml
dependencies:
  location_multishot_capture:
    git:
      url: https://github.com/baothg/location-multishot-capture.git
```

## Quick start

```dart
import 'package:location_multishot_capture/location_multishot_capture.dart';

final result = await const LocationMultishotCapture().openCamera(
  const CameraConfig(
    maxImages: 5,
    // Optional: enable location validation.
    targetLatitude: 10.0,
    targetLongitude: 106.0,
    targetRadiusMeters: 50000,
  ),
);

if (result.isConfirmed) {
  for (final image in result.images) {
    // image.filePath, image.width, image.height, ...
  }
}
```

See `INTEGRATION.md` for the full contract and `example/` for a complete app.
