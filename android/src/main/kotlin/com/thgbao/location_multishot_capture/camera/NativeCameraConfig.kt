package com.thgbao.location_multishot_capture.camera

import android.content.Intent

data class NativeCameraConfig(
    val maxImages: Int,
    val existingImageCount: Int,
    val targetLatitude: Double?,
    val targetLongitude: Double?,
    val targetRadiusMeters: Double?,
    val maxMegapixels: Int,
) {
    val remainingImageCount: Int
        get() = maxImages - existingImageCount

    /** True when the caller supplied the full location target. */
    val hasLocationTarget: Boolean
        get() = targetLatitude != null &&
            targetLongitude != null &&
            (targetRadiusMeters ?: 0.0) > 0.0

    /** True when only some of the target extras were provided. */
    val hasPartialTarget: Boolean
        get() = !hasLocationTarget &&
            (targetLatitude != null ||
                targetLongitude != null ||
                targetRadiusMeters != null)

    companion object {
        fun from(intent: Intent): NativeCameraConfig {
            return NativeCameraConfig(
                maxImages = intent.getIntExtra(EXTRA_MAX_IMAGES, 5),
                existingImageCount = intent.getIntExtra(EXTRA_EXISTING_IMAGE_COUNT, 0),
                targetLatitude = intent.doubleExtraOrNull(EXTRA_TARGET_LATITUDE),
                targetLongitude = intent.doubleExtraOrNull(EXTRA_TARGET_LONGITUDE),
                targetRadiusMeters = intent.doubleExtraOrNull(EXTRA_TARGET_RADIUS_METERS),
                maxMegapixels = intent.getIntExtra(EXTRA_MAX_MEGAPIXELS, Int.MAX_VALUE),
            )
        }

        private fun Intent.doubleExtraOrNull(key: String): Double? {
            return if (hasExtra(key)) getDoubleExtra(key, 0.0) else null
        }

        const val EXTRA_MAX_IMAGES = "maxImages"
        const val EXTRA_EXISTING_IMAGE_COUNT = "existingImageCount"
        const val EXTRA_TARGET_LATITUDE = "targetLatitude"
        const val EXTRA_TARGET_LONGITUDE = "targetLongitude"
        const val EXTRA_TARGET_RADIUS_METERS = "targetRadiusMeters"
        const val EXTRA_MAX_MEGAPIXELS = "maxMegapixels"
    }
}
