import 'dart:io';

import 'package:flutter/material.dart';
import 'package:location_multishot_capture/location_multishot_capture.dart';

class CapturedImageTile extends StatelessWidget {
  const CapturedImageTile({
    required this.image,
    required this.onTap,
    required this.onDelete,
    super.key,
  });

  final CapturedImage image;
  final VoidCallback onTap;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Material(
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.antiAlias,
      color: Colors.black12,
      child: Stack(
        fit: StackFit.expand,
        children: [
          InkWell(
            onTap: onTap,
            child: Image.file(
              File(image.filePath),
              fit: BoxFit.cover,
              errorBuilder: (context, error, stackTrace) {
                return const Center(
                  child: Icon(Icons.broken_image_outlined, size: 40),
                );
              },
            ),
          ),
          Positioned(
            top: 8,
            right: 8,
            child: Material(
              color: Colors.red,
              shape: const CircleBorder(),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: onDelete,
                child: const Padding(
                  padding: EdgeInsets.all(7),
                  child: Icon(
                    Icons.delete_outline,
                    color: Colors.white,
                    size: 20,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
