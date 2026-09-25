package com.thgbao.location_multishot_capture.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

object JpegNormalizer {
    data class NormalizedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val orientation: Int = 0,
    )

    fun normalize(
        bytes: ByteArray,
        requestedOrientation: Int,
        rawWidth: Int = 0,
        rawHeight: Int = 0,
    ): NormalizedImage {
        // The capture request asks the HAL for an unrotated JPEG, so the only
        // rotation to apply is the one computed from the user's view.
        val orientation = requestedOrientation
        if (orientation == 0) {
            val bounds = decodeBounds(bytes)
            return NormalizedImage(
                bytes = bytes,
                width = bounds?.first ?: 0,
                height = bounds?.second ?: 0,
            )
        }

        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
            val bounds = decodeBounds(bytes)
            return NormalizedImage(
                bytes = bytes,
                width = bounds?.first ?: 0,
                height = bounds?.second ?: 0,
                orientation = orientation,
            )
        }

        if (rawWidth > 0 && rawHeight > 0 &&
            (source.width != rawWidth || source.height != rawHeight)
        ) {
            // The HAL already rotated the pixels itself (decoded dimensions
            // no longer match the raw buffer). Trust it instead of rotating
            // a second time.
            source.recycle()
            val bounds = decodeBounds(bytes)
            return NormalizedImage(
                bytes = bytes,
                width = bounds?.first ?: 0,
                height = bounds?.second ?: 0,
            )
        }

        val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        val output = ByteArrayOutputStream(bytes.size)
        rotated.compress(Bitmap.CompressFormat.JPEG, 100, output)
        val normalizedWidth = rotated.width
        val normalizedHeight = rotated.height
        if (rotated != source) {
            rotated.recycle()
        }
        source.recycle()

        return NormalizedImage(
            bytes = output.toByteArray(),
            width = normalizedWidth,
            height = normalizedHeight,
        )
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }
        return options.outWidth to options.outHeight
    }
}
