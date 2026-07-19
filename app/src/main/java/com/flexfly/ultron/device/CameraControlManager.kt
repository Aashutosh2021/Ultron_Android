package com.flexfly.ultron.device

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flexfly.ultron.automation.UltronAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opens the system camera and drives the shutter through the accessibility service.
 *
 * Recovered from `CameraControlManager.kt`. Walks a list of known camera package names,
 * falls back to `STILL_IMAGE_CAMERA` / `IMAGE_CAPTURE` intents, then locates and taps the
 * shutter button via [UltronAccessibilityService] using four strategies: content-description
 * match, size/position heuristic, and finally a raw dispatched gesture.
 *
 * The two decompiled `$openCameraAndClickShutter$1` / `$clickShutter$1` state-machine inner
 * classes were the coroutine bodies of [openCameraAndClickShutter] and [clickShutter]'s retry;
 * they are folded back inline as `launch { delay(...) ... }` blocks.
 *
 * RECOVERY NOTE: several `performAction(16)` calls == `ACTION_CLICK`; the mangled `am`
 * constants (`0`/`1` facing) map to [CAMERA_FACING_BACK] / [CAMERA_FACING_FRONT].
 * `268435456` == `Intent.FLAG_ACTIVITY_NEW_TASK`, `65536` == `PackageManager.MATCH_DEFAULT_ONLY`.
 */
class CameraControlManager(private val context: Context) {

    private var currentFacing = 0
    private var launchedPackage: String? = null

