import 'package:flutter/material.dart';

import 'features/capture/pages/capture_grid_page.dart';

void main() {
  runApp(const LocationCaptureApp());
}

class LocationCaptureApp extends StatelessWidget {
  const LocationCaptureApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Location Capture',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2196F3)),
        useMaterial3: true,
      ),
      home: const CaptureGridPage(),
    );
  }
}
