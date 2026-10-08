package com.thgbao.location_multishot_capture.location

internal data class LocationCoordinates(
    val latitude: Double,
    val longitude: Double,
)

internal object LocationValidationPolicy {
    const val LOCATION_MAX_AGE_MS = 5 * 60 * 1000L
    const val PULL_TOWARD_TARGET_FACTOR = 0.8

    fun isFresh(recordedAtMillis: Long, nowMillis: Long): Boolean {
        val ageMillis = nowMillis - recordedAtMillis
        return ageMillis in 0..LOCATION_MAX_AGE_MS
    }

    fun pullTowardTarget(
        target: LocationCoordinates,
        location: LocationCoordinates,
    ): LocationCoordinates {
        val distance = distanceMeters(target, location)
        if (distance == 0.0) {
            return target
        }

        val targetLatitude = Math.toRadians(target.latitude)
        val targetLongitude = Math.toRadians(target.longitude)
        val locationLatitude = Math.toRadians(location.latitude)
        val locationLongitude = Math.toRadians(location.longitude)
        val longitudeDelta = locationLongitude - targetLongitude
        val bearing = kotlin.math.atan2(
            kotlin.math.sin(longitudeDelta) * kotlin.math.cos(locationLatitude),
            kotlin.math.cos(targetLatitude) * kotlin.math.sin(locationLatitude) -
                kotlin.math.sin(targetLatitude) * kotlin.math.cos(locationLatitude) *
                kotlin.math.cos(longitudeDelta),
        )
        val angularDistance = distance * PULL_TOWARD_TARGET_FACTOR / EARTH_RADIUS_METERS
        val adjustedLatitude = kotlin.math.asin(
            kotlin.math.sin(targetLatitude) * kotlin.math.cos(angularDistance) +
                kotlin.math.cos(targetLatitude) * kotlin.math.sin(angularDistance) *
                kotlin.math.cos(bearing),
        )
        val adjustedLongitude = targetLongitude + kotlin.math.atan2(
            kotlin.math.sin(bearing) * kotlin.math.sin(angularDistance) *
                kotlin.math.cos(targetLatitude),
            kotlin.math.cos(angularDistance) -
                kotlin.math.sin(targetLatitude) * kotlin.math.sin(adjustedLatitude),
        )

        return LocationCoordinates(
            latitude = Math.toDegrees(adjustedLatitude),
            longitude = normalizeLongitude(Math.toDegrees(adjustedLongitude)),
        )
    }

    fun distanceMeters(
        first: LocationCoordinates,
        second: LocationCoordinates,
    ): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val haversine = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
            kotlin.math.cos(firstLatitude) * kotlin.math.cos(secondLatitude) *
            kotlin.math.sin(longitudeDelta / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * kotlin.math.asin(
            kotlin.math.sqrt(haversine.coerceIn(0.0, 1.0)),
        )
    }

    private fun normalizeLongitude(longitude: Double): Double {
        return (longitude + 540.0) % 360.0 - 180.0
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
