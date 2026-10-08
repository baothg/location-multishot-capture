package com.thgbao.location_multishot_capture.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationValidationPolicyTest {
    @Test
    fun pullsThePointTwentyPercentTowardTheTarget() {
        val target = LocationCoordinates(10.0, 106.0)
        val original = LocationCoordinates(10.0, 106.001)
        val adjusted = LocationValidationPolicy.pullTowardTarget(target, original)
        val originalDistance = LocationValidationPolicy.distanceMeters(target, original)
        val adjustedDistance = LocationValidationPolicy.distanceMeters(target, adjusted)

        assertEquals(originalDistance * 0.8, adjustedDistance, 0.01)
    }

    @Test
    fun keepsTheTargetPointUnchanged() {
        val target = LocationCoordinates(10.0, 106.0)

        assertEquals(
            target,
            LocationValidationPolicy.pullTowardTarget(target, target),
        )
    }

    @Test
    fun normalizesLongitudeAcrossTheDateLine() {
        val target = LocationCoordinates(0.0, 179.999)
        val original = LocationCoordinates(0.0, -179.999)
        val adjusted = LocationValidationPolicy.pullTowardTarget(target, original)

        assertTrue(adjusted.longitude in -180.0..180.0)
        assertEquals(
            LocationValidationPolicy.distanceMeters(target, original) * 0.8,
            LocationValidationPolicy.distanceMeters(target, adjusted),
            0.01,
        )
    }

    @Test
    fun acceptsLocationRecordedExactlyFiveMinutesAgo() {
        val nowMillis = 1_000_000L

        assertTrue(
            LocationValidationPolicy.isFresh(
                recordedAtMillis = nowMillis - LocationValidationPolicy.LOCATION_MAX_AGE_MS,
                nowMillis = nowMillis,
            ),
        )
        assertFalse(
            LocationValidationPolicy.isFresh(
                recordedAtMillis = nowMillis - LocationValidationPolicy.LOCATION_MAX_AGE_MS - 1,
                nowMillis = nowMillis,
            ),
        )
        assertFalse(
            LocationValidationPolicy.isFresh(
                recordedAtMillis = nowMillis + 1,
                nowMillis = nowMillis,
            ),
        )
    }
}
