package com.flexfly.ultron.device

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.flexfly.ultron.UltronCameraActivity

/**
 * High-level camera entry point: fires up [UltronCameraActivity] with the right extras
 * for photo/video capture, optional countdown, facing, and vision analysis.
 *
 * Recovered from `Cameramanager.kt`. All callbacks are invoked synchronously right
 * after the activity is launched (they report "launched", not "captured").
 */
object CameraManager {

    private const val TAG = "CameraManager"
    const val CAMERA_FACING_BACK = 0
    const val CAMERA_FACING_FRONT = 1

    fun hasPermissions(context: Context): Boolean {
        val camera = ContextCompat.checkSelfPermission(context, "android.permission.CAMERA") == 0
        val audio = ContextCompat.checkSelfPermission(context, "android.permission.RECORD_AUDIO") == 0
        val storage = if (Build.VERSION.SDK_INT < 33) {
            ContextCompat.checkSelfPermission(context, "android.permission.WRITE_EXTERNAL_STORAGE") == 0
        } else {
            ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_IMAGES") == 0 &&
                ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VIDEO") == 0
        }
        val total = camera && audio && storage
        Log.d(TAG, "📸 Permissions: Camera=$camera Audio=$audio Storage=$storage Total=$total")
        return total
    }

    fun takePhotoWithCountdown(context: Context, seconds: Int, facing: Int = 0, onResult: (Boolean) -> Unit) {
        Log.d(TAG, "📸 Photo: countdown=${seconds}s facing=$facing")
        if (!hasPermissions(context)) {
            Log.e(TAG, "❌ Permissions not granted")
            onResult(false)
            return
        }
        val intent = Intent(context, UltronCameraActivity::class.java)
        intent.putExtra(UltronCameraActivity.EXTRA_MODE, "photo")
        intent.putExtra(UltronCameraActivity.EXTRA_FACING, if (facing == 1) "front" else "back")
        intent.putExtra(UltronCameraActivity.EXTRA_COUNTDOWN, seconds)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onResult(true)
    }

    fun takePhoto(context: Context, facing: Int = 0, onResult: (Boolean) -> Unit) {
        Log.d(TAG, "📸 Photo: instant facing=$facing")
        if (!hasPermissions(context)) {
            Log.e(TAG, "❌ Permissions not granted")
            onResult(false)
            return
        }
        val intent = Intent(context, UltronCameraActivity::class.java)
        intent.putExtra(UltronCameraActivity.EXTRA_MODE, "photo")
        intent.putExtra(UltronCameraActivity.EXTRA_FACING, if (facing == 1) "front" else "back")
        intent.putExtra(UltronCameraActivity.EXTRA_COUNTDOWN, 0)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onResult(true)
    }

    fun takePhotoAndAnalyze(context: Context, facing: Int = 0, question: String = "", onResult: (Boolean) -> Unit) {
        Log.d(TAG, "📸 Photo and analyze: facing=$facing question=$question")
        if (!hasPermissions(context)) {
            Log.e(TAG, "❌ Permissions not granted")
            onResult(false)
            return
        }
        val intent = Intent(context, UltronCameraActivity::class.java)
        intent.putExtra(UltronCameraActivity.EXTRA_MODE, "photo")
        intent.putExtra(UltronCameraActivity.EXTRA_FACING, if (facing == 1) "front" else "back")
        intent.putExtra(UltronCameraActivity.EXTRA_COUNTDOWN, 0)
        intent.putExtra(UltronCameraActivity.EXTRA_ANALYZE, true)
        intent.putExtra(UltronCameraActivity.EXTRA_QUESTION, question)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onResult(true)
    }

    fun recordVideo(context: Context, durationSeconds: Int, facing: Int = 0, onResult: (Boolean, String) -> Unit) {
        Log.d(TAG, "🎥 Video: duration=${durationSeconds}s facing=$facing")
        if (!hasPermissions(context)) {
            Log.e(TAG, "❌ Permissions not granted")
            onResult(false, "Camera permission nahi mili Boss!")
            return
        }
        val intent = Intent(context, UltronCameraActivity::class.java)
        intent.putExtra(UltronCameraActivity.EXTRA_MODE, "video")
        intent.putExtra(UltronCameraActivity.EXTRA_FACING, if (facing == 1) "front" else "back")
        intent.putExtra(UltronCameraActivity.EXTRA_COUNTDOWN, 0)
        intent.putExtra("duration", durationSeconds)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onResult(true, "Recording ${durationSeconds}s video...")
    }

    fun recordVideoWithCountdown(context: Context, countdownSeconds: Int, durationSeconds: Int, facing: Int = 0, onResult: (Boolean, String) -> Unit) {
        Log.d(TAG, "🎥 Video: countdown=${countdownSeconds}s duration=${durationSeconds}s facing=$facing")
        if (!hasPermissions(context)) {
            Log.e(TAG, "❌ Permissions not granted")
            onResult(false, "Camera permission nahi mili Boss!")
            return
        }
        val intent = Intent(context, UltronCameraActivity::class.java)
        intent.putExtra(UltronCameraActivity.EXTRA_MODE, "video")
        intent.putExtra(UltronCameraActivity.EXTRA_FACING, if (facing == 1) "front" else "back")
        intent.putExtra(UltronCameraActivity.EXTRA_COUNTDOWN, countdownSeconds)
        intent.putExtra("duration", durationSeconds)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onResult(true, "Starting video recording...")
    }

    fun recordVideoManual(context: Context, facing: Int = 0, onResult: (Boolean, String) -> Unit) {
        Log.d(TAG, "🎥 Video: manual stop facing=$facing")
        if (!hasPermissions(context)) {
            Log.e(TAG, "❌ Permissions not granted")
            onResult(false, "Camera permission nahi mili Boss!")
            return
        }
        val intent = Intent(context, UltronCameraActivity::class.java)
        intent.putExtra(UltronCameraActivity.EXTRA_MODE, "video")
        intent.putExtra(UltronCameraActivity.EXTRA_FACING, if (facing == 1) "front" else "back")
        intent.putExtra(UltronCameraActivity.EXTRA_COUNTDOWN, 0)
        intent.putExtra("duration", 0)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onResult(true, "Recording started. Tap to stop.")
    }

    fun openCamera(context: Context) {
        takePhoto(context, 0) { }
    }
}
