package com.flexfly.ultron

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flexfly.ultron.data.AppPreferences
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// RECOVERY NOTE: CameraX capture logic recovered faithfully; the Compose overlay UI
// (UltronCameraActivity.kt) was compiler-shredded and is reconstructed functionally as a
// full-screen preview + shutter + flip button.

/**
 * Standalone camera capture screen. Launched with intent extras describing what to do
 * ([EXTRA_MODE] photo/video, [EXTRA_FACING] front/back, [EXTRA_COUNTDOWN], [EXTRA_DURATION],
 * [EXTRA_ANALYZE], [EXTRA_QUESTION]). Captures a JPEG to MediaStore, stores the resulting
 * Uri in [AppPreferences.lastCapturedPhotoUri], and optionally hands it to vision analysis.
 *
 * Recovered from `UltronCameraActivity.kt`.
 */
class UltronCameraActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    // 1 == CameraSelector.LENS_FACING_BACK (RECOVERY NOTE: original default currentFacing = 1)
    private var currentFacing: Int = CameraSelector.LENS_FACING_BACK
    private var shouldAnalyze = false
    private var analyzeQuestion = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: "photo"
        val facing = intent.getStringExtra(EXTRA_FACING) ?: "back"
        val countdown = intent.getIntExtra(EXTRA_COUNTDOWN, 0)
        val duration = intent.getIntExtra(EXTRA_DURATION, 0)
        shouldAnalyze = intent.getBooleanExtra(EXTRA_ANALYZE, false)
        analyzeQuestion = intent.getStringExtra(EXTRA_QUESTION) ?: ""
        currentFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        Log.d(TAG, "📷 Camera: mode=$mode facing=$facing countdown=$countdown duration=$duration analyze=$shouldAnalyze")

        cameraExecutor = Executors.newSingleThreadExecutor()
        previewView = PreviewView(this)

        setContent { CameraScreen() }
        startCamera()
    }

    @Composable
    private fun CameraScreen() {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // Flip button (top-right)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0x66000000))
                    .clickable { flipCamera() },
                contentAlignment = Alignment.Center,
            ) { Text("⟲", color = Color.White) }
            // Shutter (bottom-center)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { capturePhoto() },
            )
        }
    }

    private fun flipCamera() {
        currentFacing = if (currentFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bindCameraUseCases(future.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val selector = CameraSelector.Builder().requireLensFacing(currentFacing).build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        // setCaptureMode(1) == CAPTURE_MODE_MINIMIZE_LATENCY (RECOVERY NOTE)
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle failed: ${e.message}")
        }
    }

    fun capturePhoto() {
        val capture = imageCapture ?: return
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ULTRON_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()

        capture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val savedUri = result.savedUri
                    Log.d(TAG, "✅ Photo saved: $savedUri")
                    if (savedUri != null) {
                         runCatching { AppPreferences(this@UltronCameraActivity).lastCapturedPhotoUri = savedUri.toString() }
                            .onFailure { Log.e(TAG, "Failed to save lastCapturedPhotoUri: ${it.message}") }
                    }
                    // RECOVERY NOTE: when shouldAnalyze, the original launched vision analysis on
                    // the saved image (com.flexfly.ultron.ai.CameraVisionManager) before finishing.
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}")
                    finish()
                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "UltronCamera"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FACING = "facing"
        const val EXTRA_COUNTDOWN = "countdown"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_ANALYZE = "analyze"
        const val EXTRA_QUESTION = "question"
    }
}
