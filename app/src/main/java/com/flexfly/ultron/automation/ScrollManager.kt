package com.flexfly.ultron.automation

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Scrolls/swipes the foreground app, preferring accessibility scroll actions and
 * falling back to dispatched gesture strokes. TikTok/Reels-style apps always use a
 * curved full-screen swipe.
 *
 * Recovered from `ScrollManager.kt`. The recursive `performScroll` / `doNext`
 * `Ref.IntRef`-driven helpers are restored as local functions capturing mutable
 * counters; `$$ExternalSyntheticLambda` callbacks are inlined.
 *
 * RECOVERY NOTE: `performAction(4096)` == ACTION_SCROLL_FORWARD,
 * `performAction(8192)` == ACTION_SCROLL_BACKWARD.
 */
object ScrollManager {

    private const val TAG = "ScrollManager"

    private val ALWAYS_SWIPE_APPS = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
    )

    fun scrollDown(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        val currentPackage = getCurrentPackage()
        Log.d(TAG, "📱 Current package: $currentPackage")
        if (ALWAYS_SWIPE_APPS.contains(currentPackage)) {
            doTikTokSwipe(true, onResult)
        } else {
            doNormalAppScroll(true, onResult)
        }
    }

    fun scrollUp(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        if (ALWAYS_SWIPE_APPS.contains(getCurrentPackage())) {
            doTikTokSwipe(false, onResult)
        } else {
            doNormalAppScroll(false, onResult)
        }
    }

    fun scrollDownTimes(context: Context, times: Int, onResult: ((Boolean) -> Unit)? = null) {
        val count = times.coerceIn(1, 10)
        if (ALWAYS_SWIPE_APPS.contains(getCurrentPackage())) {
            repeatTikTokSwipe(true, count, onResult)
        } else {
            repeatNormalScroll(true, count, onResult)
        }
    }

    fun scrollUpTimes(context: Context, times: Int, onResult: ((Boolean) -> Unit)? = null) {
        val count = times.coerceIn(1, 10)
        if (ALWAYS_SWIPE_APPS.contains(getCurrentPackage())) {
            repeatTikTokSwipe(false, count, onResult)
        } else {
            repeatNormalScroll(false, count, onResult)
        }
    }

    fun scrollToBottom(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        val currentPackage = getCurrentPackage()
        Log.d(TAG, "📱 Scroll to bottom: $currentPackage")
        val handler = Handler(Looper.getMainLooper())
        val maxAttempts = 40
        var attempt = 0
        var lastScrollY = -1

        fun performScroll() {
            attempt++
            if (attempt > maxAttempts) {
                Log.d(TAG, "📱 Reached max attempts ($maxAttempts), stopping")
                onResult?.invoke(true)
                return
            }
            val currentScrollY = getCurrentScrollY()
            if (currentScrollY == lastScrollY && lastScrollY != -1) {
                Log.d(TAG, "📱 Scroll position not changing, reached bottom")
                onResult?.invoke(true)
                return
            }
            lastScrollY = currentScrollY
            Log.d(TAG, "📱 Scroll to bottom attempt $attempt/$maxAttempts, scrollY=$currentScrollY")
            if (ALWAYS_SWIPE_APPS.contains(currentPackage)) {
                doTikTokSwipe(true) { handler.postDelayed({ performScroll() }, 350L) }
            } else if (tryActionScroll(true)) {
                handler.postDelayed({ performScroll() }, 250L)
            } else {
                tryNormalGestureScroll(true)
                handler.postDelayed({ performScroll() }, 400L)
            }
        }
        performScroll()
    }

    fun scrollToTop(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        val currentPackage = getCurrentPackage()
        Log.d(TAG, "📱 Scroll to top: $currentPackage")
        val handler = Handler(Looper.getMainLooper())
        val maxAttempts = 40
        var attempt = 0
        var lastScrollY = -1

        fun performScroll() {
            attempt++
            if (attempt > maxAttempts) {
                Log.d(TAG, "📱 Reached max attempts ($maxAttempts), stopping")
                onResult?.invoke(true)
                return
            }
            val currentScrollY = getCurrentScrollY()
            if (currentScrollY <= 0) {
                Log.d(TAG, "📱 Already at top, scrollY=$currentScrollY")
                onResult?.invoke(true)
                return
            }
            if (currentScrollY == lastScrollY && lastScrollY != -1) {
                Log.d(TAG, "📱 Scroll position not changing, reached top")
                onResult?.invoke(true)
                return
            }
            lastScrollY = currentScrollY
            Log.d(TAG, "📱 Scroll to top attempt $attempt/$maxAttempts, scrollY=$currentScrollY")
            if (ALWAYS_SWIPE_APPS.contains(currentPackage)) {
                doTikTokSwipe(false) { handler.postDelayed({ performScroll() }, 350L) }
            } else if (tryActionScroll(false)) {
                handler.postDelayed({ performScroll() }, 250L)
            } else {
                tryNormalGestureScroll(false)
                handler.postDelayed({ performScroll() }, 400L)
            }
        }
        performScroll()
    }

    private fun performSwipeGesture(startYPercent: Float, endYPercent: Float, durationMs: Long): Boolean {
        val service = UltronAccessibilityService.getInstance() ?: return false
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2.0f
        val height = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(x, height * startYPercent)
            lineTo(x, height * endYPercent)
        }
        val dispatched = service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            null,
            null,
        )
        Log.d(TAG, "👆 Swipe gesture from $startYPercent% to $endYPercent% (duration=${durationMs}ms): $dispatched")
        return dispatched
    }

    private fun doTikTokSwipe(forward: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            onResult?.invoke(false)
            return
        }
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2.0f
        val height = metrics.heightPixels.toFloat()
        val startY = if (forward) height * 0.75f else height * 0.25f
        val endY = if (forward) height * 0.25f else height * 0.75f
        val midY = (startY + endY) / 2.0f
        val controlX = if (forward) x + 10.0f else x - 10.0f
        val path = Path().apply {
            moveTo(x, startY)
            quadTo(controlX, midY, x, endY)
        }
        val dispatched = service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 450L))
                .build(),
            null,
            null,
        )
        Log.d(TAG, "🎬 TikTok swipe ${if (forward) "DOWN" else "UP"}: $dispatched")
        Handler(Looper.getMainLooper()).postDelayed({ onResult?.invoke(dispatched) }, 550L)
    }

    private fun repeatTikTokSwipe(forward: Boolean, times: Int, onResult: ((Boolean) -> Unit)?) {
        val handler = Handler(Looper.getMainLooper())
        var count = 0

        fun doNext() {
            if (count < times) {
                doTikTokSwipe(forward) {
                    count++
                    handler.postDelayed({ doNext() }, 600L)
                }
            } else {
                onResult?.invoke(true)
            }
        }
        doNext()
    }

    private fun doNormalAppScroll(forward: Boolean, onResult: ((Boolean) -> Unit)?) {
        if (!tryActionScroll(forward)) {
            tryNormalGestureScroll(forward)
            Handler(Looper.getMainLooper()).postDelayed({ onResult?.invoke(true) }, 400L)
        } else {
            onResult?.invoke(true)
        }
    }

    private fun repeatNormalScroll(forward: Boolean, times: Int, onResult: ((Boolean) -> Unit)?) {
        val handler = Handler(Looper.getMainLooper())
        var count = 0

        fun doNext() {
            if (count < times) {
                doNormalAppScroll(forward) {
                    count++
                    handler.postDelayed({ doNext() }, 400L)
                }
            } else {
                onResult?.invoke(true)
            }
        }
        doNext()
    }

    private fun tryActionScroll(forward: Boolean): Boolean {
        val service = UltronAccessibilityService.getInstance() ?: return false
        val root = service.rootInActiveWindow ?: return false
        // RECOVERY NOTE: 4096 == ACTION_SCROLL_FORWARD, 8192 == ACTION_SCROLL_BACKWARD
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val scrollable = findScrollableNode(root)
        val performed = scrollable?.performAction(action) ?: false
        scrollable?.recycle()
        root.recycle()
        return performed
    }

    private fun tryNormalGestureScroll(forward: Boolean): Boolean {
        val service = UltronAccessibilityService.getInstance() ?: return false
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2.0f
        val height = metrics.heightPixels.toFloat()
        val startY = if (forward) height * 0.7f else height * 0.3f
        val endY = if (forward) height * 0.3f else height * 0.7f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        return service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
                .build(),
            null,
            null,
        )
    }

    private fun tryReelsEdgeSwipe(forward: Boolean): Boolean {
        val service = UltronAccessibilityService.getInstance() ?: return false
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val x = (width / 2).toFloat()
        val startY = if (forward) height * 0.92f else height * 0.08f
        val endY = if (forward) height * 0.08f else height * 0.92f
        val controlX = if (forward) x + 10.0f else x - 10.0f
        val path = Path().apply {
            moveTo(x, startY)
            quadTo(controlX, (startY + endY) / 2.0f, x, endY)
        }
        return service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 500L))
                .build(),
            null,
            null,
        )
    }

    private fun tryMultiStepSwipe(forward: Boolean, onResult: ((Boolean) -> Unit)?) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            onResult?.invoke(false)
            return
        }
        val metrics = service.resources.displayMetrics
        val height = metrics.heightPixels.toFloat()
        val x = (metrics.widthPixels / 2).toFloat()
        val builder = GestureDescription.Builder()
        val startY = if (forward) height * 0.85f else height * 0.15f
        val endY = if (forward) height * 0.15f else height * 0.85f
        val step = (endY - startY) / 3.0f
        for (i in 0 until 3) {
            val path = Path().apply {
                moveTo(x, i * step + startY)
                lineTo(x, (i + 1) * step + startY)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, (i * 200).toLong(), 200L))
        }
        val dispatched = service.dispatchGesture(builder.build(), null, null)
        Handler(Looper.getMainLooper()).postDelayed({ onResult?.invoke(dispatched) }, 800L)
    }

    private fun getCurrentPackage(): String {
        val service = UltronAccessibilityService.getInstance() ?: return ""
        val root = service.rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString() ?: ""
        root.recycle()
        return pkg
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 100 && rect.height() > 100) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findScrollableNode(child)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    private fun getCurrentScrollY(): Int {
        val service = UltronAccessibilityService.getInstance() ?: return -1
        val root = service.rootInActiveWindow ?: return -1
        val scrollable = findScrollableNode(root)
        if (scrollable == null) {
            root.recycle()
            return -1
        }
        val rect = Rect()
        scrollable.getBoundsInScreen(rect)
        scrollable.recycle()
        root.recycle()
        return rect.top
    }
}
