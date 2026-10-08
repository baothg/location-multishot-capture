import 'package:flutter_test/flutter_test.dart';
import 'package:location_multishot_capture/location_multishot_capture.dart';

void main() {
  test('location config forwards required initial coordinates', () {
    const config = CameraConfig(
      targetLatitude: 10,
      targetLongitude: 106,
      targetRadiusMeters: 100,
      initialLatitude: 10.1,
      initialLongitude: 106.1,
    );

    expect(config.toMap()['initialLatitude'], 10.1);
    expect(config.toMap()['initialLongitude'], 106.1);
  });

  test('captured image safely parses missing location metadata', () {
    final image = CapturedImage.fromMap({
      'latitude': null,
      'longitude': null,
      'distanceToTargetMeters': null,
      'locationTimestamp': null,
    });

    expect(image.latitude, 0);
    expect(image.longitude, 0);
    expect(image.distanceToTargetMeters, 0);
    expect(image.locationCapturedAt, isNull);
  });

  test('captured image parses the recorded location timestamp', () {
    final image = CapturedImage.fromMap({'locationTimestamp': 123456700});

    expect(
      image.locationCapturedAt,
      DateTime.fromMillisecondsSinceEpoch(123456700),
    );
  });
}
