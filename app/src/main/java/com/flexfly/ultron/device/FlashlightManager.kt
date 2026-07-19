package com.flexfly.ultron.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Torch (flashlight) control via Camera2 [CameraManager.setTorchMode].
 *
 * Recovered from `FlashlightManager.kt`. Resolves the flash-capable camera id once
 * at construction (preferring the back lens), registers a [CameraManager.TorchCallback]
 * to track real torch state, and exposes on/off/toggle.
 */
class FlashlightManager(context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String?
    private var isOn: Boolean = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) {
                isOn = enabled
                Log.d(TAG, "TorchCallback: isOn=$enabled for cameraId=$id")
            }
        }

        override fun onTorchModeUnavailable(id: String) {
            Log.w(TAG, "Torch unavailable for cameraId=$id")
        }
    }

    init {
        cameraId = findFlashCameraId()
        Log.d(TAG, "Init — cameraId=$cameraId")
        try {
            cameraManager.registerTorchCallback(torchCallback, null)
        } catch (e: Exception) {
            Log.e(TAG, "TorchCallback register failed: ${e.message}")
        }
    }

    private fun findFlashCameraId(): String? {
        return try {
            // Prefer a flash-capable BACK-facing camera.
            val backFlash = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }
            backFlash ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "findFlashCameraId error: ${e.message}")
            null
        }
    }

    fun turnOn(): Boolean {
        Log.d(TAG, "turnOn() called — cameraId=$cameraId")
        val id = cameraId
        if (id == null) {
            Log.e(TAG, "turnOn FAILED — cameraId is null!")
            return false
        }
        return try {
            cameraManager.setTorchMode(id, true)
            isOn = true
            Log.d(TAG, "✅ Torch ON success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "turnOn ERROR: ${e.message}")
            false
        }
    }

    fun turnOff(): Boolean {
        Log.d(TAG, "turnOff() called — cameraId=$cameraId")
        val id = cameraId
        if (id == null) {
            Log.e(TAG, "turnOff FAILED — cameraId is null!")
            return false
        }
        return try {
            cameraManager.setTorchMode(id, false)
            isOn = false
            Log.d(TAG, "✅ Torch OFF success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "turnOff ERROR: ${e.message}")
            false
        }
    }

    fun toggle(): Boolean {
        return if (isOn) {
            turnOff()
            false
        } else {
            turnOn()
            true
        }
    }

    val isFlashlightOn: Boolean
        get() = isOn

    fun release() {
        try {
            if (isOn) {
                turnOff()
            }
            cameraManager.unregisterTorchCallback(torchCallback)
            Log.d(TAG, "Released")
        } catch (e: Exception) {
            Log.e(TAG, "release error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FlashlightManager"
    }
}
