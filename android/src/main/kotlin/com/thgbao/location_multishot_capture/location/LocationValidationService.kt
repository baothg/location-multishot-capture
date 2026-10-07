package com.thgbao.location_multishot_capture.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class LocationValidationService(
    context: Context,
    private val targetLatitude: Double,
    private val targetLongitude: Double,
    private val targetRadiusMeters: Double,
    private val callback: (LocationValidationResult) -> Unit,
) {
    data class LocationValidationResult(
        val captureIds: List<String>,
        val location: Location?,
        val distanceToTargetMeters: Float?,
        val locationAccuracyMeters: Float?,
        val isValid: Boolean,
        val errorMessage: String?,
    )

    private data class Acquisition(
        val captureId: String?,
        val startedElapsedMs: Long,
        val startedElapsedRealtimeNanos: Long,
        var bestLocation: Location? = null,
        var timeoutRunnable: Runnable? = null,
    )

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val captureAcquisitions = linkedMapOf<String, Acquisition>()
    private val activeListeners = mutableListOf<LocationListener>()
    private var locationUpdatesRegistered = false
    private var warmupAcquisition: Acquisition? = null
    private var warmupCallback: ((LocationValidationResult) -> Unit)? = null
    private var cachedValidLocation: Location? = null

    fun isLocationServiceEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun warmupLocation(callback: (LocationValidationResult) -> Unit) {
        synchronized(lock) {
            warmupCallback = callback
            if (warmupAcquisition == null) {
                startAcquisitionLocked(captureId = null)
            }
        }
    }

    fun validateCapture(captureId: String) {
        synchronized(lock) {
            if (captureAcquisitions[captureId] != null) {
                return
            }
            val cachedLocation = getCachedValidLocationLocked()
            if (cachedLocation != null) {
                complete(toResult(listOf(captureId), cachedLocation))
            } else {
                startAcquisitionLocked(captureId)
            }
        }
    }

    fun retryCaptures(captureIds: Collection<String>) {
        synchronized(lock) {
            cachedValidLocation = null
            captureIds.forEach { captureId ->
                cancelAcquisitionLocked(captureAcquisitions.remove(captureId))
                startAcquisitionLocked(captureId)
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            captureAcquisitions.values.forEach(::removeTimeoutLocked)
            captureAcquisitions.clear()
            warmupAcquisition?.let(::removeTimeoutLocked)
            warmupAcquisition = null
            warmupCallback = null
            cachedValidLocation = null
            cleanupLocationUpdatesLocked()
        }
    }

    private fun startAcquisitionLocked(captureId: String?) {
        val acquisition = Acquisition(
            captureId = captureId,
            startedElapsedMs = SystemClock.elapsedRealtime(),
            startedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        )
        if (captureId == null) {
            warmupAcquisition = acquisition
        } else {
            captureAcquisitions[captureId] = acquisition
        }

        if (!hasLocationPermission()) {
            finishAcquisitionLocked(
                acquisition,
                invalidResult(
                    acquisition,
                    "Ứng dụng chưa được cấp quyền truy cập vị trí chính xác (ACCESS_FINE_LOCATION).",
                ),
            )
            return
        }

        if (!ensureLocationUpdatesLocked()) {
            finishAcquisitionLocked(
                acquisition,
                invalidResult(
                    acquisition,
                    "Không thể đăng ký lấy tọa độ từ GPS/mạng: hệ thống từ chối yêu cầu.",
                ),
            )
            return
        }

        acquisition.timeoutRunnable = Runnable {
            synchronized(lock) {
                if (!isActiveLocked(acquisition)) {
                    return@synchronized
                }
                val result = currentBestLocation(acquisition)?.let {
                    toResult(acquisition.captureId?.let(::listOf) ?: emptyList(), it)
                } ?: invalidResult(acquisition, noLocationMessage())
                finishAcquisitionLocked(acquisition, result)
            }
        }.also { mainHandler.postDelayed(it, LOCATION_TIMEOUT_MS) }
    }

    private fun ensureLocationUpdatesLocked(): Boolean {
        if (locationUpdatesRegistered) {
            return true
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter(locationManager::isProviderEnabled)
        if (providers.isEmpty()) {
            return false
        }

        val listener = LocationListener(::onLocationChanged)
        var requestedProviderCount = 0
        providers.forEach { provider ->
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                requestedProviderCount += 1
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (requestedProviderCount == 0) {
            return false
        }
        activeListeners.add(listener)
        locationUpdatesRegistered = true
        return true
    }

    private fun onLocationChanged(location: Location) {
        synchronized(lock) {
            val fixAgeNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (!location.hasAccuracy() ||
                !location.accuracy.isFinite() ||
                location.accuracy <= 0f ||
                fixAgeNanos !in 0..MAX_FIX_AGE_NANOS
            ) {
                return
            }

            val acquisitions = captureAcquisitions.values.toList() + listOfNotNull(
                warmupAcquisition,
            )
            acquisitions.forEach { acquisition ->
                if (location.elapsedRealtimeNanos < acquisition.startedElapsedRealtimeNanos) {
                    return@forEach
                }
                if (isBetterLocation(location, acquisition.bestLocation)) {
                    acquisition.bestLocation = Location(location)
                }
                val shouldFinish = SystemClock.elapsedRealtime() - acquisition.startedElapsedMs >=
                    MIN_ACQUISITION_TIME_MS &&
                    (currentBestLocation(acquisition)?.accuracy ?: Float.MAX_VALUE) <=
                    EARLY_FINISH_ACCURACY_METERS
                if (shouldFinish) {
                    val result = currentBestLocation(acquisition)?.let {
                        toResult(acquisition.captureId?.let(::listOf) ?: emptyList(), it)
                    } ?: invalidResult(acquisition, noLocationMessage())
                    finishAcquisitionLocked(acquisition, result)
                }
            }
        }
    }

    private fun isBetterLocation(candidate: Location, current: Location?): Boolean {
        if (current == null) {
            return true
        }
        val currentAgeNanos = SystemClock.elapsedRealtimeNanos() - current.elapsedRealtimeNanos
        if (currentAgeNanos > MAX_FIX_AGE_NANOS) {
            return true
        }
        val accuracyDelta = candidate.accuracy - current.accuracy
        return accuracyDelta < -LOCATION_ACCURACY_IMPROVEMENT_METERS ||
            (accuracyDelta <= 0f && candidate.time > current.time)
    }

    private fun currentBestLocation(acquisition: Acquisition): Location? {
        val location = acquisition.bestLocation ?: return null
        val fixAgeNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return location.takeIf {
            it.elapsedRealtimeNanos >= acquisition.startedElapsedRealtimeNanos &&
                fixAgeNanos in 0..MAX_FIX_AGE_NANOS
        }
    }

    private fun finishAcquisitionLocked(
        acquisition: Acquisition,
        result: LocationValidationResult,
    ) {
        if (!isActiveLocked(acquisition)) {
            return
        }
        removeTimeoutLocked(acquisition)
        cachedValidLocation = if (result.isValid && result.location != null) {
            Location(result.location)
        } else {
            null
        }
        if (acquisition.captureId == null) {
            warmupAcquisition = null
            val warmup = warmupCallback?.also { warmupCallback = null }
            if (warmup != null) {
                mainHandler.post { warmup(result) }
            }
        } else {
            captureAcquisitions.remove(acquisition.captureId)
            complete(result)
        }
        if (captureAcquisitions.isEmpty() && warmupAcquisition == null) {
            cleanupLocationUpdatesLocked()
        }
    }

    private fun cancelAcquisitionLocked(acquisition: Acquisition?) {
        if (acquisition == null) {
            return
        }
        removeTimeoutLocked(acquisition)
        if (captureAcquisitions.isEmpty() && warmupAcquisition == null) {
            cleanupLocationUpdatesLocked()
        }
    }

    private fun isActiveLocked(acquisition: Acquisition): Boolean {
        return if (acquisition.captureId == null) {
            warmupAcquisition === acquisition
        } else {
            captureAcquisitions[acquisition.captureId] === acquisition
        }
    }

    private fun removeTimeoutLocked(acquisition: Acquisition) {
        acquisition.timeoutRunnable?.let(mainHandler::removeCallbacks)
        acquisition.timeoutRunnable = null
    }

    private fun cleanupLocationUpdatesLocked() {
        activeListeners.forEach(locationManager::removeUpdates)
        activeListeners.clear()
        locationUpdatesRegistered = false
    }

    private fun getCachedValidLocationLocked(): Location? {
        val location = cachedValidLocation ?: return null
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (ageNanos !in 0..LOCATION_CACHE_MAX_AGE_NANOS) {
            cachedValidLocation = null
            return null
        }
        return Location(location)
    }

    private fun toResult(
        captureIds: List<String>,
        location: Location,
    ): LocationValidationResult {
        val target = Location("target").apply {
            latitude = targetLatitude
            longitude = targetLongitude
        }
        val distance = location.distanceTo(target)
        val isConfidentlyWithinTarget = distance + location.accuracy <= targetRadiusMeters
        val isAmbiguous = distance - location.accuracy <= targetRadiusMeters &&
            !isConfidentlyWithinTarget
        return LocationValidationResult(
            captureIds = captureIds,
            location = location,
            distanceToTargetMeters = distance,
            locationAccuracyMeters = location.accuracy,
            isValid = isConfidentlyWithinTarget,
            errorMessage = if (isAmbiguous) {
                "Độ chính xác vị trí chưa đủ để xác nhận bạn đang trong phạm vi cho phép."
            } else {
                null
            },
        )
    }

    private fun invalidResult(
        acquisition: Acquisition,
        errorMessage: String,
    ): LocationValidationResult {
        return LocationValidationResult(
            captureIds = acquisition.captureId?.let(::listOf) ?: emptyList(),
            location = null,
            distanceToTargetMeters = null,
            locationAccuracyMeters = null,
            isValid = false,
            errorMessage = errorMessage,
        )
    }

    private fun noLocationMessage(): String {
        return "Không lấy được tọa độ vị trí mới: GPS/mạng không trả vị trí trong " +
            "${LOCATION_TIMEOUT_MS / 1000} giây. Hãy thử lấy lại vị trí."
    }

    private fun complete(result: LocationValidationResult) {
        mainHandler.post { callback(result) }
    }

    private fun hasLocationPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 15000L
        const val LOCATION_UPDATE_INTERVAL_MS = 1000L
        const val MIN_ACQUISITION_TIME_MS = 4000L
        const val EARLY_FINISH_ACCURACY_METERS = 15f
        const val LOCATION_ACCURACY_IMPROVEMENT_METERS = 3f
        const val MAX_FIX_AGE_NANOS = 5_000_000_000L
        const val LOCATION_CACHE_MAX_AGE_NANOS = 10_000_000_000L
    }
}
