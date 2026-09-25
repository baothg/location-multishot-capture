import 'dart:async';
import 'dart:io';

import 'package:flutter/painting.dart';
import 'package:location_multishot_capture/location_multishot_capture.dart';

class SessionImageStore {
  const SessionImageStore();

  Future<void> deleteImage(CapturedImage image) async {
    final file = File(image.filePath);
    if (await file.exists()) {
      await file.delete();
    }
    unawaited(FileImage(File(image.filePath)).evict());
  }

  Future<void> deleteAll(Iterable<CapturedImage> images) async {
    for (final image in images) {
      await deleteImage(image);
    }
  }
}
