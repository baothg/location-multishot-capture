package com.thgbao.location_multishot_capture.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper

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
        val isValid: Boolean,
        val errorMessage: String?,
    )

    private data class CachedLocationResult(
        val location: Location,
        val timestampMillis: Long,
    )

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pendingCaptureIds = linkedSetOf<String>()
    private val activeListeners = mutableListOf<LocationListener>()
    private var timeoutRunnable: Runnable? = null
    private var validationInProgress = false
    private var cachedLocationResult: CachedLocationResult? = null
    private var warmupCallback: ((Boolean) -> Unit)? = null

    fun isLocationServiceEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun warmupLocation(callback: (Boolean) -> Unit) {
        synchronized(lock) {
            warmupCallback = callback
            if (!validationInProgress) {
                startValidationLocked()
            }
        }
    }

    fun validateCapture(captureId: String) {
        synchronized(lock) {
            pendingCaptureIds.add(captureId)
            if (!validationInProgress) {
                startValidationLocked()
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            pendingCaptureIds.clear()
            validationInProgress = false
            warmupCallback = null
            cleanupLocked()
        }
    }

    private fun startValidationLocked() {
        if (!hasLocationPermission()) {
            completePendingWithFallbackLocked("Thiếu quyền vị trí.")
            return
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter(locationManager::isProviderEnabled)

        if (providers.isEmpty()) {
            completePendingWithFallbackLocked("Dịch vụ vị trí đang tắt.")
            return
        }

        val listener = LocationListener { location ->
            finishValidation { captureIds ->
                cachedLocationResult = CachedLocationResult(
                    location,
                    System.currentTimeMillis(),
                )
                toResult(captureIds, location)
            }
        }
        activeListeners.add(listener)
        validationInProgress = true

        val timeout = Runnable {
            finishValidation { captureIds ->
                fallbackOrInvalid(captureIds, "Không xác định được vị trí.")
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, LOCATION_TIMEOUT_MS)

        var requestedProviderCount = 0
        providers.forEach { provider ->
            try {
                locationManager.requestSingleUpdate(
                    provider,
                    listener,
                    Looper.getMainLooper(),
                )
                requestedProviderCount += 1
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }

        if (requestedProviderCount == 0) {
            finishValidation { captureIds ->
                fallbackOrInvalid(captureIds, "Không xác định được vị trí.")
            }
        }
    }

    private fun finishValidation(
        resultFor: (List<String>) -> LocationValidationResult,
    ) {
        val (result, warmup) = synchronized(lock) {
            if (!validationInProgress) {
                return
            }
            val validationResult = resultFor(pendingCaptureIds.toList())
            pendingCaptureIds.clear()
            validationInProgress = false
            cleanupLocked()
            validationResult to warmupCallback?.also { warmupCallback = null }
        }
        if (result.captureIds.isNotEmpty()) {
            complete(result)
        }
        notifyWarmup(warmup, result)
    }

    private fun completePendingWithFallbackLocked(errorMessage: String) {
        val result = fallbackOrInvalid(
            pendingCaptureIds.toList(),
            errorMessage,
        )
        pendingCaptureIds.clear()
        cleanupLocked()
        val warmup = warmupCallback?.also { warmupCallback = null }
        if (result.captureIds.isNotEmpty()) {
            complete(result)
        }
        notifyWarmup(warmup, result)
    }

    private fun fallbackOrInvalid(
        captureIds: List<String>,
        errorMessage: String,
    ): LocationValidationResult {
        val cached = cachedLocationResult
        if (cached != null &&
            System.currentTimeMillis() - cached.timestampMillis <
            LOCATION_CACHE_MAX_AGE_MS
        ) {
            return toResult(captureIds, cached.location)
        }
        return invalidResult(captureIds, errorMessage)
    }

    private fun notifyWarmup(
        warmup: ((Boolean) -> Unit)?,
        result: LocationValidationResult,
    ) {
        if (warmup == null) {
            return
        }
        val success = result.location != null && result.isValid
        mainHandler.post { warmup(success) }
    }

    private fun cleanupLocked() {
        activeListeners.forEach(locationManager::removeUpdates)
        activeListeners.clear()
        timeoutRunnable?.let(mainHandler::removeCallbacks)
        timeoutRunnable = null
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
        return LocationValidationResult(
            captureIds = captureIds,
            location = location,
            distanceToTargetMeters = distance,
            isValid = distance <= targetRadiusMeters,
            errorMessage = null,
        )
    }

    private fun complete(result: LocationValidationResult) {
        mainHandler.post { callback(result) }
    }

    private fun invalidResult(
        captureIds: List<String>,
        errorMessage: String,
    ): LocationValidationResult {
        return LocationValidationResult(
            captureIds = captureIds,
            location = null,
            distanceToTargetMeters = null,
            isValid = false,
            errorMessage = errorMessage,
        )
    }

    private fun hasLocationPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 15000L
        const val LOCATION_CACHE_MAX_AGE_MS = 5 * 60 * 1000L
    }
}
