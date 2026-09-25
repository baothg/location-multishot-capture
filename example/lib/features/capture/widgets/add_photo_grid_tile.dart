import 'package:flutter/material.dart';

import 'dashed_border.dart';

class AddPhotoGridTile extends StatelessWidget {
  const AddPhotoGridTile({required this.onTap, super.key});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    const color = Color(0xFF2196F3);

    return Material(
      color: color.withValues(alpha: 0.08),
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: DashedBorder(
          color: color,
          child: const Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.photo_camera_outlined, color: color, size: 40),
                SizedBox(height: 8),
                Text(
                  'Chụp ảnh',
                  style: TextStyle(
                    color: color,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
