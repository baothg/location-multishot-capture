# Integration Guide

## 1. Add the dependency

```yaml
dependencies:
  location_multishot_capture:
    git:
      url: https://github.com/baothg/location-multishot-capture.git
      ref: main
```

## 2. Android requirements

| Requirement | Value |
|---|---|
| Platform | Android only (`openCamera` does nothing useful on other platforms) |
| `minSdk` | 24+ |
| Camera | `Camera2` HAL — all devices with `android.hardware.camera.any` |
| Runtime permissions | `CAMERA` to open the camera; location permission is used for background refresh and last-chance fetch in location mode |

The plugin manifest already declares the permissions, the camera feature
flag, and `NativeCameraActivity` (`fullSensor`, `configChanges` handled).
Manifest merging applies them to the host app automatically — no manual
manifest edits required.

The caller must obtain the user's initial location and pass it as
`initialLatitude`/`initialLongitude`. The camera screen does not request
location permission on entry; permission is used for automatic refresh and the
last-chance fetch. If it is unavailable, cached initial coordinates can still
be used while fresh, but the last-chance fetch will report unavailable.

## 3. Public API

```dart
import 'package:location_multishot_capture/location_multishot_capture.dart';

final plugin = const LocationMultishotCapture();
final CameraResult result = await plugin.openCamera(config);
await plugin.clearSessionImages();
```

### CameraConfig

| Field | Type | Default | Notes |
|---|---|---|---|
| `maxImages` | `int` | `5` | Hard cap for the session |
| `existingImageCount` | `int` | `0` | Photos already held by the caller; the session is limited to `maxImages - existingImageCount` |
| `targetLatitude` | `double?` | `null` | With longitude + radius, enables validation |
| `targetLongitude` | `double?` | `null` | — |
| `targetRadiusMeters` | `double?` | `null` | Must be `> 0` |
| `initialLatitude` | `double?` | `null` | Required with a location target; current user location supplied by the caller |
| `initialLongitude` | `double?` | `null` | Required with a location target; current user location supplied by the caller |
| `maxMegapixels` | `int` | unlimited | Cap on captured JPEG resolution |

**Validation modes**

- **Location mode** — all three target fields and both initial user location
  coordinates are set: the initial coordinates are trusted for five minutes,
  while FLP and Android location updates refresh a primary/secondary cache at a
  30-second target interval while the camera is active. Each capture validates
  against fresh cached locations after pulling them 20% toward the target; a
  direct 15-second fetch is the final fallback. Failed validation ends the
  session.
- **Capture-only mode** — no target fields: only camera permission is
  requested; no location work happens. `latitude`, `longitude` and
  `distanceToTargetMeters` come back as `0`.
- **Partial target** (only some fields set) → the session fails immediately
  with `INVALID_CAMERA_CONFIG`.

### CameraResult

| Field | Type | Notes |
|---|---|---|
| `status` | `CameraResultStatus` | `confirmed`, `cancelled`, `error` |
| `images` | `List<CapturedImage>` | populated on `confirmed` |
| `errorCode` / `errorMessage` | `String?` | populated on `error` |

### CapturedImage

`id`, `filePath`, `width`, `height`, `orientation`, `capturedAt`,
`latitude`, `longitude`, `distanceToTargetMeters`, `locationCapturedAt`.
In location mode, coordinates and distance are based on the 20%-adjusted
location; `locationCapturedAt` records when the location was accepted by the
plugin.

## 4. Behavior guarantees

- **Orientation normalization** — every returned JPEG has `Orientation = 0`
  in its metadata and pixels rotated to match the user's view at capture
  time, in portrait, both landscape directions, and upside-down portrait.
  Phones and tablets share one code path (`fullSensor` window rotation +
  `display.rotation`).
- **Session storage** — images live under app-private files; `confirmed`
  files persist for the caller, cancelled/error sessions are deleted.
- **Session cleanup** — call `clearSessionImages()` when abandoning a flow
  (e.g. app restart mid-session) to remove leftover files.
- **Concurrency** — only one camera session at a time; a second call while
  open returns a `CAMERA_ALREADY_OPEN` platform error.

## 5. Error codes

| Code | Cause |
|---|---|
| `INVALID_CAMERA_CONFIG` | `remainingImageCount <= 0`, partial target, or missing/invalid initial location in location mode |
| `LOCATION_UNAVAILABLE` | Last-chance location fetch returned no usable location |
| `LOCATION_VALIDATION_FAILED` | Last-chance location remained outside the adjusted target radius |
| `CAMERA_ALREADY_OPEN` | a session is already active |
| `NO_ACTIVITY` | plugin not attached to an activity yet |
| `CAMERA_OPEN_FAILED` | activity failed to launch |
| `CAMERA_*` (native) | Camera2 open/capture failures |

## 6. Limitations

- Android only; no iOS/desktop/web implementation.
- Requires a physical device — emulators can capture but GPS validation is
  usually impractical.
- UI copy inside the native camera screen is currently Vietnamese.
- `NativeCameraActivity` uses `fullSensor`; if the host app locks
  orientation the camera screen still rotates.

## 7. Minimal example

```dart
Future<void> capture(BuildContext context) async {
  final result = await const LocationMultishotCapture().openCamera(
    const CameraConfig(maxImages: 5),
  );
  switch (result.status) {
    case CameraResultStatus.confirmed:
      // use result.images
    case CameraResultStatus.cancelled:
      break;
    case CameraResultStatus.error:
      // result.errorCode / result.errorMessage
  }
}
```

The `example/` directory contains a full Provider-based grid app.

## 8. Method-channel contract (advanced)

Channel: `location_multishot_capture/native_camera`

| Method | Args | Result |
|---|---|---|
| `openCamera` | `maxImages:int`, `existingImageCount:int`, `maxMegapixels:int`, optional `targetLatitude:double`, `targetLongitude:double`, `targetRadiusMeters:double` | `{status, images?[], errorCode?, errorMessage?}` |
| `clearSessionImages` | — | `null` |

`images[]` items: `id`, `filePath`, `width`, `height`, `orientation`,
`timestamp` (epoch ms), `latitude`, `longitude`, `distanceToTargetMeters`.
