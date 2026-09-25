import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:location_multishot_capture/location_multishot_capture.dart';
import 'package:location_multishot_capture_example/features/capture/pages/capture_grid_page.dart';
import 'package:location_multishot_capture_example/features/capture/pages/image_viewer_page.dart';
import 'package:location_multishot_capture_example/features/capture/services/session_image_store.dart';
import 'package:location_multishot_capture_example/features/capture/widgets/add_photo_grid_tile.dart';
import 'package:location_multishot_capture_example/features/capture/widgets/captured_image_tile.dart';
import 'package:location_multishot_capture_example/main.dart';

class FakeNativeCameraBridge implements NativeCameraBridge {
  CameraResult result = const CameraResult(
    status: CameraResultStatus.cancelled,
  );
  int openCameraCalls = 0;
  int clearSessionCalls = 0;

  @override
  Future<void> clearSessionImages() async {
    clearSessionCalls += 1;
  }

  @override
  Future<CameraResult> openCamera(CameraConfig config) async {
    openCameraCalls += 1;
    return result;
  }
}

void main() {
  test('CameraConfig calculates remaining grid capacity', () {
    const config = CameraConfig(maxImages: 5, existingImageCount: 3);
    expect(config.remainingImageCount, 2);
  });

  test('CameraResult parses confirmed native images', () {
    final result = CameraResult.fromMap({
      'status': 'confirmed',
      'images': [
        {
          'id': 'image-1',
          'filePath': '/tmp/image-1.jpg',
          'width': 3000,
          'height': 4000,
          'orientation': 0,
          'timestamp': 123456789,
          'latitude': 10.1,
          'longitude': 106.2,
          'distanceToTargetMeters': 15.5,
        },
      ],
    });

    expect(result.status, CameraResultStatus.confirmed);
    expect(result.images, hasLength(1));
    expect(result.images.single.id, 'image-1');
    expect(result.images.single.distanceToTargetMeters, 15.5);
  });

  test('SessionImageStore deletes the backing file', () async {
    final file = _createTestImage();
    await const SessionImageStore().deleteImage(
      CapturedImage(
        id: 'image-1',
        filePath: file.path,
        width: 1,
        height: 1,
        orientation: 0,
        capturedAt: DateTime.fromMillisecondsSinceEpoch(0),
        latitude: 10.1,
        longitude: 106.2,
        distanceToTargetMeters: 10,
      ),
    );

    expect(file.existsSync(), isFalse);
  });

  testWidgets('App renders capture grid entry point', (tester) async {
    await tester.pumpWidget(const LocationCaptureApp());
    await tester.pumpAndSettle();

    expect(find.text('Ảnh đã chụp'), findsOneWidget);
    expect(find.byType(AddPhotoGridTile), findsOneWidget);
    expect(find.text('Chụp ảnh'), findsOneWidget);
  });

  testWidgets('Grid uses two columns on phone and four on tablet', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 360);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      const MaterialApp(home: CaptureGridPage(autoOpenCamera: false)),
    );
    await tester.pumpAndSettle();
    var gridView = tester.widget<GridView>(find.byType(GridView));
    var delegate =
        gridView.gridDelegate as SliverGridDelegateWithFixedCrossAxisCount;
    expect(delegate.crossAxisCount, 2);

    tester.view.physicalSize = const Size(1400, 900);
    await tester.pumpAndSettle();
    gridView = tester.widget<GridView>(find.byType(GridView));
    delegate =
        gridView.gridDelegate as SliverGridDelegateWithFixedCrossAxisCount;
    expect(delegate.crossAxisCount, 4);
  });

  testWidgets('Grid auto-opens native camera once when empty', (tester) async {
    final bridge = FakeNativeCameraBridge();
    await tester.pumpWidget(
      MaterialApp(home: CaptureGridPage(cameraBridge: bridge)),
    );
    await tester.pumpAndSettle();

    expect(bridge.clearSessionCalls, 1);
    expect(bridge.openCameraCalls, 1);
  });

  testWidgets('Confirmed image opens viewer and can be deleted', (
    tester,
  ) async {
    final bridge = FakeNativeCameraBridge();
    final imageFile = _createTestImage();
    bridge.result = CameraResult(
      status: CameraResultStatus.confirmed,
      images: [
        CapturedImage(
          id: 'image-1',
          filePath: imageFile.path,
          width: 1,
          height: 1,
          orientation: 0,
          capturedAt: DateTime.fromMillisecondsSinceEpoch(0),
          latitude: 10.1,
          longitude: 106.2,
          distanceToTargetMeters: 10,
        ),
      ],
    );

    await tester.pumpWidget(
      MaterialApp(
        home: CaptureGridPage(cameraBridge: bridge, autoOpenCamera: false),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(AddPhotoGridTile));
    await tester.pumpAndSettle();

    expect(find.byType(CapturedImageTile), findsOneWidget);

    await tester.tap(find.byType(CapturedImageTile));
    await tester.pumpAndSettle();
    expect(find.byType(ImageViewerPage), findsOneWidget);
    expect(find.text('1 / 1'), findsOneWidget);
    await tester.tap(find.byIcon(Icons.arrow_back));
    await tester.pumpAndSettle();

    final tile = find.byType(CapturedImageTile);
    await tester.tap(
      find.descendant(of: tile, matching: find.byIcon(Icons.delete_outline)),
    );
    await tester.pumpAndSettle();
    expect(find.text('Xóa ảnh'), findsOneWidget);
    await tester.tap(find.text('Xóa'));
    await tester.pumpAndSettle();

    expect(find.byType(CapturedImageTile), findsNothing);
  });
}

File _createTestImage() {
  const pngBase64 =
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  final file = File(
    '${Directory.systemTemp.path}/location_capture_test_${DateTime.now().microsecondsSinceEpoch}.png',
  );
  file.writeAsBytesSync(base64Decode(pngBase64));
  return file;
}
