package com.flexfly.ultron.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.flexfly.ultron.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OpenRouter vision helper. Captures a camera frame (or takes an existing bitmap /
 * screenshot), compresses it to base64, and asks an OpenRouter chat-completions vision
 * model to describe it in Hinglish. Tries [VISION_MODELS] in order until one returns a
 * non-blank answer.
 *
 * Recovered from `Cameravisionmanager.kt`. The original was an `object` singleton
 * (`INSTANCE`). The three public entry points ([analyzeScene], [analyzeBitmap],
 * [checkCredits]) launch work on [scope] and deliver results through
 * `onResult`/`onError` callbacks; their `$1` state-machine inner classes are folded
 * back into the `launch { }` bodies here.
 *
 * Cross-file refs — verify: [AppPreferences] (uses `.llamaVisionApiKey`).
 */
object CameraVisionManager {

    private const val TAG = "CameraVision"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val VISION_MODELS = listOf(
        "openrouter/free",
        "nvidia/nemotron-nano-vl-7b-v1:free",
        "google/gemma-4-26b-a4b-it:free",
    )

    fun analyzeScene(
        context: Context,
        question: String = "",
        useFrontCamera: Boolean = false,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (ContextCompat.checkSelfPermission(context, "android.permission.CAMERA") != 0) {
            onError("📸 Camera permission chahiye! Settings > Apps > Ultron > Permissions mein Camera ON karo.")
            return
        }
        Log.d(TAG, "🤳 Camera capture shuru... front=$useFrontCamera")
        scope.launch {
            captureFrame(
                context = context,
                useFrontCamera = useFrontCamera,
                onCaptured = { bitmap ->
                    // From CameraVisionManager$analyzeScene$1$1$1 — runs the vision call on IO,
                    // saves the frame as the "intruder selfie" for anti-theft.
                    scope.launch(Dispatchers.IO) {
                        try {
                            val appPreferences = AppPreferences(context)
                            val apiKey = appPreferences.llamaVisionApiKey
                            if (apiKey.isBlank()) {
                                onError("Boss, OpenRouter API Key missing hai! Settings mein jaakar setup karein.")
                                bitmap.recycle()
                                return@launch
                            }

                            val base64Image = bitmapToBase64(bitmap)
                            try {
                                val file = File(context.cacheDir, "intruder_selfie.jpg")
                                FileOutputStream(file).use { fos ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                                }
                                Log.d(TAG, "📸 Intruder selfie saved to: ${file.absolutePath}")
                                val galleryUri = saveBitmapToGallery(context, bitmap)
                                if (galleryUri != null) {
                                    appPreferences.lastCapturedPhotoUri = galleryUri.toString()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to save intruder selfie: ${e.message}")
                            }
                            bitmap.recycle()

                            if (base64Image == null) {
                                onError("❌ Image compress error.")
                                return@launch
                            }

                            val prompt = buildPrompt(question)
                            var lastError = "No models tried"
                            for (model in VISION_MODELS) {
                                try {
                                    Log.d(TAG, "🔁 Trying: $model")
                                    val result = callVisionAPI(apiKey, model, base64Image, prompt)
                                    if (!result.isNullOrBlank()) {
                                        Log.d(TAG, "✅ Success: $model → $result")
                                        onResult(result)
                                        return@launch
                                    }
                                    lastError = "$model: empty response"
                                    Log.w(TAG, "⚠️ $model empty response")
                                } catch (e: Exception) {
                                    lastError = e.message ?: "unknown" // RECOVERY NOTE: EnvironmentCompat.MEDIA_UNKNOWN == "unknown"
                                    Log.w(TAG, "❌ $model failed: $lastError")
                                }
                            }
                            onError("😞 Vision failed. Last error: $lastError")
                        } catch (e: Exception) {
                            Log.e(TAG, "Outer error: ${e.message}", e)
                            onError("❌ Error: ${e.localizedMessage}")
                        }
                    }
                },
                onFail = { error ->
                    Log.e(TAG, "Camera fail: $error")
                    onError("❌ Camera: $error")
                },
            )
        }
    }

    fun analyzeBitmap(
        context: Context,
        bitmap: Bitmap,
        question: String = "",
        isScreenshot: Boolean = false,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            // From CameraVisionManager$analyzeBitmap$1.
            try {
                val apiKey = AppPreferences(context).llamaVisionApiKey
                if (apiKey.isBlank()) {
                    onError("Boss, OpenRouter API Key missing hai! Settings mein jaakar setup karein.")
                    bitmap.recycle()
                    return@launch
                }

                val base64Image = bitmapToBase64(bitmap)
                bitmap.recycle()
                if (base64Image == null) {
                    onError("❌ Image compress error.")
                    return@launch
                }

                val prompt = buildPrompt(question, isScreenshot)
                var lastError = "No models tried"
                for (model in VISION_MODELS) {
                    try {
                        Log.d(TAG, "🔁 Trying: $model")
                        val result = callVisionAPI(apiKey, model, base64Image, prompt)
                        if (!result.isNullOrBlank()) {
                            Log.d(TAG, "✅ Success: $model → $result")
                            onResult(result)
                            return@launch
                        }
                        lastError = "$model: empty response"
                        Log.w(TAG, "⚠️ $model empty response")
                    } catch (e: Exception) {
                        lastError = e.message ?: "unknown" // RECOVERY NOTE: EnvironmentCompat.MEDIA_UNKNOWN == "unknown"
                        Log.w(TAG, "❌ $model failed: $lastError")
                    }
                }
                onError("😞 Vision failed. Last error: $lastError")
            } catch (e: Exception) {
                Log.e(TAG, "Outer error: ${e.message}", e)
                onError("❌ Error: ${e.localizedMessage}")
            }
        }
    }

