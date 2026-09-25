package com.thgbao.location_multishot_capture.camera

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.thgbao.location_multishot_capture.R
import com.thgbao.location_multishot_capture.location.LocationValidationService
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

class NativeCameraActivity : Activity(), Camera2Controller.Callback {
    private enum class CaptureStatus {
        PENDING_LOCATION,
        VALID,
    }

    private data class CaptureRecord(
        val id: String,
        val file: File,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val timestamp: Long,
        val status: CaptureStatus,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val distanceToTargetMeters: Float? = null,
    )

    private lateinit var config: NativeCameraConfig
    private lateinit var repository: ImageFileRepository
    private lateinit var locationService: LocationValidationService
    private lateinit var textureView: TextureView
    private lateinit var rootLayout: FrameLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var backButton: ImageButton
    private lateinit var shutterButton: ImageButton
    private lateinit var shutterContainer: FrameLayout
    private lateinit var shutterProgress: ProgressBar
    private lateinit var confirmButton: ImageButton
    private lateinit var confirmContainer: FrameLayout
    private lateinit var countBadge: TextView
    private lateinit var captureProgress: ProgressBar
    private lateinit var loadingOverlay: FrameLayout
    private var cameraController: Camera2Controller? = null
    private var isLocationWarmupInProgress = false
    private var inFlightCaptures = 0
    private var lastShutterTapElapsedMs = 0L

    private val captures = linkedMapOf<String, CaptureRecord>()
    private var resultDelivered = false
    private var permissionDialogShowing = false
    private var waitingForPermissionResult = false
    private var systemInsetLeft = 0
    private var systemInsetTop = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0
    private var lastControlState = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        config = NativeCameraConfig.from(intent)
        if (config.remainingImageCount <= 0 || config.hasPartialTarget) {
            finishWithError(
                "INVALID_CAMERA_CONFIG",
                "Cấu hình camera không hợp lệ.",
            )
            return
        }

        repository = ImageFileRepository(this)
        repository.prepareCaptureSession()
        if (config.hasLocationTarget) {
            locationService = LocationValidationService(
                context = this,
                targetLatitude = config.targetLatitude!!,
                targetLongitude = config.targetLongitude!!,
                targetRadiusMeters = config.targetRadiusMeters!!,
                callback = ::onLocationValidationResult,
            )
        }

