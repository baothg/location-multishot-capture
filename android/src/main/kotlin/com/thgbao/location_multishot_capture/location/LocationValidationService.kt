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
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationValidationService(
    context: Context,
    private val targetLatitude: Double,
    private val targetLongitude: Double,
    private val targetRadiusMeters: Double,
    initialLatitude: Double,
    initialLongitude: Double,
    private val callback: (LocationValidationResult) -> Unit,
) {
    data class LocationValidationResult(
        val captureIds: List<String>,
        val location: Location?,
        val locationCapturedAtMillis: Long?,
        val distanceToTargetMeters: Float?,
        val isValid: Boolean,
        val errorMessage: String?,
    )

    private data class CachedLocation(
        val location: Location,
        val recordedAtMillis: Long,
    )

    private val appContext = context.applicationContext
    private val locationManager: LocationManager? =
        appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val target = LocationCoordinates(targetLatitude, targetLongitude)
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(appContext)
    }
    private val initialRecordedAtMillis = System.currentTimeMillis()
    private var primaryLocation = CachedLocation(
        Location("flutter-initial").apply {
            latitude = initialLatitude
            longitude = initialLongitude
            time = initialRecordedAtMillis
        },
        initialRecordedAtMillis,
    )
    private var secondLocation: CachedLocation? = null
    private var periodicLocationCallback: LocationCallback? = null
    private val periodicLocationListeners = mutableListOf<LocationListener>()
    private val pendingCaptureIds = linkedSetOf<String>()
    private val validatingCaptureIds = linkedSetOf<String>()
    private var locationUpdatesStarted = false
    private var lastChanceInProgress = false
    private var lastChanceStartedElapsedRealtimeNanos = 0L
    private var lastChanceCancellationToken: CancellationTokenSource? = null
    private val lastChanceListeners = mutableListOf<LocationListener>()
    private var lastChanceTimeout: Runnable? = null
    private var disposed = false

    fun startPeriodicUpdates() {
        if (disposed || locationUpdatesStarted || !hasLocationPermission()) {
            return
        }
        locationUpdatesStarted = true

        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (locationUpdatesStarted) {
                    result.locations.forEach(::recordLocation)
                }
            }
        }
        periodicLocationCallback = fusedCallback
        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_REFRESH_INTERVAL_MS,
            ).setMinUpdateIntervalMillis(LOCATION_REFRESH_INTERVAL_MS).build()
            fusedLocationClient.requestLocationUpdates(
                request,
                fusedCallback,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }

        val manager = locationManager ?: return
        enabledProviders().forEach { provider ->
            val listener = LocationListener(::recordLocation)
            try {
                manager.requestLocationUpdates(
                    provider,
                    LOCATION_REFRESH_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                periodicLocationListeners.add(listener)
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun stopPeriodicUpdates() {
        periodicLocationCallback?.let { locationCallback ->
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (_: RuntimeException) {
            }
        }
        periodicLocationCallback = null
        periodicLocationListeners.forEach { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }
        periodicLocationListeners.clear()
        locationUpdatesStarted = false
    }

    fun validateCapture(captureId: String) {
        if (disposed || !validatingCaptureIds.add(captureId)) {
            return
        }

        val now = System.currentTimeMillis()
        if (isFresh(primaryLocation, now)) {
            val primaryResult = toResult(listOf(captureId), primaryLocation)
            if (primaryResult.isValid) {
                complete(primaryResult)
                return
            }

            val second = secondLocation
            if (second != null && isFresh(second, now)) {
                val secondResult = toResult(listOf(captureId), second)
                if (secondResult.isValid) {
                    complete(secondResult)
                    return
                }
            }
        }

        pendingCaptureIds.add(captureId)
        if (!lastChanceInProgress) {
            startLastChanceFetch()
        }
    }

    fun cancelAll() {
        if (disposed) {
            return
        }
        disposed = true
        stopPeriodicUpdates()
        finishLastChance(null, deliverResults = false)
        pendingCaptureIds.clear()
        validatingCaptureIds.clear()
    }

    private fun startLastChanceFetch() {
        if (disposed || lastChanceInProgress) {
            return
        }
        lastChanceInProgress = true
        lastChanceStartedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        lastChanceTimeout = Runnable { finishLastChance(null) }.also {
            mainHandler.postDelayed(it, LAST_CHANCE_TIMEOUT_MS)
        }
        if (!hasLocationPermission()) {
            finishLastChance(null)
            return
        }

        val cancellationToken = CancellationTokenSource()
        lastChanceCancellationToken = cancellationToken
        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(LAST_CHANCE_TIMEOUT_MS)
                .setMaxUpdateAgeMillis(0)
                .build()
            fusedLocationClient.getCurrentLocation(request, cancellationToken.token)
                .addOnSuccessListener { location ->
                    if (isUsableLastChanceLocation(location)) {
                        finishLastChance(location)
                    }
                }
        } catch (_: SecurityException) {
            finishLastChance(null)
        } catch (_: RuntimeException) {
        }

        val manager = locationManager ?: return
        enabledProviders().forEach { provider ->
            val listener = LocationListener { location ->
                if (isUsableLastChanceLocation(location)) {
                    finishLastChance(location)
                }
            }
            try {
                manager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                lastChanceListeners.add(listener)
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun finishLastChance(
        location: Location?,
        deliverResults: Boolean = true,
    ) {
        if (!lastChanceInProgress) {
            return
        }
        lastChanceInProgress = false
        lastChanceTimeout?.let(mainHandler::removeCallbacks)
        lastChanceTimeout = null
        lastChanceCancellationToken?.cancel()
        lastChanceCancellationToken = null
        lastChanceListeners.forEach { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }
        lastChanceListeners.clear()

        val captureIds = pendingCaptureIds.toList()
        pendingCaptureIds.clear()
        if (!deliverResults || disposed) {
            return
        }
        val usableLocation = location?.takeIf(::isUsableLastChanceLocation)
        if (usableLocation == null) {
            captureIds.forEach { captureId ->
                complete(
                    LocationValidationResult(
                        captureIds = listOf(captureId),
                        location = null,
                        locationCapturedAtMillis = null,
                        distanceToTargetMeters = null,
                        isValid = false,
                        errorMessage = LOCATION_UNAVAILABLE_MESSAGE,
                    ),
                )
            }
            return
        }

        val newLocation = CachedLocation(Location(usableLocation), System.currentTimeMillis())
        secondLocation = primaryLocation
        primaryLocation = newLocation
        captureIds.forEach { captureId ->
            complete(toResult(listOf(captureId), newLocation))
        }
    }

    private fun recordLocation(location: Location) {
        if (disposed || !isUsableLocation(location)) {
            return
        }
        if (sameFix(primaryLocation.location, location)) {
            return
        }
        secondLocation = primaryLocation
        primaryLocation = CachedLocation(Location(location), System.currentTimeMillis())
    }

    private fun toResult(
        captureIds: List<String>,
        cachedLocation: CachedLocation,
    ): LocationValidationResult {
        val adjustedCoordinates = LocationValidationPolicy.pullTowardTarget(
            target = target,
            location = LocationCoordinates(
                cachedLocation.location.latitude,
                cachedLocation.location.longitude,
            ),
        )
        val adjustedLocation = Location(cachedLocation.location).apply {
            latitude = adjustedCoordinates.latitude
            longitude = adjustedCoordinates.longitude
        }
        val distance = LocationValidationPolicy.distanceMeters(
            target,
            adjustedCoordinates,
        ).toFloat()
        return LocationValidationResult(
            captureIds = captureIds,
            location = adjustedLocation,
            locationCapturedAtMillis = cachedLocation.recordedAtMillis,
            distanceToTargetMeters = distance,
            isValid = distance <= targetRadiusMeters,
            errorMessage = null,
        )
    }

    private fun complete(result: LocationValidationResult) {
        result.captureIds.forEach(validatingCaptureIds::remove)
        mainHandler.post {
            if (!disposed) {
                callback(result)
            }
        }
    }

    private fun isFresh(location: CachedLocation, nowMillis: Long): Boolean {
        return LocationValidationPolicy.isFresh(location.recordedAtMillis, nowMillis)
    }

    private fun isUsableLocation(location: Location?): Boolean {
        if (location == null ||
            !location.latitude.isFinite() ||
            location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() ||
            location.longitude !in -180.0..180.0
        ) {
            return false
        }
        return !location.hasAccuracy() ||
            (location.accuracy.isFinite() && location.accuracy > 0f)
    }

    private fun isUsableLastChanceLocation(location: Location?): Boolean {
        val usableLocation = location?.takeIf(::isUsableLocation) ?: return false
        return usableLocation.elapsedRealtimeNanos >= lastChanceStartedElapsedRealtimeNanos
    }

    private fun sameFix(first: Location, second: Location): Boolean {
        return first.time == second.time &&
            first.latitude == second.latitude &&
            first.longitude == second.longitude
    }

    private fun enabledProviders(): List<String> {
        val manager = locationManager ?: return emptyList()
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter { provider ->
            try {
                manager.isProviderEnabled(provider)
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val LOCATION_REFRESH_INTERVAL_MS = 30_000L
        private const val LAST_CHANCE_TIMEOUT_MS = 15_000L
        private const val LOCATION_UNAVAILABLE_MESSAGE =
            "Không lấy được thông tin vị trí của bạn. Hãy kiểm tra định vị và thử lại"
    }
}