    private fun buildPrompt(question: String, isScreenshot: Boolean = false): String {
        val base = "You are Max, a friendly Android voice assistant. IMPORTANT: You MUST respond ONLY in natural Hinglish (Hindi written in Latin script, e.g. 'Screen par ek photo dikh rahi hai...') or simple Hindi. Do NOT respond in English under any circumstances. Keep your response very short, friendly, and natural (max 2 sentences, no markdown, no bullet points). Note: If the screenshot shows the Max voice assistant app itself (or our conversation chat history/chat screen), tell the user in Hinglish that the chat screen is open, and guide them that if they want to analyze a gallery photo, they should open that photo in the Gallery app first and then ask you. "
        return if (isScreenshot) {
            if (question.isNotBlank()) {
                base + "Answer this question about what is shown on the phone screen screenshot: " + question
            } else {
                base + "Describe what is visible on this phone screen screenshot."
            }
        } else {
            if (question.isNotBlank()) {
                base + "Answer this question about the image: " + question
            } else {
                base + "Describe what is in front of the camera."
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val stream = ByteArrayOutputStream()
            var bmp = bitmap
            if (bmp.width > 800 || bmp.height > 600) {
                bmp = Bitmap.createScaledBitmap(bmp, 800, 600, true)
            }
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val bytes = stream.toByteArray()
            stream.close()
            Base64.encodeToString(bytes, Base64.NO_WRAP) // RECOVERY NOTE: flag 2 == Base64.NO_WRAP
        } catch (e: Exception) {
            Log.e(TAG, "Base64 failed: ${e.message}")
            null
        }
    }

    private fun callVisionAPI(apiKey: String, model: String, base64Image: String, promptText: String): String? {
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        // RECOVERY NOTE: ClassDiscriminatorModeKt.CLASS_DISCRIMINATOR_KEY == "type";
                        // ImagesContract.URL == "url"; TypingAccessibilityService.EXTRA_TEXT == "text".
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", promptText)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/ankit143659/Ultron-the-Boss")
            .header("X-Title", "Ultron")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            Log.d(TAG, "$model → HTTP ${response.code}")
            if (!response.isSuccessful) {
                Log.e(TAG, "$model error body: $bodyStr")
                val reason = when (response.code) {
                    401 -> "Invalid API Key"
                    402 -> "Insufficient credits"
                    404 -> "Model not found"
                    429 -> "Rate limited — retry baad mein"
                    500, 502, 503 -> "Server down"
                    else -> "HTTP ${response.code}"
                }
                throw Exception("$model: $reason")
            }
            try {
                val json = JSONObject(bodyStr)
                if (json.has("error")) {
                    throw Exception(json.getJSONObject("error").optString("message", "API error"))
                }
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                return content?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "Parse error $model: ${e.message}")
                throw e
            }
        }
    }

    private fun captureFrame(
        context: Context,
        useFrontCamera: Boolean,
        onCaptured: (Bitmap) -> Unit,
        onFail: (String) -> Unit,
    ) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // LENS_FACING_FRONT == 0, LENS_FACING_BACK == 1; original compares against !useFrontCamera.
        val wantFacing = if (useFrontCamera) 0 else 1

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                facing != null && facing == wantFacing
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                onFail("No camera found")
                return
            }

            val handlerThread = HandlerThread("CameraThread")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)
            // RECOVERY NOTE: ImageFormat.JPEG == 256.
            val imageReader = ImageReader.newInstance(1080, 720, ImageFormat.JPEG, 2)
            val finished = booleanArrayOf(false)
            val device = arrayOfNulls<CameraDevice>(1)

            fun cleanup() {
                try {
                    device[0]?.close()
                } catch (_: Exception) {
                }
                try {
                    imageReader.close()
                } catch (_: Exception) {
                }
                handler.postDelayed({ handlerThread.quitSafely() }, 1000L)
            }

            // RECOVERY NOTE: DeviceOrientationRequest.OUTPUT_PERIOD_MEDIUM == 10000L (matches "10s" message).
            handler.postDelayed({
                if (finished[0]) return@postDelayed
                finished[0] = true
                cleanup()
                onFail("Camera timeout (10s)")
            }, 10000L)

            imageReader.setOnImageAvailableListener({ reader ->
                if (finished[0]) return@setOnImageAvailableListener
                finished[0] = true
                try {
                    val image = reader.acquireLatestImage() ?: throw Exception("Null image")
                    val buffer = image.planes[0].buffer
                    val remaining = buffer.remaining()
                    val bytes = ByteArray(remaining)
                    buffer.get(bytes)
                    image.close()
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, remaining)
                        ?: throw Exception("Bitmap decode failed")
                    cleanup()
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    // RECOVERY NOTE: jadx flattened the original if/else into a fall-through that
                    // appears to invoke onCaptured twice; rotateBitmap recycles its source, so only
                    // one branch can run. Restored as if/else (front camera → mirror, else → no flip).
                    val rotated = if (lensFacing != null && lensFacing == 0) {
                        // Front camera: mirror horizontally.
                        rotateBitmap(decoded, sensorOrientation.toFloat(), true)
                    } else {
                        rotateBitmap(decoded, sensorOrientation.toFloat(), false)
                    }
                    onCaptured(rotated)
                } catch (e: Exception) {
                    cleanup()
                    onFail("Image error: ${e.message}")
                }
            }, handler)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device[0] = cam
                    try {
                        // RECOVERY NOTE: createCaptureRequest(2) == TEMPLATE_STILL_CAPTURE.
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        req.addTarget(imageReader.surface)
                        // RECOVERY NOTE: CONTROL_MODE 1 == CONTROL_MODE_AUTO.
                        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        // RECOVERY NOTE: CONTROL_AF_MODE 4 == CONTROL_AF_MODE_CONTINUOUS_PICTURE.
                        req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        // RECOVERY NOTE: CONTROL_AE_MODE 1 == CONTROL_AE_MODE_ON.
                        req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        req.set(CaptureRequest.JPEG_ORIENTATION, 0)
                        req.set(CaptureRequest.JPEG_QUALITY, 90.toByte())

                        cam.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                // From CameraVisionManager$captureFrame$3$onOpened$1.
                                override fun onConfigured(session: CameraCaptureSession) {
                                    handler.postDelayed({
                                        if (finished[0]) return@postDelayed
                                        try {
                                            session.capture(req.build(), null, handler)
                                        } catch (e: Exception) {
                                            if (finished[0]) return@postDelayed
                                            finished[0] = true
                                            cleanup()
                                            onFail("Capture fail: ${e.message}")
                                        }
                                    }, 1200L)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    if (finished[0]) return
                                    finished[0] = true
                                    cleanup()
                                    onFail("Session configure fail")
                                }
                            },
                            handler,
                        )
                    } catch (e: Exception) {
                        if (finished[0]) return
                        finished[0] = true
                        cleanup()
                        onFail("Request error: ${e.message}")
                    }
                }

                override fun onDisconnected(cam: CameraDevice) {
                    if (finished[0]) return
                    finished[0] = true
                    cleanup()
                    onFail("Camera disconnected")
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    if (finished[0]) return
                    finished[0] = true
                    cleanup()
                    val msg = when (error) {
                        1 -> "Camera already in use"
                        2 -> "Max cameras in use"
                        3 -> "Camera disabled"
                        4 -> "Hardware error"
                        5 -> "Service crashed"
                        else -> "Error $error"
                    }
                    onFail(msg)
                }
            }

            try {
                cameraManager.openCamera(cameraId, stateCallback, handler)
            } catch (e: Exception) {
                if (finished[0]) return
                finished[0] = true
                cleanup()
                onFail("Open failed: ${e.message}")
            }
        } catch (e: Exception) {
            onFail("Camera list error: ${e.message}")
        }
    }

    fun checkCredits(apiKey: String, onResult: (String) -> Unit) {
        scope.launch {
            // From CameraVisionManager$checkCredits$1.
            try {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/credits")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onResult("❌ HTTP ${response.code}")
                        return@launch
                    }
                    val bodyStr = response.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)
                    // RECOVERY NOTE: AudioStats.AUDIO_AMPLITUDE_NONE == 0.0 (double default).
                    val totalCredits = json.optDouble("total_credits", 0.0)
                    val totalUsage = json.optDouble("total_usage", 0.0)
                    val total = String.format("%.4f", totalCredits)
                    val used = String.format("%.4f", totalUsage)
                    val remaining = String.format("%.4f", totalCredits - totalUsage)
                    onResult(
                        """
                        💰 OpenRouter Credits:
                          Total    : ${'$'}$total
                          Used     : ${'$'}$used
                          Remaining: ${'$'}$remaining

                        ✅ Vision Model Strategy:
                          PRIMARY  → openrouter/free (auto-router, always works)
                          FALLBACK → nvidia/nemotron-nano-vl-7b-v1:free
                          FALLBACK → google/gemma-4-26b-a4b-it:free
                    """.trimIndent()
                    )
                }
            } catch (e: Exception) {
                onResult("❌ Credits error: ${e.message}")
            }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float, flipHorizontal: Boolean): Bitmap {
        if (degrees == 0.0f && !flipHorizontal) {
            return bitmap
        }
        val matrix = Matrix()
        if (degrees != 0.0f) {
            matrix.postRotate(degrees)
        }
        if (flipHorizontal) {
            matrix.postScale(-1.0f, 1.0f, bitmap.width / 2.0f, bitmap.height / 2.0f)
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, prefix: String = "NOVA_ANTITHEFT"): Uri? {
        val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put("_display_name", name)
            put("mime_type", "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put("relative_path", "DCIM/Ultron")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    Log.d(TAG, "✅ Anti-theft photo saved to gallery: $uri")
                    return uri
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to save anti-theft photo to gallery: ${e.message}")
            }
        }
        return null
    }
}