        buildUi()
        hideNavigationBar()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBackRequest() }
        }
        if (!hasRequiredPermissions()) {
            waitingForPermissionResult = true
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
        } else if (!isLocationServiceReady()) {
            showRequiredPermissionDialog("Vui lòng bật dịch vụ vị trí để chụp ảnh.")
        } else {
            startLocationWarmup()
        }
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
        if (!::config.isInitialized || waitingForPermissionResult) {
            return
        }
        if (!hasRequiredPermissions()) {
            showRequiredPermissionDialog(
                "Ứng dụng cần quyền camera và vị trí để chụp ảnh.",
            )
            return
        }
        if (!isLocationServiceReady()) {
            showRequiredPermissionDialog("Vui lòng bật dịch vụ vị trí để chụp ảnh.")
            return
        }
        cameraController?.start()
    }

    private fun isLocationServiceReady(): Boolean {
        return !config.hasLocationTarget ||
            (::locationService.isInitialized &&
                locationService.isLocationServiceEnabled())
    }

    override fun onPause() {
        cameraController?.stop()
        inFlightCaptures = 0
        if (::countBadge.isInitialized) {
            updateCaptureUi()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::locationService.isInitialized) {
            locationService.cancelAll()
        }
        cameraController?.stop()
        if (!resultDelivered && ::repository.isInitialized) {
            repository.discardAll(captures.values.map { it.file })
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideNavigationBar()
        }
    }

    private fun hideNavigationBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decorView = window.decorView
            window.setDecorFitsSystemWindows(false)
            decorView.windowInsetsController?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rootLayout.post { applyOrientation() }
    }

    private fun applyOrientation() {
        updateWindowRotation()
        // Buttons live on rootLayout (not the counter-rotated preview
        // container), so the OS keeps them at user-view positions on every
        // device — only inset margins and the landscape shutter position need
        // updating when rotation or insets change.
        val rotation = deviceRotation()
        val controlState = "$rotation|" +
            "$systemInsetLeft,$systemInsetTop,$systemInsetRight,$systemInsetBottom"
        if (controlState != lastControlState) {
            lastControlState = controlState
            updateControlMargins(rotation)
        }
        cameraController?.configureTransform(
            contentContainer.width,
            contentContainer.height,
        )
    }

    private fun updateControlMargins(rotation: Int) {
        val margin = dp(20)
        // In landscape the shutter hugs the user's right edge so it stays
        // under the thumb; portrait keeps it bottom-center.
        val landscape = rotation == Surface.ROTATION_90 ||
            rotation == Surface.ROTATION_270
        val shutterGravity = if (landscape) {
            Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        fun params(size: Int, gravity: Int) =
            FrameLayout.LayoutParams(size, size).apply {
                this.gravity = gravity
                setMargins(
                    margin + systemInsetLeft,
                    margin + systemInsetTop,
                    margin + systemInsetRight,
                    margin + systemInsetBottom,
                )
            }
        backButton.layoutParams = params(
            dp(48),
            Gravity.TOP or Gravity.START,
        )
        shutterContainer.layoutParams = params(dp(76), shutterGravity)
        confirmContainer.layoutParams = params(
            dp(76),
            Gravity.BOTTOM or Gravity.END,
        )
    }

    private fun deviceRotation(): Int {
        return rootLayout.display?.rotation ?: Surface.ROTATION_0
    }

    private fun updateWindowRotation() {
        val rotation = deviceRotation()
        val rotated90 = rotation == Surface.ROTATION_90 ||
            rotation == Surface.ROTATION_270
        val width = if (rotated90) rootLayout.height else rootLayout.width
        val height = if (rotated90) rootLayout.width else rootLayout.height
        if (width > 0 && height > 0) {
            val params = contentContainer.layoutParams as? FrameLayout.LayoutParams
            if (params != null &&
                (params.width != width || params.height != height)
            ) {
                contentContainer.layoutParams = FrameLayout.LayoutParams(
                    width,
                    height,
                    Gravity.CENTER,
                )
            }
        }
        contentContainer.rotation = -90f * rotation
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) {
            return
        }
        waitingForPermissionResult = false

        if (!hasRequiredPermissions()) {
            showRequiredPermissionDialog(
                "Ứng dụng cần quyền camera và vị trí để chụp ảnh.",
            )
            return
        }
        if (!isLocationServiceReady()) {
            showRequiredPermissionDialog("Vui lòng bật dịch vụ vị trí để chụp ảnh.")
            return
        }
        cameraController?.start()
        startLocationWarmup()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackRequest()
    }

    override fun onPreviewSizeChanged(previewSize: android.util.Size) {
        runOnUiThread {
            cameraController?.configureTransform(
                contentContainer.width,
                contentContainer.height,
            )
        }
    }

    override fun onImageCaptured(
        bytes: ByteArray,
        width: Int,
        height: Int,
        jpegOrientation: Int,
    ) {
        val captureId = UUID.randomUUID().toString()
        val normalizedImage = try {
            JpegNormalizer.normalize(bytes, jpegOrientation, width, height)
        } catch (_: Exception) {
            JpegNormalizer.NormalizedImage(bytes, width, height, jpegOrientation)
        }
        val file = try {
            repository.savePending(captureId, normalizedImage.bytes)
        } catch (_: Exception) {
            null
        }

        runOnUiThread {
            inFlightCaptures = (inFlightCaptures - 1).coerceAtLeast(0)
            if (file == null) {
                updateCaptureUi()
                Toast.makeText(this, "Không thể lưu ảnh.", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            if (isFinishing || resultDelivered) {
                repository.discard(file)
                return@runOnUiThread
            }
            captures[captureId] = CaptureRecord(
                id = captureId,
                file = file,
                width = normalizedImage.width,
                height = normalizedImage.height,
                orientation = normalizedImage.orientation,
                timestamp = System.currentTimeMillis(),
                status = if (config.hasLocationTarget) {
                    CaptureStatus.PENDING_LOCATION
                } else {
                    CaptureStatus.VALID
                },
            )
            updateCaptureUi()
            if (config.hasLocationTarget) {
                locationService.validateCapture(captureId)
            }
        }
    }

    override fun onCameraError(code: String, message: String) {
        runOnUiThread { finishWithError(code, message) }
    }

    override fun onCaptureFailed(message: String) {
        runOnUiThread {
            inFlightCaptures = (inFlightCaptures - 1).coerceAtLeast(0)
            updateCaptureUi()
            if (isFinishing) {
                return@runOnUiThread
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildUi() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
        }
        contentContainer = FrameLayout(this).apply {
            clipChildren = false
        }
        rootLayout.addView(
            contentContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        textureView = TextureView(this)
        contentContainer.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        backButton = createCircleButton(
            icon = R.drawable.ic_arrow_back_24,
            backgroundColor = 0x66000000,
            description = "Quay lại",
        )
        backButton.setOnClickListener { handleBackRequest() }
        rootLayout.addView(
            backButton,
            FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.TOP or Gravity.START,
            ),
        )

        shutterContainer = FrameLayout(this).apply {
            clipChildren = false
        }
        shutterButton = createCircleButton(
            icon = R.drawable.ic_photo_camera_24,
            backgroundColor = Color.WHITE,
            description = "Chụp ảnh",
        )
        shutterButton.setOnClickListener { onShutterClicked() }
        shutterContainer.addView(
            shutterButton,
            FrameLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER
            },
        )
        shutterProgress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        shutterContainer.addView(
            shutterProgress,
            FrameLayout.LayoutParams(dp(38), dp(38)).apply {
                gravity = Gravity.CENTER
            },
        )
        rootLayout.addView(
            shutterContainer,
            FrameLayout.LayoutParams(
                dp(76),
                dp(76),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )

        confirmContainer = FrameLayout(this).apply {
            clipChildren = false
        }
        confirmButton = createCircleButton(
            icon = R.drawable.ic_check_24,
            backgroundColor = Color.rgb(33, 150, 243),
            description = "Xác nhận",
        )
        confirmButton.setOnClickListener { onConfirmClicked() }
        confirmContainer.addView(
            confirmButton,
            FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER
            },
        )

        countBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            minWidth = dp(24)
            minHeight = dp(24)
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
        }
        confirmContainer.addView(
            countBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(24),
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            },
        )

        captureProgress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        confirmContainer.addView(
            captureProgress,
            FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER
            },
        )
        rootLayout.addView(
            confirmContainer,
            FrameLayout.LayoutParams(
                dp(76),
                dp(76),
                Gravity.BOTTOM or Gravity.END,
            ),
        )

        val loadingContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        loadingContent.addView(
            ProgressBar(this).apply { isIndeterminate = true },
        )
        loadingContent.addView(
            TextView(this).apply {
                text = "Đang kiểm tra vị trí…"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )
        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        loadingOverlay.addView(
            loadingContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )
        rootLayout.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(rootLayout)
        rootLayout.setOnApplyWindowInsetsListener { _, insets ->
            systemInsetLeft = insets.systemWindowInsetLeft
            systemInsetTop = insets.systemWindowInsetTop
            systemInsetRight = insets.systemWindowInsetRight
            systemInsetBottom = insets.systemWindowInsetBottom
            applyOrientation()
            insets
        }
        rootLayout.requestApplyInsets()
        rootLayout.post { applyOrientation() }
        rootLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyOrientation()
        }
        cameraController = Camera2Controller(
            context = this,
            textureView = textureView,
            maxMegapixels = config.maxMegapixels,
            maxPendingImages = config.remainingImageCount,
            deviceRotation = { deviceRotation() },
            callback = this,
        )
        textureView.surfaceTextureListener = cameraController
        applyOrientation()
        updateCaptureUi()
    }

    private fun createCircleButton(
        icon: Int,
        backgroundColor: Int,
        description: String,
    ): ImageButton {
        return ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(backgroundColor)
            }
            elevation = dp(4).toFloat()
        }
    }

    private fun startLocationWarmup() {
        if (!config.hasLocationTarget || isLocationWarmupInProgress) {
            return
        }
        isLocationWarmupInProgress = true
        loadingOverlay.visibility = View.VISIBLE
        updateCaptureUi()
        locationService.warmupLocation { success ->
            if (isFinishing || resultDelivered) {
                return@warmupLocation
            }
            isLocationWarmupInProgress = false
            loadingOverlay.visibility = View.GONE
            updateCaptureUi()
            if (!success) {
                showLocationWarmupError()
            }
        }
    }

    private fun showLocationWarmupError() {
        materialDialogBuilder()
            .setTitle("Không xác định được vị trí")
            .setMessage(
                "Vui lòng kiểm tra lại kết nối định vị của thiết bị " +
                    "rồi thử lại.",
            )
            .setCancelable(false)
            .setNegativeButton("Đóng") { _, _ -> finishCancelled() }
            .show()
            .also(::styleDialogButtons)
    }

    private fun onShutterClicked() {
        if (isLocationWarmupInProgress) {
            return
        }
        if (inFlightCaptures > 0 ||
            captures.values.any { it.status == CaptureStatus.PENDING_LOCATION }
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastShutterTapElapsedMs < SHUTTER_DEBOUNCE_MS) {
            return
        }
        lastShutterTapElapsedMs = now
        if (captures.size + inFlightCaptures >= config.remainingImageCount) {
            showInfoDialog(
                title = "Đã đạt giới hạn",
                message = "Bạn chỉ có thể chụp tối đa ${config.remainingImageCount} ảnh.",
            )
            return
        }
        inFlightCaptures += 1
        updateCaptureUi()
        cameraController?.captureStillImage()
    }

    private fun onConfirmClicked() {
        val pendingCount = captures.values.count {
            it.status == CaptureStatus.PENDING_LOCATION
        }
        if (pendingCount > 0) {
            showInfoDialog(
                title = "Đang kiểm tra vị trí",
                message = "Vui lòng đợi hoàn tất kiểm tra vị trí của ảnh.",
            )
            return
        }

        val validCaptures = captures.values.filter {
            it.status == CaptureStatus.VALID &&
                (!config.hasLocationTarget ||
                    (it.latitude != null &&
                        it.longitude != null &&
                        it.distanceToTargetMeters != null)) &&
                it.file.exists()
        }
        if (validCaptures.isEmpty() || validCaptures.size != captures.size) {
            return
        }
        finishConfirmed(validCaptures)
    }

    private fun handleBackRequest() {
        if (captures.isEmpty()) {
            finishCancelled()
            return
        }

        materialDialogBuilder()
            .setTitle("Không lưu ảnh đã chụp")
            .setMessage("Đồng ý quay lại và chưa ghi nhận ảnh đã chụp")
            .setNegativeButton("Chụp tiếp", null)
            .setPositiveButton("Quay lại") { _, _ -> finishCancelled() }
            .show()
            .also { styleDialogButtons(it, destructive = true) }
    }

    private fun onLocationValidationResult(
        result: LocationValidationService.LocationValidationResult,
    ) {
        if (isFinishing || resultDelivered) {
            return
        }
        val records = result.captureIds.mapNotNull(captures::get)
        if (records.isEmpty()) {
            return
        }

        if (!result.isValid || result.location == null) {
            records.forEach { record ->
                captures.remove(record.id)
                repository.discard(record.file)
            }
            updateCaptureUi()
            val message = result.distanceToTargetMeters?.let { distance ->
                "Bạn đang đứng cách vị trí cho phép ${formatDistance(distance)}."
            } ?: (result.errorMessage ?: "Không xác định được vị trí. Ảnh không được ghi nhận.")
            showInfoDialog("Vị trí không hợp lệ", message)
            return
        }

        records.forEach { record ->
            captures[record.id] = record.copy(
                status = CaptureStatus.VALID,
                latitude = result.location.latitude,
                longitude = result.location.longitude,
                distanceToTargetMeters = result.distanceToTargetMeters,
            )
        }
        updateCaptureUi()
    }

    private fun updateCaptureUi() {
        countBadge.text = captures.size.toString()
        countBadge.visibility = if (captures.isEmpty()) View.GONE else View.VISIBLE

        val isProcessing = inFlightCaptures > 0 ||
            captures.values.any { it.status == CaptureStatus.PENDING_LOCATION }

        shutterButton.isEnabled = !isLocationWarmupInProgress && !isProcessing
        shutterButton.alpha = if (shutterButton.isEnabled) 1f else 0.45f
        shutterProgress.visibility = if (isProcessing) View.VISIBLE else View.GONE

        confirmButton.isEnabled = !isProcessing && captures.isNotEmpty() &&
            captures.values.all {
                it.status == CaptureStatus.VALID &&
                    (!config.hasLocationTarget ||
                        (it.latitude != null &&
                            it.longitude != null &&
                            it.distanceToTargetMeters != null)) &&
                    it.file.exists()
            }
        confirmButton.alpha = if (confirmButton.isEnabled) 1f else 0.45f
        captureProgress.visibility = if (isProcessing) View.VISIBLE else View.GONE
    }

    private fun finishConfirmed(validCaptures: List<CaptureRecord>) {
        if (::locationService.isInitialized) {
            locationService.cancelAll()
        }
        val images = JSONArray()
        try {
            validCaptures.forEach { capture ->
                val confirmedFile = repository.confirmCapture(capture.id, capture.file)
                images.put(
                    JSONObject()
                        .put("id", capture.id)
                        .put("filePath", confirmedFile.absolutePath)
                        .put("width", capture.width)
                        .put("height", capture.height)
                        .put("orientation", capture.orientation)
                        .put("timestamp", capture.timestamp)
                        .put("latitude", capture.latitude ?: JSONObject.NULL)
                        .put("longitude", capture.longitude ?: JSONObject.NULL)
                        .put(
                            "distanceToTargetMeters",
                            capture.distanceToTargetMeters ?: JSONObject.NULL,
                        ),
                )
            }
        } catch (_: Exception) {
            finishWithError("CONFIRM_FAILED", "Không thể lưu ảnh đã chụp.")
            return
        }

        val result = Intent()
            .putExtra(EXTRA_STATUS, STATUS_CONFIRMED)
            .putExtra(EXTRA_IMAGES_JSON, images.toString())
        setResult(RESULT_OK, result)
        resultDelivered = true
        finish()
    }

    private fun finishCancelled() {
        if (::locationService.isInitialized) {
            locationService.cancelAll()
        }
        repository.discardAll(captures.values.map { it.file })
        captures.clear()
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(EXTRA_STATUS, STATUS_CANCELLED),
        )
        resultDelivered = true
        finish()
    }

    private fun finishWithError(code: String, message: String) {
        if (::locationService.isInitialized) {
            locationService.cancelAll()
        }
        if (::repository.isInitialized) {
            repository.discardAll(captures.values.map { it.file })
        }
        captures.clear()
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_STATUS, STATUS_ERROR)
                .putExtra(EXTRA_ERROR_CODE, code)
                .putExtra(EXTRA_ERROR_MESSAGE, message),
        )
        resultDelivered = true
        finish()
    }

    private fun showRequiredPermissionDialog(message: String) {
        if (permissionDialogShowing || isFinishing) {
            return
        }
        permissionDialogShowing = true
        materialDialogBuilder()
            .setTitle("Cần cấp quyền")
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton("Đóng") { _, _ -> finishCancelled() }
            .show()
            .also(::styleDialogButtons)
    }

    private fun showInfoDialog(title: String, message: String) {
        if (isFinishing) {
            return
        }
        materialDialogBuilder()
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Đóng", null)
            .show()
            .also(::styleDialogButtons)
    }

    private fun materialDialogBuilder(): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(
            ContextThemeWrapper(
                this,
                com.google.android.material.R.style.ThemeOverlay_Material3_Light,
            ),
        )
    }

    private fun styleDialogButtons(
        dialog: AlertDialog,
        destructive: Boolean = false,
    ) {
        val textColor = Color.rgb(0x1B, 0x1B, 0x1F)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            setTextColor(
                if (destructive) {
                    Color.rgb(211, 47, 47)
                } else {
                    Color.rgb(33, 150, 243)
                },
            )
            typeface = Typeface.DEFAULT_BOLD
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(textColor)
    }

    private fun hasRequiredPermissions(): Boolean {
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val locationGranted = !config.hasLocationTarget ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return cameraGranted && locationGranted
    }

    private fun requiredPermissions(): Array<String> {
        return if (config.hasLocationTarget) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    }

    private fun formatDistance(distanceMeters: Float): String {
        val formatter = NumberFormat.getIntegerInstance(Locale("vi", "VN"))
        return "${formatter.format(distanceMeters.roundToInt())} m"
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).roundToInt()
    }

    companion object {
        const val REQUEST_PERMISSIONS = 1001
        const val EXTRA_STATUS = "status"
        const val EXTRA_IMAGES_JSON = "imagesJson"
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_ERROR = "error"
        private const val SHUTTER_DEBOUNCE_MS = 700L


    }
}
