package com.thgbao.location_multishot_capture.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val maxMegapixels: Int,
    private val maxPendingImages: Int,
    private val deviceRotation: () -> Int,
    private val callback: Callback,
) : TextureView.SurfaceTextureListener {
    interface Callback {
        fun onPreviewSizeChanged(previewSize: Size)
        fun onImageCaptured(
            bytes: ByteArray,
            width: Int,
            height: Int,
            jpegOrientation: Int,
        )
        fun onCameraError(code: String, message: String)
        fun onCaptureFailed(message: String)
    }

    private data class ImageSizeCandidate(
        val size: Size,
        val requiresMaximumResolution: Boolean,
    )

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewRequest: CaptureRequest? = null
    private var previewSize: Size? = null
    private var imageSize: Size? = null
    private var sensorOrientation = 0
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var cameraId: String? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    @Volatile
    private var cameraOpenRequested = false
    private var useMaximumResolutionCapture = false
    @Volatile
    private var isStopped = true
    private val pendingJpegOrientations = ConcurrentLinkedQueue<Int>()

    fun start() {
        isStopped = false
        startBackgroundThread()
        if (textureView.isAvailable) {
            surfaceTexture = textureView.surfaceTexture
            openCamera()
        }
    }

    @Synchronized
    fun stop() {
        isStopped = true
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        cameraOpenRequested = false
        useMaximumResolutionCapture = false
        pendingJpegOrientations.clear()
        stopBackgroundThread()
    }

    fun captureStillImage() {
        val session = captureSession
        val device = cameraDevice
        val reader = imageReader
        if (isStopped || session == null || device == null || reader == null) {
            callback.onCaptureFailed("Camera chưa sẵn sàng.")
            return
        }

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            requestBuilder.addTarget(reader.surface)
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            val jpegOrientation = getJpegOrientation()
            // Request an unrotated buffer from the HAL: some HALs rotate JPEG
            // pixels themselves without writing an EXIF tag, which would
            // double-apply the rotation we do in JpegNormalizer.
            requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0)
            pendingJpegOrientations.add(jpegOrientation)
            requestBuilder.set(CaptureRequest.JPEG_QUALITY, MAX_JPEG_QUALITY)
            if (useMaximumResolutionCapture &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                requestBuilder.set(
                    CaptureRequest.SENSOR_PIXEL_MODE,
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION,
                )
            }

            session.capture(
                requestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) = Unit

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        failPendingImage()
                    }
                },
                backgroundHandler,
            )
        } catch (exception: CameraAccessException) {
            failPendingImage()
        } catch (exception: IllegalStateException) {
            failPendingImage()
        }
    }

    fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val size = previewSize ?: return
        if (viewWidth == 0 || viewHeight == 0) {
            return
        }

        val naturalPreviewWidth = if (sensorOrientation == 90 || sensorOrientation == 270) {
            size.height.toFloat()
        } else {
            size.width.toFloat()
        }
        val naturalPreviewHeight = if (sensorOrientation == 90 || sensorOrientation == 270) {
            size.width.toFloat()
        } else {
            size.height.toFloat()
        }
        val scale = min(
            viewWidth.toFloat() / naturalPreviewWidth,
            viewHeight.toFloat() / naturalPreviewHeight,
        )
        val targetWidth = (naturalPreviewWidth * scale).roundToInt()
        val targetHeight = (naturalPreviewHeight * scale).roundToInt()

        val params = textureView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != targetWidth ||
            params.height != targetHeight ||
            params.gravity != Gravity.CENTER
        ) {
            textureView.layoutParams = FrameLayout.LayoutParams(
                targetWidth,
                targetHeight,
                Gravity.CENTER,
            )
        }
        textureView.rotation = 0f
        textureView.setTransform(Matrix())
    }

    private fun configureToParentSize() {
        val parent = textureView.parent as? View ?: return
        configureTransform(parent.width, parent.height)
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surfaceTexture = surface
        if (!isStopped) {
            openCamera()
        }
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        configureToParentSize()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceTexture = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (isStopped || cameraDevice != null || cameraOpenRequested ||
            surfaceTexture == null
        ) {
            return
        }

        try {
            cameraOpenRequested = true
            val selectedCameraId = selectCamera()
            cameraId = selectedCameraId
            val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)
            sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            val sensorAspectRatio = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
            )?.let { it.width().toDouble() / it.height() } ?: (4.0 / 3.0)
            val maximumResolutionAspectRatio =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION,
                    )?.let { it.width().toDouble() / it.height() } ?: sensorAspectRatio
                } else {
                    sensorAspectRatio
                }
            val configurationMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            ) ?: run {
                callback.onCameraError("CAMERA_UNAVAILABLE", "Camera không hỗ trợ cấu hình output.")
                return
            }

            val regularJpegSizes =
                configurationMap.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val highResolutionJpegSizes = try {
                configurationMap.getHighResolutionOutputSizes(ImageFormat.JPEG)
                    ?.toList()
                    .orEmpty()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
            val maximumResolutionJpegSizes =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
                    )?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                } else {
                    emptyList()
                }
            val standardJpegSizes = (regularJpegSizes + highResolutionJpegSizes).distinct()
            val jpegCandidates = buildList {
                standardJpegSizes.forEach {
                    add(ImageSizeCandidate(it, requiresMaximumResolution = false))
                }
                maximumResolutionJpegSizes
                    .filterNot { standardJpegSizes.contains(it) }
                    .forEach {
                        add(ImageSizeCandidate(it, requiresMaximumResolution = true))
                    }
            }
            val selectedImageSize = chooseImageSize(
                jpegCandidates,
                sensorAspectRatio,
                maximumResolutionAspectRatio,
            ) ?: run {
                callback.onCameraError("CAMERA_UNAVAILABLE", "Camera không hỗ trợ JPEG output.")
                return
            }
            imageSize = selectedImageSize.size
            useMaximumResolutionCapture = selectedImageSize.requiresMaximumResolution

            val previewSizes = configurationMap
                .getOutputSizes(SurfaceTexture::class.java)
                ?.toList()
                .orEmpty()
            previewSize = choosePreviewSize(previewSizes, imageSize!!) ?: run {
                callback.onCameraError("CAMERA_UNAVAILABLE", "Camera không hỗ trợ preview.")
                return
            }
            imageReader = ImageReader.newInstance(
                imageSize!!.width,
                imageSize!!.height,
                ImageFormat.JPEG,
                maxOf(3, maxPendingImages),
            ).apply {
                setOnImageAvailableListener(::onImageAvailable, backgroundHandler)
            }

            cameraManager.openCamera(
                selectedCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        synchronized(this@Camera2Controller) {
                            if (isStopped) {
                                cameraOpenRequested = false
                                device.close()
                                return
                            }
                            cameraDevice = device
                        }
                        createPreviewSession()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        cameraOpenRequested = false
                        device.close()
                        if (cameraDevice == device) {
                            cameraDevice = null
                        }
                        callback.onCameraError(
                            "CAMERA_UNAVAILABLE",
                            "Camera đang được ứng dụng khác sử dụng.",
                        )
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        cameraOpenRequested = false
                        device.close()
                        if (cameraDevice == device) {
                            cameraDevice = null
                        }
                        callback.onCameraError("CAMERA_OPEN_FAILED", "Không thể mở camera.")
                    }
                },
                backgroundHandler,
            )
        } catch (exception: CameraAccessException) {
            cameraOpenRequested = false
            callback.onCameraError("CAMERA_UNAVAILABLE", "Không thể truy cập camera.")
        } catch (exception: SecurityException) {
            cameraOpenRequested = false
            callback.onCameraError("CAMERA_PERMISSION_DENIED", "Thiếu quyền camera.")
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val texture = surfaceTexture ?: return
        val size = previewSize ?: return

        try {
            texture.setDefaultBufferSize(size.width, size.height)
            previewSurface?.release()
            val surface = Surface(texture)
            previewSurface = surface
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    synchronized(this@Camera2Controller) {
                        if (isStopped || cameraDevice == null) {
                            session.close()
                            return
                        }
                        captureSession = session
                    }
                    try {
                        val requestBuilder =
                            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        requestBuilder.addTarget(surface)
                        requestBuilder.set(
                            CaptureRequest.CONTROL_MODE,
                            CaptureRequest.CONTROL_MODE_AUTO,
                        )
                        requestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        )
                        if (useMaximumResolutionCapture &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ) {
                            requestBuilder.set(
                                CaptureRequest.SENSOR_PIXEL_MODE,
                                CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT,
                            )
                        }
                        previewRequest = requestBuilder.build()
                        session.setRepeatingRequest(
                            previewRequest!!,
                            null,
                            backgroundHandler,
                        )
                        callback.onPreviewSizeChanged(size)
                    } catch (exception: CameraAccessException) {
                        callback.onCameraError(
                            "CAMERA_OPEN_FAILED",
                            "Không thể khởi tạo preview camera.",
                        )
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    callback.onCameraError(
                        "CAMERA_OPEN_FAILED",
                        "Không thể khởi tạo camera session.",
                    )
                }
            }

            if (useMaximumResolutionCapture &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                val executor = Executor { command ->
                    backgroundHandler?.post(command) ?: command.run()
                }
                val previewOutput = OutputConfiguration(surface).apply {
                    addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
                }
                val imageOutput = OutputConfiguration(reader.surface).apply {
                    addSensorPixelModeUsed(
                        CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION,
                    )
                }
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(previewOutput, imageOutput),
                        executor,
                        sessionCallback,
                    ),
                )
            } else {
                device.createCaptureSession(
                    listOf(surface, reader.surface),
                    sessionCallback,
                    backgroundHandler,
                )
            }
        } catch (exception: CameraAccessException) {
            callback.onCameraError("CAMERA_OPEN_FAILED", "Không thể khởi tạo camera.")
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        }
        if (image == null) {
            failPendingImage()
            return
        }
        try {
            val buffer = image.planes.firstOrNull()?.buffer
            if (buffer == null) {
                failPendingImage()
                return
            }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val size = imageSize ?: Size(image.width, image.height)
            val jpegOrientation = pendingJpegOrientations.poll()
                ?: getJpegOrientation()
            callback.onImageCaptured(
                bytes,
                size.width,
                size.height,
                jpegOrientation,
            )
        } finally {
            image.close()
        }
    }

    private fun failPendingImage() {
        pendingJpegOrientations.poll()
        callback.onCaptureFailed("Không thể chụp ảnh.")
    }

    private fun selectCamera(): String {
        val ids = cameraManager.cameraIdList
        val rearCamera = ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        return rearCamera ?: ids.firstOrNull()
            ?: throw CameraAccessException(CameraAccessException.CAMERA_ERROR)
    }

    private fun chooseImageSize(
        sizes: List<ImageSizeCandidate>,
        sensorAspectRatio: Double,
        maximumResolutionAspectRatio: Double,
    ): ImageSizeCandidate? {
        if (sizes.isEmpty()) {
            return null
        }
        val matchingSensorAspect = sizes.filter {
            val targetAspectRatio = if (it.requiresMaximumResolution) {
                maximumResolutionAspectRatio
            } else {
                sensorAspectRatio
            }
            abs(it.size.width.toDouble() / it.size.height - targetAspectRatio) <= 0.05
        }
        val candidates = matchingSensorAspect.ifEmpty { sizes }
        val maxPixels = maxMegapixels.toLong() * 1_000_000L
        val underLimit = candidates.filter {
            it.size.width.toLong() * it.size.height <= maxPixels
        }
        return underLimit.maxByOrNull { it.size.width.toLong() * it.size.height }
            ?: candidates.minByOrNull { it.size.width.toLong() * it.size.height }
    }

    private fun choosePreviewSize(sizes: List<Size>, outputSize: Size): Size? {
        if (sizes.isEmpty()) {
            return null
        }
        val outputAspect = outputSize.width.toDouble() / outputSize.height
        val safePreviewSizes = sizes.filter {
            it.width <= MAX_PREVIEW_WIDTH && it.height <= MAX_PREVIEW_HEIGHT
        }
        val supportedPreviewSizes = safePreviewSizes.ifEmpty { sizes }
        val matchingAspect = supportedPreviewSizes.filter {
            abs(it.width.toDouble() / it.height - outputAspect) <= 0.05
        }
        val candidates = matchingAspect.ifEmpty { supportedPreviewSizes }
        val targetPixels = textureView.width.toLong() * textureView.height
        return candidates.minByOrNull { size ->
            val aspectDifference = abs(size.width.toDouble() / size.height - outputAspect)
            val pixelDifference = abs(size.width.toLong() * size.height - targetPixels)
            (aspectDifference * 1_000_000).toLong() + min(pixelDifference, Long.MAX_VALUE / 2)
        }
    }

    private fun getRelativeRotation(): Int {
        // deviceRotation() returns display.rotation: the window always rotates
        // (fullSensor), so the standard Camera2 formula applies directly.
        // Back camera: sensor - degrees; front camera mirrors: sensor + degrees.
        val deviceDegrees = when (deviceRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) -1 else 1
        return (sensorOrientation - deviceDegrees * sign + 360) % 360
    }

    private fun getJpegOrientation(): Int {
        return getRelativeRotation()
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) {
            return
        }
        backgroundThread = HandlerThread("NativeCamera2").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private companion object {
        const val MAX_PREVIEW_WIDTH = 1920
        const val MAX_PREVIEW_HEIGHT = 1080
        const val MAX_JPEG_QUALITY: Byte = 100
    }
}
