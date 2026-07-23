package com.flexfly.ultron.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.flexfly.ultron.apps.YouTubeManager
import com.flexfly.ultron.service.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Core [AccessibilityService]: UI automation, gestures, screenshot, auto-click,
 * node search. Referenced app-wide via [Companion.getInstance].
 *
 * Recovered from `UltronAccessibilityService.kt`. The `doXxx$1` `SuspendLambda` inner
 * classes were the state machines of `scope.launch { }` retry loops and are folded
 * back inline. `gestureAllPositions` / `waitForWindow` were `suspend fun`s (their
 * `$1` `ContinuationImpl` holders carried no extra logic). `$$ExternalSyntheticLambda`
 * runnables/functions are inlined.
 *
 * RECOVERY NOTES for decompiler-substituted constants:
 *  - `performAction(16)`      -> [AccessibilityNodeInfo.ACTION_CLICK]
 *  - `performAction(2097152)` -> [AccessibilityNodeInfo.ACTION_SET_TEXT]
 *  - `performAction(256, …)`  -> [AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY]
 *  - `performAction(1048580)` -> kept as raw literal (IME-enter action id `0x100004`);
 *    the standard ACTION_IME_ENTER id is `0x100000`, but the original used `1048580`,
 *    so it is preserved verbatim to keep behavior identical.
 *  - `findFocus(1)`           -> [AccessibilityNodeInfo.FOCUS_INPUT]
 *  - service `eventTypes 2080` = WINDOW_STATE_CHANGED | WINDOW_CONTENT_CHANGED
 *  - service `flags 66`        = FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS
 *  - service `feedbackType 16` = FEEDBACK_GENERIC
 *
 * Cross-file refs (verified):
 *  - [WakeWordService] (com.flexfly.ultron.service) — companion `getActiveInstance()`,
 *    `injectReminderMessage(String)`, `ghostPointerCoordinates: MutableState<PointF?>`.
 *  - [YouTubeManager] (com.flexfly.ultron.apps) — `skipAdsAndPopups(root)`.
 */
class UltronAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "✅ Accessibility Service connected")
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg != "com.google.android.youtube") return
            val root = rootInActiveWindow ?: return
            YouTubeManager.skipAdsAndPopups(root)
            root.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent for YouTube: ${e.message}")
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }

    private fun checkLockAndProceed(
        commandName: String,
        onResult: ((Boolean) -> Unit)? = null,
        action: () -> Unit,
    ): Boolean {
        if (isPhoneLocked()) {
            notifyUnlockRequired(commandName)
            onResult?.invoke(false)
            return false
        }
        action()
        return true
    }

    fun clickShutterWithRetry(onResult: ((Boolean) -> Unit)? = null) {
        checkLockAndProceed("Camera Shutter", onResult) {
            doClickShutterWithRetry(onResult)
        }
    }

    private fun doClickShutterWithRetry(onResult: ((Boolean) -> Unit)?) {
        scope.launch {
            var attempt = 0
            var success = false
            while (attempt < MAX_RETRIES && !success) {
                attempt++
                Log.d(TAG, "🔄 Attempt $attempt/10")
                success = tryClickShutter()
                if (!success) {
                    delay(RETRY_DELAY_MS)
                }
            }
            if (!success) {
                Log.w(TAG, "⚠️ Node nahi mila — gesture fallback")
                success = gestureAllPositions()
            }
            Log.d(TAG, if (success) "✅ Done" else "❌ All failed")
            onResult?.invoke(success)
        }
    }

    fun clickShutterButton(): Boolean {
        if (isPhoneLocked()) {
            notifyUnlockRequired("Camera Shutter")
            return false
        }
        return tryClickShutter()
    }

    fun flipCamera(onResult: ((Boolean) -> Unit)? = null) {
        checkLockAndProceed("Flip Camera", onResult) {
            doFlipCamera(onResult)
        }
    }

    private fun doFlipCamera(onResult: ((Boolean) -> Unit)?) {
        scope.launch {
            var attempt = 0
            var success = false
            while (attempt < MAX_RETRIES && !success) {
                attempt++
                Log.d(TAG, "🔄 Flip attempt $attempt/10")
                success = tryFlipByViewId()
                if (!success) {
                    delay(RETRY_DELAY_MS)
                }
            }
            Log.d(TAG, if (success) "✅ Camera flipped" else "❌ Flip failed")
            onResult?.invoke(success)
        }
    }

    fun searchInSpotify(songName: String, onResult: ((Boolean) -> Unit)? = null) {
        checkLockAndProceed("Spotify Search", onResult) {
            doSearchInSpotify(songName, onResult)
        }
    }

    private fun doSearchInSpotify(songName: String, onResult: ((Boolean) -> Unit)?) {
        scope.launch {
            var attempt = 0
            var success = false
            while (attempt < 8 && !success) {
                attempt++
                Log.d(TAG, "🔍 Spotify search attempt $attempt")
                success = tryTypeInSpotifySearch(songName)
                if (!success) {
                    delay(700L)
                }
            }
            if (!success) {
                Log.w(TAG, "⚠️ Search bar nahi mila")
                onResult?.invoke(false)
                return@launch
            }
            delay(4000L)
            clickFirstSpotifyResult { clicked ->
                onResult?.invoke(clicked)
            }
        }
    }

    fun clickFirstSpotifyResult(onResult: ((Boolean) -> Unit)? = null) {
        checkLockAndProceed("Spotify Result", onResult) {
            doClickFirstSpotifyResult(onResult)
        }
    }

    private fun doClickFirstSpotifyResult(onResult: ((Boolean) -> Unit)?) {
        scope.launch {
            var attempt = 0
            var success = false
            while (attempt < 6 && !success) {
                attempt++
                Log.d(TAG, "🎵 First result attempt $attempt")
                success = tryClickFirstResult()
                if (!success) {
                    delay(800L)
                }
            }
            Log.d(TAG, if (success) "✅ First result clicked" else "❌ First result nahi mila")
            onResult?.invoke(success)
        }
    }

    fun clickSpotifyPlayPause() {
        checkLockAndProceed("Spotify Play/Pause", null) {
            doClickSpotifyPlayPause()
        }
    }

    private fun doClickSpotifyPlayPause() {
        scope.launch {
            var attempt = 0
            var success = false
            while (attempt < 6 && !success) {
                attempt++
                Log.d(TAG, "⏯️ Play/pause attempt $attempt")
                success = tryClickPlayPause()
                if (!success) {
                    delay(700L)
                }
            }
            Log.d(TAG, if (success) "✅ Play/pause clicked" else "❌ Play/pause nahi mila")
        }
    }

    fun searchInChrome(query: String, onResult: ((Boolean) -> Unit)? = null) {
        checkLockAndProceed("Chrome Search", onResult) {
            doSearchInChrome(query, onResult)
        }
    }

    private fun doSearchInChrome(query: String, onResult: ((Boolean) -> Unit)?) {
        scope.launch {
            var attempt = 0
            var success = false
            while (attempt < MAX_RETRIES && !success) {
                attempt++
                Log.d(TAG, "🌐 Chrome search attempt $attempt")
                success = tryTypeInChromeBar(query)
                if (!success) {
                    delay(RETRY_DELAY_MS)
                }
            }
            if (success) {
                delay(300L)
                pressEnterOnFocusedNode()
            }
            Log.d(TAG, if (success) "✅ Chrome search done" else "❌ Chrome bar nahi mila")
            onResult?.invoke(success)
        }
    }

    fun performTapGesture(x: Float, y: Float): Boolean {
        if (isPhoneLocked()) {
            notifyUnlockRequired("Tap Gesture")
            return false
        }
        return tapGestureAt(x, y)
    }

    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (isPhoneLocked()) {
            notifyUnlockRequired("Screen Capture")
            onResult(null)
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                takeScreenshot(
                    0,
                    ContextCompat.getMainExecutor(this),
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            onResult(copy)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e("UltronAccessibilityService", "Screenshot failed with error code: $errorCode")
                            onResult(null)
                        }
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during takeScreenshot: ${e.message}", e)
                onResult(null)
            }
            return
        }
        Log.e(TAG, "Screenshot not supported on this Android version (< Android 11)")
        onResult(null)
    }

    private fun tryFlipByViewId(): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "No window yet")
            return false
        }
        for (id in FLIP_CAMERA_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                // RECOVERY NOTE: performAction(16) == ACTION_CLICK
                val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, (if (performed) "✅ Flip S1 ID: " else "❌ Flip S1 failed: ") + id)
                node.recycle()
                root.recycle()
                return performed
            }
        }
        val descNode = findNodeByDescWithFilter(root, FLIP_KEYWORDS, FLIP_EXCLUDE_KEYWORDS)
        if (descNode != null) {
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = descNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, if (performed) "✅ Flip S2: '${descNode.contentDescription}'" else "❌ Flip S2 failed")
            descNode.recycle()
            root.recycle()
            return performed
        }
        root.recycle()
        return false
    }

    private fun tryClickShutter(): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "No window yet")
            return false
        }
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        logClickableNodes(root)
        findByIdTraverse(root)?.let { node ->
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "✅ S1 ID: '${node.viewIdResourceName}' clicked=$performed")
            safeRecycle(node)
            root.recycle()
            return performed
        }
        findByDescTraverse(root)?.let { node ->
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "✅ S2 Desc: '${node.contentDescription}' clicked=$performed")
            safeRecycle(node)
            root.recycle()
            return performed
        }
        findShutterByHeuristic(root, screenW, screenH)?.let { node ->
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "✅ S3 Heuristic: '${node.className}' clicked=$performed")
            safeRecycle(node)
            root.recycle()
            return performed
        }
        root.recycle()
        return false
    }

    private fun findByIdTraverse(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            for (keyword in SHUTTER_ID_KEYWORDS) {
                if (id.contains(keyword)) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findByIdTraverse(child)
                if (found != null) return found
                safeRecycle(child)
            }
        }
        return null
    }

    private fun findByDescTraverse(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) {
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val combined = "$desc $text"
            var excluded = false
            for (keyword in SHUTTER_EXCLUDE) {
                if (combined.contains(keyword)) {
                    excluded = true
                    break
                }
            }
            if (!excluded) {
                for (keyword in SHUTTER_DESC_KEYWORDS) {
                    if (combined.contains(keyword)) {
                        Log.d(TAG, "🎯 Shutter candidate: '$combined'")
                        return node
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findByDescTraverse(child)
                if (found != null) return found
                safeRecycle(child)
            }
        }
        return null
    }

    private fun findNodeByDescWithFilter(
        node: AccessibilityNodeInfo,
        include: List<String>,
        exclude: List<String>,
    ): AccessibilityNodeInfo? {
        if (node.isClickable) {
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val combined = "$desc $text"
            for (keyword in include) {
                if (combined.contains(keyword)) {
                    var excluded = false
                    for (ex in exclude) {
                        if (combined.contains(ex)) {
                            excluded = true
                            break
                        }
                    }
                    if (!excluded) {
                        return node
                    }
                    break
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByDescWithFilter(child, include, exclude)
                if (found != null) return found
                safeRecycle(child)
            }
        }
        return null
    }

    private fun findShutterByHeuristic(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): AccessibilityNodeInfo? {
        val clickables = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        collectClickable(root, clickables)
        val centerXScreen = screenW / 2
        val horizontalTolerance = (0.35 * screenW).toInt()
        val bottomThreshold = screenH * 0.7
        val minSize = (screenW * 0.1).toInt()

        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (pair in clickables) {
            val node = pair.first
            val rect = pair.second
            val inBottom = rect.top >= bottomThreshold
            val left = centerXScreen - horizontalTolerance
            val right = centerXScreen + horizontalTolerance
            val cx = rect.centerX()
            val horizontallyCentered = cx in left..right
            val ratio = if (rect.height() > 0) rect.width().toFloat() / rect.height() else 0.0f
            val squarish = ratio in 0.6f..1.8f
            val bigEnough = rect.width() >= minSize && rect.height() >= minSize
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            var notExcluded = true
            for (keyword in SHUTTER_EXCLUDE) {
                if (desc.contains(keyword)) {
                    notExcluded = false
                    break
                }
            }
            if (inBottom && horizontallyCentered && squarish && bigEnough && notExcluded) {
                candidates.add(pair)
            }
        }
        val best = candidates.maxByOrNull { it.second.width() * it.second.height() }
        return best?.first
    }

    private fun collectClickable(
        node: AccessibilityNodeInfo,
        list: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
    ) {
        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                list.add(node to rect)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectClickable(child, list)
            }
        }
    }

    private suspend fun gestureAllPositions(): Boolean {
        val metrics = resources.displayMetrics
        val tapX = metrics.widthPixels / 2.0f
        for (yPercent in GESTURE_Y_POSITIONS) {
            val tapY = metrics.heightPixels * yPercent
            Log.d(TAG, "👆 Gesture at ($tapX, $tapY)")
            val ok = tapGestureAt(tapX, tapY)
            if (ok) {
                return true
            }
            delay(300L)
        }
        return false
    }

    private fun tapGestureAt(x: Float, y: Float): Boolean {
        Handler(Looper.getMainLooper()).post {
            WakeWordService.ghostPointerCoordinates.value = PointF(x, y)
        }
        return try {
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
                    .build(),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gesture error: ${e.message}")
            false
        }
    }

    private fun tryTypeInSpotifySearch(songName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val searchBar = findSpotifySearchBar(root)
        if (searchBar == null) {
            root.recycle()
            return false
        }
        // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        searchBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val bundle = Bundle()
        bundle.putString(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, songName)
        // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
        val performed = searchBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        Log.d(TAG, if (performed) "✅ Typed: '$songName'" else "❌ Type failed")
        safeRecycle(searchBar)
        root.recycle()
        return performed
    }

    private fun findSpotifySearchBar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        val hint = node.hintText?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val isEditText = className.contains("EditText")
        val looksLikeSearch = hint.contains("search") || desc.contains("search") ||
            text.contains("search") || hint.contains("artist") ||
            hint.contains("song") || hint.contains("podcast")
        if (isEditText || looksLikeSearch) {
            Log.d(TAG, "🎯 Spotify search bar found: hint='$hint' desc='$desc'")
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findSpotifySearchBar(child)
                if (found != null) return found
                safeRecycle(child)
            }
        }
        return null
    }

    private fun tryClickFirstResult(): Boolean {
        val root = rootInActiveWindow ?: return false
        val metrics = resources.displayMetrics
        val clickables = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        collectClickable(root, clickables)
        val bottomLimit = metrics.heightPixels * 0.88f
        var match: Pair<AccessibilityNodeInfo, Rect>? = null
        for (pair in clickables) {
            val node = pair.first
            val rect = pair.second
            val className = node.className?.toString() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val isViewGroup = className.contains("ViewGroup")
            val belowHeader = rect.top >= 365
            val leftEdge = rect.left <= 10
            val aboveBottom = rect.top < bottomLimit
            val isNowPlaying = desc == "now playing bar"
            val isCancel = desc == "cancel"
            val rowHeightOk = rect.height() in 100 until 251
            if (isViewGroup && belowHeader && leftEdge && aboveBottom && !isNowPlaying && !isCancel && rowHeightOk) {
                match = pair
                break
            }
        }
        if (match != null) {
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = match.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "🎵 Clicking first song row: bounds=${match.second} clicked=$performed")
            root.recycle()
            return performed
        }
        root.recycle()
        Log.w(TAG, "⚠️ Gesture fallback on first song position")
        return tapGestureAt(metrics.widthPixels * 0.4f, 457.0f)
    }

    private fun tryClickPlayPause(): Boolean {
        val root = rootInActiveWindow ?: return false
        val metrics = resources.displayMetrics
        val clickables = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        collectClickable(root, clickables)

        var exactMatch: Pair<AccessibilityNodeInfo, Rect>? = null
        for (pair in clickables) {
            val node = pair.first
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
            val className = node.className?.toString() ?: ""
            if (desc == "play" || desc == "pause" || desc == "play/pause") {
                if (className.contains("ImageButton") || className.contains("Button") || className.contains("View")) {
                    exactMatch = pair
                    break
                }
            }
        }
        if (exactMatch != null) {
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = exactMatch.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, if (performed) "✅ Play/pause exact match: '${exactMatch.first.contentDescription}'" else "❌ Exact match click failed")
            root.recycle()
            return performed
        }

        var positionalMatch: Pair<AccessibilityNodeInfo, Rect>? = null
        for (pair in clickables) {
            val node = pair.first
            val rect = pair.second
            val className = node.className?.toString() ?: ""
            val isButton = className.contains("ImageButton") || className.contains("Button")
            val nearBottom = rect.top > metrics.heightPixels * 0.88f
            val nearRight = rect.left > metrics.widthPixels * 0.85f
            if (isButton && nearBottom && nearRight) {
                positionalMatch = pair
                break
            }
        }
        if (positionalMatch != null) {
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = positionalMatch.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "✅ Play/pause S2 bottom-right: clicked=$performed")
            root.recycle()
            return performed
        }
        root.recycle()
        Log.w(TAG, "⚠️ Gesture fallback on play button position")
        return tapGestureAt(981.0f, 2111.0f)
    }

    private fun tryTypeInChromeBar(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val addressBar = findChromeAddressBar(root)
        if (addressBar == null) {
            root.recycle()
            return false
        }
        // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        addressBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val clearBundle = Bundle()
        clearBundle.putString(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
        addressBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
        val queryBundle = Bundle()
        queryBundle.putString(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
        val performed = addressBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, queryBundle)
        Log.d(TAG, if (performed) "✅ Chrome bar mein type kiya: '$query'" else "❌ Type nahi hua")
        safeRecycle(addressBar)
        root.recycle()
        return performed
    }

    private fun findChromeAddressBar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val className = node.className?.toString() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val hint = node.hintText?.toString()?.lowercase(Locale.ROOT) ?: ""
        val idMatches = id.contains("url_bar") || id.contains("search_box") ||
            id.contains("location_bar") || id.contains("omnibox") || id.contains("address_bar")
        val isEditText = className.contains("EditText")
        val hintOrDescMatches = hint.contains("search") || hint.contains("url") ||
            hint.contains("address") || desc.contains("search") ||
            desc.contains("address") || desc.contains("url")
        if (idMatches || (isEditText && hintOrDescMatches)) {
            Log.d(TAG, "🎯 Chrome bar found: id='$id' hint='$hint' desc='$desc'")
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findChromeAddressBar(child)
                if (found != null) return found
                safeRecycle(child)
            }
        }
        return null
    }

    fun pressEnterOnFocusedNode() {
        val root = rootInActiveWindow ?: return
        // RECOVERY NOTE: findFocus(1) == FOCUS_INPUT
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null) {
            val bundle = Bundle()
            bundle.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 4)
            // RECOVERY NOTE: performAction(256, …) == ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            focus.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, bundle)
            KeyEvent(0, 66)
            // RECOVERY NOTE: 1048580 (0x100004) is the IME-enter action id used by the
            // original; preserved verbatim (standard ACTION_IME_ENTER id is 0x100000).
            focus.performAction(1048580)
            safeRecycle(focus)
        }
        root.recycle()
    }

    suspend fun waitForWindow(packageName: String, timeoutMs: Long = 3000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString() ?: ""
                root.recycle()
                if (pkg.contains(packageName)) {
                    return true
                }
            }
            delay(200L)
        }
        return false
    }

    private fun safeRecycle(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (ignored: Exception) {
        }
    }

    private fun logClickableNodes(node: AccessibilityNodeInfo, depth: Int = 0) {
        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val indent = "  ".repeat(depth)
            Log.d(
                TAG,
                "$indent[NODE] id='${node.viewIdResourceName}' desc='${node.contentDescription}' " +
                    "cls=${node.className} bounds=$rect ${rect.width()}x${rect.height()}",
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                logClickableNodes(child, depth + 1)
                safeRecycle(child)
            }
        }
    }

    companion object {
        private const val TAG = "UltronAccessibilityService"
        private const val MAX_RETRIES = 10
        private const val RETRY_DELAY_MS = 600L

        private var instance: UltronAccessibilityService? = null

        @JvmStatic
        fun getInstance(): UltronAccessibilityService? = instance

        /**
         * Whether the user has enabled Ultron's accessibility service in system
         * settings — the authoritative signal, independent of whether the service
         * instance is currently bound. [getInstance] can be null for a moment while
         * Android tears down and rebinds the service (routine on OEM battery
         * killers), so callers must not treat a null instance as "not enabled."
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expectedClass = UltronAccessibilityService::class.java.name

            try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
                val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                for (service in services) {
                    if (service.resolveInfo.serviceInfo.packageName == context.packageName) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking AccessibilityManager", e)
            }

            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            return enabled.contains(context.packageName) ||
                enabled.contains(expectedClass) ||
                enabled.contains("UltronAccessibilityService") ||
                enabled.contains("NovaAccessibilityService") ||
                enabled.contains("MJAccessibilityService")
        }

        fun isPhoneLocked(context: Context): Boolean {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return keyguard.isKeyguardLocked
        }

        fun isPhoneLocked(): Boolean {
            val appContext = getInstance()?.applicationContext ?: return false
            val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return keyguard.isKeyguardLocked
        }

        fun notifyUnlockRequired(toolName: String) {
            val message = "Phone unlock karo Boss! Lock hai, '$toolName' execute nahi kar sakti."
            Handler(Looper.getMainLooper()).post {
                WakeWordService.getActiveInstance()?.injectReminderMessage(message)
            }
            Log.d(TAG, "🔒 Locked: Cannot execute '$toolName'")
        }

        private val SHUTTER_ID_KEYWORDS = listOf(
            "shutter", "capture", "photo_button", "btn_shutter", "camera_button",
            "take_photo", "shoot", "snap", "photo_video_button", "shutter_button",
            "shot_button", "btn_photo", "camera_shutter", "capture_button",
            "take_picture", "shutter_btn", "photo_btn", "captureButton",
            "camera_shutter_button",
        )

        private val SHUTTER_DESC_KEYWORDS = listOf(
            "shutter\" button", "shutter button", "take photo", "take a photo",
            "capture", "take picture", "shoot", "snap", "click photo",
            "double-tap to take",
        )

        private val SHUTTER_EXCLUDE = listOf(
            "interval", "view photo", "view video", "gallery", "album", "flash",
            "settings", "timer", "filter", "zoom", "effect", "hdr", "aspect",
            "hi-res", "dazzle", "subset", "latest video", "rear camera",
            "front camera", "filters", "pop up",
        )

        private val FLIP_KEYWORDS = listOf(
            "switch camera", "flip camera", "toggle camera", "switch to front",
            "switch to rear", "switchfront", "switchrear", "rear camera",
            "front camera",
        )

        private val FLIP_EXCLUDE_KEYWORDS = listOf(
            "shutter", "capture", "flash", "gallery", "zoom", "take photo",
        )

        private val FLIP_CAMERA_VIEW_IDS = listOf(
            "com.oplus.camera:id/switch_camera_button",
            "com.oneplus.camera:id/switch_camera_button",
            "com.google.android.GoogleCamera:id/photo_video_button",
            "com.google.android.GoogleCamera:id/switch_camera",
            "com.sec.android.app.camera:id/switch_button",
            "com.sec.android.app.camera:id/btn_switching",
            "com.miui.camera:id/switch_camera_button",
            "com.miui.camera:id/btn_switch_camera",
            "com.vivo.camera:id/switch_camera_button",
            "com.huawei.camera:id/switch_camera_button",
            "com.motorola.camera2:id/switch_camera_button",
            "com.sonyericsson.android.camera:id/switch_camera",
            "com.asus.camera:id/switch_camera_button",
            "com.android.camera2:id/camera_toggle_button",
            "com.android.camera:id/camera_toggle_button",
        )

        private val GESTURE_Y_POSITIONS = listOf(0.85f, 0.8f, 0.88f, 0.75f)
    }
}
