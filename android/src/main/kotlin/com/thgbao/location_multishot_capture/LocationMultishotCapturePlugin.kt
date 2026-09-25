package com.thgbao.location_multishot_capture

import android.app.Activity
import android.content.Intent
import com.thgbao.location_multishot_capture.camera.ImageFileRepository
import com.thgbao.location_multishot_capture.camera.NativeCameraActivity
import com.thgbao.location_multishot_capture.camera.NativeCameraConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject

class LocationMultishotCapturePlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener {

    private lateinit var channel: MethodChannel
    private lateinit var applicationContext: android.content.Context
    private var activity: Activity? = null
    private var pendingCameraResult: MethodChannel.Result? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(
        binding: ActivityPluginBinding,
    ) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "openCamera" -> openNativeCamera(call, result)
            "clearSessionImages" -> {
                ImageFileRepository(applicationContext).clearAllSessionImages()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (requestCode != REQUEST_NATIVE_CAMERA) {
            return false
        }

        val result = pendingCameraResult ?: return true
        pendingCameraResult = null

        val status = data?.getStringExtra(NativeCameraActivity.EXTRA_STATUS)
            ?: NativeCameraActivity.STATUS_CANCELLED
        val response = hashMapOf<String, Any?>("status" to status)

        when (status) {
            NativeCameraActivity.STATUS_CONFIRMED -> {
                val imagesJson = data?.getStringExtra(
                    NativeCameraActivity.EXTRA_IMAGES_JSON,
                ).orEmpty()
                response["images"] = parseImages(imagesJson)
            }
            NativeCameraActivity.STATUS_ERROR -> {
                response["errorCode"] = data?.getStringExtra(
                    NativeCameraActivity.EXTRA_ERROR_CODE,
                )
                response["errorMessage"] = data?.getStringExtra(
                    NativeCameraActivity.EXTRA_ERROR_MESSAGE,
                )
            }
        }

        result.success(response)
        return true
    }

    private fun openNativeCamera(call: MethodCall, result: MethodChannel.Result) {
        if (pendingCameraResult != null) {
            result.error("CAMERA_ALREADY_OPEN", "Native camera is already open.", null)
            return
        }
        val hostActivity = activity
        if (hostActivity == null) {
            result.error(
                "NO_ACTIVITY",
                "Plugin is not attached to an activity yet.",
                null,
            )
            return
        }

        val arguments = call.arguments as? Map<*, *> ?: emptyMap<String, Any>()
        val intent = Intent(hostActivity, NativeCameraActivity::class.java)
            .putExtra(
                NativeCameraConfig.EXTRA_MAX_IMAGES,
                arguments.intValue("maxImages", 5),
            )
            .putExtra(
                NativeCameraConfig.EXTRA_EXISTING_IMAGE_COUNT,
                arguments.intValue("existingImageCount", 0),
            )
        arguments.doubleOrNull("targetLatitude")?.let {
            intent.putExtra(NativeCameraConfig.EXTRA_TARGET_LATITUDE, it)
        }
        arguments.doubleOrNull("targetLongitude")?.let {
            intent.putExtra(NativeCameraConfig.EXTRA_TARGET_LONGITUDE, it)
        }
        arguments.doubleOrNull("targetRadiusMeters")?.let {
            intent.putExtra(NativeCameraConfig.EXTRA_TARGET_RADIUS_METERS, it)
        }
        intent.putExtra(
            NativeCameraConfig.EXTRA_MAX_MEGAPIXELS,
            arguments.intValue("maxMegapixels", Int.MAX_VALUE),
        )

        pendingCameraResult = result
        try {
            hostActivity.startActivityForResult(intent, REQUEST_NATIVE_CAMERA)
        } catch (exception: Exception) {
            pendingCameraResult = null
            result.error(
                "CAMERA_OPEN_FAILED",
                "Unable to open native camera.",
                null,
            )
        }
    }

    private fun parseImages(imagesJson: String): List<Map<String, Any?>> {
        if (imagesJson.isBlank()) {
            return emptyList()
        }

        val array = JSONArray(imagesJson)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(item.toHashMap())
            }
        }
    }

    private fun JSONObject.toHashMap(): Map<String, Any?> {
        val map = hashMapOf<String, Any?>()
        keys().forEach { key ->
            map[key] = if (isNull(key)) null else get(key)
        }
        return map
    }

    private fun Map<*, *>.intValue(key: String, defaultValue: Int): Int {
        return (this[key] as? Number)?.toInt() ?: defaultValue
    }

    private fun Map<*, *>.doubleOrNull(key: String): Double? {
        return (this[key] as? Number)?.toDouble()
    }

    companion object {
        private const val CHANNEL_NAME = "location_multishot_capture/native_camera"
        private const val REQUEST_NATIVE_CAMERA = 2001
    }
}
