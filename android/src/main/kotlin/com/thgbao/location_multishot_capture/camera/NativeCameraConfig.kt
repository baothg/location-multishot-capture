package com.thgbao.location_multishot_capture.camera

import android.content.Intent

data class NativeCameraConfig(
    val maxImages: Int,
    val existingImageCount: Int,
    val targetLatitude: Double?,
    val targetLongitude: Double?,
    val targetRadiusMeters: Double?,
    val initialLatitude: Double?,
    val initialLongitude: Double?,
    val maxMegapixels: Int,
) {
    val remainingImageCount: Int
        get() = maxImages - existingImageCount

    /** True when the caller supplied the full location target. */
    val hasLocationTarget: Boolean
        get() = targetLatitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            targetLongitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
            targetRadiusMeters?.let { it.isFinite() && it > 0.0 } == true

    /** True when only some of the target extras were provided. */
    val hasPartialTarget: Boolean
        get() = !hasLocationTarget &&
            (targetLatitude != null ||
                targetLongitude != null ||
                targetRadiusMeters != null)

    val hasValidInitialLocation: Boolean
        get() = initialLatitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            initialLongitude?.let { it.isFinite() && it in -180.0..180.0 } == true

    val hasPartialInitialLocation: Boolean
        get() = (initialLatitude == null) != (initialLongitude == null)

    companion object {
        fun from(intent: Intent): NativeCameraConfig {
            return NativeCameraConfig(
                maxImages = intent.getIntExtra(EXTRA_MAX_IMAGES, 5),
                existingImageCount = intent.getIntExtra(EXTRA_EXISTING_IMAGE_COUNT, 0),
                targetLatitude = intent.doubleExtraOrNull(EXTRA_TARGET_LATITUDE),
                targetLongitude = intent.doubleExtraOrNull(EXTRA_TARGET_LONGITUDE),
                targetRadiusMeters = intent.doubleExtraOrNull(EXTRA_TARGET_RADIUS_METERS),
                initialLatitude = intent.doubleExtraOrNull(EXTRA_INITIAL_LATITUDE),
                initialLongitude = intent.doubleExtraOrNull(EXTRA_INITIAL_LONGITUDE),
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
        const val EXTRA_INITIAL_LATITUDE = "initialLatitude"
        const val EXTRA_INITIAL_LONGITUDE = "initialLongitude"
        const val EXTRA_MAX_MEGAPIXELS = "maxMegapixels"
    }
}