    fun openCamera(facing: Int = currentFacing): Boolean {
        currentFacing = facing
        val isFront = facing == CAMERA_FACING_FRONT

        for (pkg in KNOWN_CAMERA_PACKAGES) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                applyFacingExtras(launchIntent, isFront)
                try {
                    context.startActivity(launchIntent)
                    launchedPackage = pkg
                    Log.d(TAG, "✅ Camera opened: $pkg [${if (isFront) "FRONT" else "BACK"}]")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed $pkg: ${e.message}")
                }
            }
        }

        return try {
            val intent = Intent("android.media.action.STILL_IMAGE_CAMERA")
            applyFacingExtras(intent, isFront)
            if (isCameraAppAvailable(intent)) {
                context.startActivity(intent)
                launchedPackage = null
                Log.d(TAG, "✅ Camera opened via STILL_IMAGE_CAMERA intent")
                true
            } else {
                openCameraFallback(isFront)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openCamera error: ${e.message}")
            openCameraFallback(isFront)
        }
    }

    fun openFrontCamera(): Boolean = openCamera(CAMERA_FACING_FRONT)

    fun openBackCamera(): Boolean = openCamera(CAMERA_FACING_BACK)

    fun switchCamera(): Boolean =
        openCamera(if (currentFacing == CAMERA_FACING_BACK) CAMERA_FACING_FRONT else CAMERA_FACING_BACK)

    fun openCameraAndClickShutter(facing: Int = currentFacing, waitMs: Long = CAMERA_LOAD_WAIT_MS) {
        if (!openCamera(facing)) {
            Log.e(TAG, "Camera open failed — shutter aborted")
            return
        }
        Log.d(TAG, "Camera opened, shutter click in ${waitMs}ms...")
        CoroutineScope(Dispatchers.Main).launch {
            delay(waitMs)
            clickShutter()
        }
    }

    fun clickShutter() {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "Accessibility service not available")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "No active window found")
            return
        }

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val clickX = screenW / 2
        val clickY = (screenH * 0.8).toInt()
        Log.d(TAG, "Screen: ${screenW}x$screenH | Target: ($clickX, $clickY)")

        logAllClickableNodes(root)

        if (tryClickByDescription(root)) {
            root.recycle()
            return
        }

        if (!tryClickBySize(root, screenW, screenH)) {
            Log.d(TAG, "Trying gesture click at ($clickX, $clickY)")
            if (performGlobalClick(service, clickX, clickY)) {
                Log.d(TAG, "✅ Gesture click done")
                root.recycle()
                return
            } else {
                Log.w(TAG, "Shutter not found — retry in ${SHUTTER_RETRY_WAIT_MS}ms")
                root.recycle()
                CoroutineScope(Dispatchers.Main).launch {
                    delay(SHUTTER_RETRY_WAIT_MS)
                    val retryRoot = service.rootInActiveWindow
                    if (retryRoot == null) {
                        Log.e(TAG, "Retry: no window")
                        return@launch
                    }
                    logAllClickableNodes(retryRoot)
                    if (!tryClickByDescription(retryRoot)) {
                        if (!tryClickBySize(retryRoot, screenW, screenH)) {
                            val clicked = performGlobalClick(service, clickX, clickY)
                            if (clicked) {
                                Log.d(TAG, "✅ Retry gesture click at ($clickX, $clickY)")
                            }
                            if (!clicked) {
                                Log.e(TAG, "❌ Shutter failed after all retries")
                            }
                        }
                    }
                    retryRoot.recycle()
                }
                return
            }
        }
        root.recycle()
    }

    fun closeCamera(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "closeCamera — HOME sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "closeCamera error: ${e.message}")
            false
        }
    }

    fun getCurrentFacing(): Int = currentFacing

    private fun tryClickByDescription(root: AccessibilityNodeInfo): Boolean {
        val node = findByDescription(root) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK) // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        Log.d(TAG, "✅ Shutter clicked via description: '${node.contentDescription}'")
        node.recycle()
        return true
    }

    private fun tryClickBySize(root: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        val node = findShutterBySize(root, screenW, screenH) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK) // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        Log.d(TAG, "✅ Shutter clicked via size: ${node.className}")
        node.recycle()
        return true
    }

    private fun findByDescription(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (desc in SHUTTER_DESCRIPTIONS) {
            val matches = root.findAccessibilityNodeInfosByText(desc)
            if (!matches.isNullOrEmpty()) {
                val clickable = matches.firstOrNull { it.isClickable }
                if (clickable != null) {
                    Log.d(TAG, "Found by desc: '$desc'")
                    return clickable
                }
            }
        }
        return null
    }

    private fun findShutterBySize(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): AccessibilityNodeInfo? {
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        collectClickableNodes(root, candidates)

        val minTop = screenH * 0.55
        val centerScreenX = screenW / 2
        val horizontalTolerance = screenW * 0.4

        val inZone = candidates.filter { (node, _) ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top >= minTop) {
                val left = (centerScreenX - horizontalTolerance).toInt()
                val right = (centerScreenX + horizontalTolerance).toInt()
                rect.centerX() in left..right
            } else {
                false
            }
        }

        return inZone.maxByOrNull { it.second }?.first
    }

    private fun collectClickableNodes(
        node: AccessibilityNodeInfo,
        list: MutableList<Pair<AccessibilityNodeInfo, Int>>,
    ) {
        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val area = rect.width() * rect.height()
            if (area > 0) {
                list.add(node to area)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickableNodes(it, list) }
        }
    }

    private fun performGlobalClick(service: UltronAccessibilityService, x: Int, y: Int): Boolean {
        return try {
            val path = Path()
            val fx = x.toFloat()
            val fy = y.toFloat()
            path.moveTo(fx, fy)
            path.lineTo(fx, fy)
            service.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
                    .build(),
                null,
                null,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gesture failed: ${e.message}")
            false
        }
    }

    private fun applyFacingExtras(intent: Intent, isFront: Boolean): Intent {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("android.intent.extras.CAMERA_FACING", if (isFront) 1 else 0)
        intent.putExtra("android.intent.extras.LENS_FACING_FRONT", if (isFront) 1 else 0)
        intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", isFront)
        return intent
    }

    private fun isCameraAppAvailable(intent: Intent): Boolean {
        // RECOVERY NOTE: 65536 == PackageManager.MATCH_DEFAULT_ONLY
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()
    }

    private fun openCameraFallback(isFront: Boolean = false): Boolean {
        return try {
            val intent = Intent("android.media.action.IMAGE_CAPTURE")
            applyFacingExtras(intent, isFront)
            context.startActivity(intent)
            Log.d(TAG, "✅ Camera fallback opened")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Camera fallback failed: ${e.message}")
            false
        }
    }

    private fun logAllClickableNodes(root: AccessibilityNodeInfo, depth: Int = 0) {
        if (root.isClickable) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            Log.d(
                TAG,
                "CLICKABLE[$depth] cls=${root.className} desc='${root.contentDescription}' bounds=$rect",
            )
        }
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                logAllClickableNodes(child, depth + 1)
                child.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "CameraControlManager"

        const val CAMERA_FACING_BACK = 0
        const val CAMERA_FACING_FRONT = 1

        private const val CAMERA_LOAD_WAIT_MS = 3000L
        private const val SHUTTER_RETRY_WAIT_MS = 1500L

        private val KNOWN_CAMERA_PACKAGES = listOf(
            "com.google.android.GoogleCamera",
            "com.android.camera2",
            "com.android.camera",
            "com.sec.android.app.camera",
            "com.huawei.camera",
            "com.oppo.camera",
            "com.vivo.camera",
            "com.oneplus.camera",
            "com.miui.camera",
            "com.asus.camera",
            "com.motorola.camera2",
            "com.lge.camera",
            "com.sonyericsson.android.camera",
            "com.nokia.camera",
        )

        private val SHUTTER_DESCRIPTIONS = listOf(
            "shutter",
            "take photo",
            "capture",
            "photo",
            "take picture",
            "shoot",
            "camera button",
            "shutter button",
            "take a photo",
            "snap",
            "capture button",
            "record",
            "फ़ोटो",
            "तस्वीर",
            "क्लिक",
        )
    }
}
