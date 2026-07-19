package com.flexfly.ultron.apps

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.flexfly.ultron.automation.UltronAccessibilityService
import java.util.Locale

/**
 * Full-flow YouTube automation over the accessibility tree: open the app, tap search,
 * type the query, pick the first suggestion, scroll past Shorts to the first regular
 * video and play it — plus in-video controls (play/pause, like, subscribe, captions)
 * and ad/popup dismissal.
 *
 * Recovered from `YouTubeManager.kt`. The search flow is a chain of [Handler]-based
 * polling stages (search button → search field → suggestion → first video), each with a
 * "detect, stabilize [STABILIZATION_DELAY_MS], then act" pattern.
 *
 * RECOVERY NOTES:
 *  - The decompiler substituted `okhttp3...WebSocketProtocol.PAYLOAD_SHORT` (== 126) for
 *    the media keycode literal; restored to [KeyEvent.KEYCODE_MEDIA_PLAY].
 *  - [analyzeScreenContent] lost the "add to shorts list" statement (jadx flagged the
 *    dropped block); reconstructed so nodes matching the Shorts bounds go to the shorts
 *    list, nodes matching the wide/regular bounds go to the regular list, others recycled
 *    — consistent with [collectAllVideoClickables]'s own bounds test.
 *  - [findNodeByTextOrDesc] and [findNodeByDescriptionsExcludingVoice] had jadx's
 *    boolean-flag / parent-walk inference broken; reconstructed from their clear intent.
 *  - `performGlobalAction(3)` then `performGlobalAction(1)` (RECENTS then BACK) is kept as
 *    the original literals — the developer used it as a makeshift "scroll" nudge.
 */
object YouTubeManager {

    private const val TAG = "YouTubeManager"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_POLL_TIME_MS = 15000L
    private const val MAX_POLL_ATTEMPTS = 75
    private const val STABILIZATION_DELAY_MS = 2500L

    private var currentQuery = ""

    fun searchAndPlay(context: Context, query: String, onResult: (String) -> Unit) {
        Log.d(TAG, "🔍 YouTube search: '$query'")
        currentQuery = query
        if (!checkAccessibilityService(context, onResult)) return
        if (!openYouTube(context)) {
            onResult("Boss, YouTube install nahi hai.")
        } else {
            Log.d(TAG, "✅ YouTube opened")
            startSmartSearchButtonPolling(onResult, 0, true)
        }
    }

    private fun openYouTube(context: Context): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
                ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startSmartSearchButtonPolling(
        onResult: (String) -> Unit,
        attemptCount: Int,
        firstDetection: Boolean,
    ) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            onResult("Boss, YouTube search button nahi mil raha.")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSearchButtonPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("youtube")) {
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSearchButtonPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val searchButton = findSearchButton(root)
        if (searchButton == null) {
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSearchButtonPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        if (firstDetection) {
            Log.d(TAG, "✅ Search button detected! Stabilizing...")
            searchButton.recycle()
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSearchButtonPolling(onResult, 0, false)
            }, STABILIZATION_DELAY_MS)
            return
        }
        Log.d(TAG, "✅ Clicking search button")
        searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        searchButton.recycle()
        root.recycle()
        Handler(Looper.getMainLooper()).postDelayed({
            startSmartSearchFieldPolling(onResult, 0, true)
        }, 500L)
    }

    private fun startSmartSearchFieldPolling(
        onResult: (String) -> Unit,
        attemptCount: Int,
        firstDetection: Boolean,
    ) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            onResult("Boss, YouTube search field nahi mil raha.")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSearchFieldPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val searchField = findSearchField(root)
        if (searchField == null) {
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSearchFieldPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        if (firstDetection) {
            Log.d(TAG, "✅ Search field detected! Stabilizing...")
            searchField.recycle()
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSearchFieldPolling(onResult, 0, false)
            }, STABILIZATION_DELAY_MS)
            return
        }
        Log.d(TAG, "✅ Typing query: '$currentQuery'")
        searchField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            currentQuery
        )
        searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        searchField.recycle()
        root.recycle()
        Handler(Looper.getMainLooper()).postDelayed({
            startSmartSuggestionPolling(onResult, 0, true)
        }, 1000L)
    }

    private fun startSmartSuggestionPolling(
        onResult: (String) -> Unit,
        attemptCount: Int,
        firstDetection: Boolean,
    ) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= 30) {
            Log.w(TAG, "⚠️ No suggestions - submitting search directly")
            service.pressEnterOnFocusedNode()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartFirstVideoPolling(onResult, 0, true)
            }, 1500L)
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSuggestionPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val suggestion = findFirstSuggestion(root)
        if (suggestion == null) {
            if (attemptCount % 5 == 0) {
                Log.d(TAG, "⏳ Waiting for suggestions... (${attemptCount * POLL_INTERVAL_MS}ms)")
            }
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSuggestionPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        if (firstDetection) {
            Log.d(TAG, "✅ First suggestion detected! Stabilizing...")
            suggestion.recycle()
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartSuggestionPolling(onResult, 0, false)
            }, STABILIZATION_DELAY_MS)
            return
        }
        Log.d(TAG, "✅ Clicking first suggestion")
        val clicked = suggestion.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, if (clicked) "✅ Suggestion clicked!" else "❌ Click failed!")
        suggestion.recycle()
        root.recycle()
        if (clicked) {
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartFirstVideoPolling(onResult, 0, true)
            }, 1500L)
        }
    }

    private fun startSmartFirstVideoPolling(
        onResult: (String) -> Unit,
        attemptCount: Int,
        firstDetection: Boolean,
    ) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            onResult("Boss, koi video nahi mila.")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartFirstVideoPolling(onResult, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        if (attemptCount < 3) {
            Log.d(TAG, "═══ ATTEMPT $attemptCount ═══")
            logAllTextViews(root)
        }
        val (shorts, regularVideos) = analyzeScreenContent(root)
        Log.d(TAG, "📊 Screen analysis: ${shorts.size} shorts, ${regularVideos.size} regular videos")

        if (regularVideos.isNotEmpty()) {
            val firstVideo = regularVideos.first()
            if (firstDetection) {
                Log.d(TAG, "✅ Regular video found! Stabilizing...")
                firstVideo.recycle()
                regularVideos.drop(1).forEach { it.recycle() }
                shorts.forEach { it.recycle() }
                root.recycle()
                Handler(Looper.getMainLooper()).postDelayed({
                    startSmartFirstVideoPolling(onResult, 0, false)
                }, STABILIZATION_DELAY_MS)
                return
            }
            Log.d(TAG, "✅ Clicking regular video")
            val clicked = firstVideo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, if (clicked) "✅ Video clicked!" else "❌ Click failed!")
            firstVideo.recycle()
            regularVideos.drop(1).forEach { it.recycle() }
            shorts.forEach { it.recycle() }
            root.recycle()
            if (clicked) {
                onResult("Boss, YouTube pe video chala diya!")
            }
            return
        }

        if (shorts.isNotEmpty()) {
            Log.d(TAG, "⚠️ Only Shorts found - scrolling to find regular videos...")
            shorts.forEach { it.recycle() }
            root.recycle()
            performScrollDown(service)
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartFirstVideoPolling(onResult, attemptCount + 1, firstDetection)
            }, 800L)
            return
        }

        if (attemptCount % 10 == 0) {
            Log.d(TAG, "⏳ Looking for content... (${attemptCount * POLL_INTERVAL_MS}ms)")
        }
        root.recycle()
        Handler(Looper.getMainLooper()).postDelayed({
            startSmartFirstVideoPolling(onResult, attemptCount + 1, firstDetection)
        }, POLL_INTERVAL_MS)
    }

    /**
     * Splits clickable video candidates into (shorts, regularVideos) by on-screen bounds.
     * RECOVERY NOTE: the shorts-collection statement was dropped by the decompiler — see
     * the class-level note.
     */
    private fun analyzeScreenContent(
        root: AccessibilityNodeInfo,
    ): Pair<List<AccessibilityNodeInfo>, List<AccessibilityNodeInfo>> {
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAllVideoClickables(root, candidates)
        val shorts = ArrayList<AccessibilityNodeInfo>()
        val regular = ArrayList<AccessibilityNodeInfo>()
        for (node in candidates) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()
            val isShort = width in 200..500 && height in 300..900
            val isRegular = width > 800 && height in 300..1000
            when {
                isShort -> shorts.add(node)
                isRegular -> regular.add(node)
                else -> node.recycle()
            }
        }
        return Pair(shorts, regular)
    }

    private fun performScrollDown(service: UltronAccessibilityService) {
        try {
            val root = service.rootInActiveWindow ?: return
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                val ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Log.d(TAG, "📜 Scroll action: ${if (ok) "Success" else "Failed"}")
                scrollable.recycle()
            } else {
                Log.d(TAG, "📜 Using global scroll gesture")
                // RECOVERY NOTE: original literals — 3 = GLOBAL_ACTION_RECENTS, 1 = GLOBAL_ACTION_BACK.
                service.performGlobalAction(3)
                Handler(Looper.getMainLooper()).postDelayed({
                    service.performGlobalAction(1)
                }, 100L)
            }
            root.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Scroll error: ${e.message}")
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun collectAllVideoClickables(
        node: AccessibilityNodeInfo,
        list: MutableList<AccessibilityNodeInfo>,
    ) {
        val desc = node.contentDescription?.toString() ?: ""
        if (node.isClickable && node.isVisibleToUser && node.isEnabled) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()
            val sizeMatches = (width in 200..500 && height in 300..900) ||
                (width > 800 && height in 300..1000)
            val positionOk = rect.top > 300 && rect.bottom < 2400
            val isNavChrome = desc.contains("Navigate", ignoreCase = true) ||
                desc.contains("Home", ignoreCase = true) ||
                desc.contains("Shorts", ignoreCase = true) ||
                desc.contains("Create", ignoreCase = true) ||
                desc.contains("Subscriptions", ignoreCase = true) ||
                desc.contains("You", ignoreCase = true) ||
                desc.contains("Cast", ignoreCase = true) ||
                desc.contains("Voice", ignoreCase = true) ||
                desc.contains("Search", ignoreCase = true) ||
                desc.contains("More options", ignoreCase = true)
            val isAd = desc.contains("Ad •", ignoreCase = true) ||
                desc.contains("Sponsored", ignoreCase = true)
            val tooSmall = !(rect.width() >= 150 && rect.height() >= 150)
            if (sizeMatches && positionOk && !isNavChrome && !isAd && !tooSmall) {
                list.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllVideoClickables(it, list) }
        }
    }

    private fun logAllTextViews(root: AccessibilityNodeInfo) {
        val texts = ArrayList<String>()
        collectTextViewsForLog(root, texts)
        Log.d(TAG, "Found ${texts.size} TextViews:")
        texts.take(10).forEach { Log.d(TAG, "  $it") }
        Log.d(TAG, "═══════════════════════")
    }

    private fun collectTextViewsForLog(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        if (className.contains("TextView") && text.length > 5 && node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            list.add("TextView: \"${text.take(30)}\" y=${rect.top}")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTextViewsForLog(it, list) }
        }
    }

    private fun findSearchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findNodeByDescriptionsExcludingVoice(root, listOf("Search", "search"))?.let { return it }

        val ids = listOf(
            "com.google.android.youtube:id/menu_item_1",
            "com.google.android.youtube:id/search"
        )
        return ids.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { node ->
                val desc = node.contentDescription?.toString() ?: ""
                node.isClickable && node.isVisibleToUser && !desc.contains("voice", ignoreCase = true)
            }
        }
    }

    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val fields = ArrayList<AccessibilityNodeInfo>()
        collectEditableFields(root, fields)
        return fields.firstOrNull()
    }

    private fun findFirstSuggestion(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val groups = ArrayList<AccessibilityNodeInfo>()
        collectSuggestionViewGroups(root, groups)
        if (groups.isEmpty()) return null
        Log.d(TAG, "✅ Found ${groups.size} suggestion ViewGroups")
        return groups.first()
    }

    private fun collectSuggestionViewGroups(
        node: AccessibilityNodeInfo,
        list: MutableList<AccessibilityNodeInfo>,
    ) {
        val className = node.className?.toString() ?: ""
        if (className.contains("ViewGroup") && node.isClickable && node.isVisibleToUser &&
            hasTextViewChild(node)
        ) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.height() > 40 && rect.width() > 200) {
                list.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSuggestionViewGroups(it, list) }
        }
    }

    private fun hasTextViewChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val className = child.className?.toString() ?: ""
            val text = child.text?.toString() ?: ""
            val desc = child.contentDescription?.toString() ?: ""
            if (className.contains("TextView") && text.isNotBlank() &&
                !desc.contains("Edit suggestion", ignoreCase = true)
            ) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun collectEditableFields(
        node: AccessibilityNodeInfo,
        list: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.isEditable && node.isVisibleToUser) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectEditableFields(it, list) }
        }
    }

    private fun findNodeByDescriptionsExcludingVoice(
        node: AccessibilityNodeInfo,
        descriptions: List<String>,
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        if (node.isClickable && node.isVisibleToUser) {
            val matches = descriptions.any { desc.contains(it, ignoreCase = true) }
            val isVoice = desc.contains("voice", ignoreCase = true)
            if (matches && !isVoice) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByDescriptionsExcludingVoice(child, descriptions)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun checkAccessibilityService(context: Context, onResult: (String) -> Unit): Boolean {
        if (UltronAccessibilityService.getInstance() != null) {
            return true
        }
        onResult("Boss, Accessibility Service band hai.")
        return false
    }

    fun skipAdsAndPopups(root: AccessibilityNodeInfo) {
        val skipIds = listOf(
            "com.google.android.youtube:id/skip_ad_button",
            "com.google.android.youtube:id/modern_skip_ad_button",
            "com.google.android.youtube:id/skip_ad_button_container"
        )
        for (id in skipIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.isVisibleToUser) {
                    Log.d(TAG, "📺 Auto-skipped YouTube Ad (by ID: $id): ${node.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
                    node.recycle()
                    nodes.filter { it != node }.forEach { it.recycle() }
                    return
                }
                node.recycle()
            }
        }

        val skipByText = findNodeByTextOrDesc(root, listOf("Skip Ad", "Skip ad", "Skip"))
        if (skipByText != null) {
            Log.d(TAG, "📺 Auto-skipped YouTube Ad (by text/desc): ${skipByText.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
            skipByText.recycle()
            return
        }

        val dismissTexts = listOf("No thanks", "No, thanks", "Not now", "Dismiss", "No, Thanks", "Close")
        val dismissIds = listOf(
            "com.google.android.youtube:id/dismiss_button",
            "com.google.android.youtube:id/mealbar_dismiss_button"
        )
        for (id in dismissIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.isVisibleToUser) {
                    Log.d(TAG, "📺 Auto-dismissed YouTube popup (by ID: $id): ${node.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
                    node.recycle()
                    nodes.filter { it != node }.forEach { it.recycle() }
                    return
                }
                node.recycle()
            }
        }

        val dismissByText = findNodeByTextOrDesc(root, dismissTexts)
        if (dismissByText != null) {
            Log.d(TAG, "📺 Auto-dismissed YouTube popup (by text/desc): ${dismissByText.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
            dismissByText.recycle()
        }
    }

    private fun findNodeByTextOrDesc(
        node: AccessibilityNodeInfo,
        targets: List<String>,
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val textMatches = targets.any {
            text.trim().equals(it, ignoreCase = true) || text.contains(it, ignoreCase = true)
        }
        val descMatches = targets.any {
            desc.trim().equals(it, ignoreCase = true) || desc.contains(it, ignoreCase = true)
        }
        if ((textMatches || descMatches) && node.isVisibleToUser) {
            if (node.isClickable) {
                return AccessibilityNodeInfo.obtain(node)
            }
            // Walk up to 3 ancestors looking for a clickable, visible parent.
            var parent = node.parent
            var result: AccessibilityNodeInfo? = null
            var i = 0
            while (i < 3 && parent != null) {
                if (parent.isClickable && parent.isVisibleToUser) {
                    result = AccessibilityNodeInfo.obtain(parent)
                    break
                }
                val next = parent.parent
                parent.recycle()
                i++
                parent = next
            }
            if (parent != null) {
                parent.recycle()
            }
            if (result != null) {
                return result
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextOrDesc(child, targets)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun tapCenterToRevealControls(service: UltronAccessibilityService) {
        val metrics = service.resources.displayMetrics
        service.performTapGesture(metrics.widthPixels / 2.0f, metrics.heightPixels / 2.5f)
    }

    fun playOrPauseVideo(context: Context, action: String, onResult: (String) -> Unit) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            onResult("Boss, Accessibility Service band hai.")
            return
        }
        try {
            val keyCode = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val down = Intent(Intent.ACTION_MEDIA_BUTTON)
            down.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            context.sendBroadcast(down)
            val up = Intent(Intent.ACTION_MEDIA_BUTTON)
            up.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            context.sendBroadcast(up)
            Log.d(TAG, "Sent global media control broadcast: $action")
        } catch (e: Exception) {
            Log.e(TAG, "Global media control broadcast failed: ${e.message}")
        }
        tapCenterToRevealControls(service)
        Handler(Looper.getMainLooper()).postDelayed({
            playOrPauseViaControls(service, action, onResult)
        }, 500L)
    }

    private fun playOrPauseViaControls(
        service: UltronAccessibilityService,
        action: String,
        onResult: (String) -> Unit,
    ) {
        val resultMessage = if (action == "pause") "Video pause kar diya Boss!" else "Video play/resume kar diya Boss!"
        val root = service.rootInActiveWindow
        if (root == null) {
            onResult(resultMessage)
            return
        }
        val ids = listOf(
            "com.google.android.youtube:id/player_control_play_pause",
            "com.google.android.youtube:id/play_pause_button",
            "com.google.android.youtube:id/play_button"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.isVisibleToUser) {
                    val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                    val isPause = desc.contains("pause")
                    if ((action == "play" && !isPause) ||
                        (action == "pause" && isPause) ||
                        action == "toggle"
                    ) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    node.recycle()
                    nodes.filter { it != node }.forEach { it.recycle() }
                    root.recycle()
                    onResult(resultMessage)
                    return
                }
                node.recycle()
            }
        }

        val targets = when (action) {
            "play" -> listOf("Play video", "Play")
            "pause" -> listOf("Pause video", "Pause")
            else -> listOf("Play video", "Play", "Pause video", "Pause")
        }
        val byText = findNodeByTextOrDesc(root, targets)
        if (byText != null) {
            byText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            byText.recycle()
            root.recycle()
            onResult(resultMessage)
            return
        }
        root.recycle()
        onResult(resultMessage)
    }

    fun likeVideo(onResult: (String) -> Unit) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            onResult("Boss, Accessibility Service band hai.")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            onResult("Boss, YouTube screen nahi mili.")
            return
        }
        val ids = listOf("com.google.android.youtube:id/like_button")
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.isVisibleToUser) {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    nodes.filter { it != node }.forEach { it.recycle() }
                    root.recycle()
                    if (ok) {
                        onResult("Boss, video like kar diya!")
                    } else {
                        onResult("Boss, video like karne mein dikkat aayi.")
                    }
                    return
                }
                node.recycle()
            }
        }

        val byText = findNodeByTextOrDesc(root, listOf("like this video", "like video", "Thumbs up"))
        if (byText != null) {
            val ok = byText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            byText.recycle()
            root.recycle()
            if (ok) {
                onResult("Boss, video like kar diya!")
            } else {
                onResult("Boss, video like karne mein dikkat aayi.")
            }
            return
        }
        root.recycle()
        onResult("Boss, like button nahi mila.")
    }

    fun subscribeChannel(onResult: (String) -> Unit) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            onResult("Boss, Accessibility Service band hai.")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            onResult("Boss, YouTube screen nahi mili.")
            return
        }
        val ids = listOf("com.google.android.youtube:id/subscribe_button")
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val combined = "$text $desc".lowercase(Locale.ROOT)
                    if (combined.contains("subscribed")) {
                        node.recycle()
                        nodes.filter { it != node }.forEach { it.recycle() }
                        root.recycle()
                        onResult("Aapne pehle se subscribe kiya hua hai Boss!")
                        return
                    }
                    if (node.isClickable) {
                        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node.recycle()
                        nodes.filter { it != node }.forEach { it.recycle() }
                        root.recycle()
                        if (ok) {
                            onResult("Boss, channel ko subscribe kar diya!")
                        } else {
                            onResult("Boss, subscribe karne mein dikkat aayi.")
                        }
                        return
                    }
                }
                node.recycle()
            }
        }

        val byText = findNodeByTextOrDesc(root, listOf("Subscribe", "Subscribe to"))
        if (byText != null) {
            val text = byText.text?.toString() ?: ""
            val desc = byText.contentDescription?.toString() ?: ""
            val combined = "$text $desc".lowercase(Locale.ROOT)
            if (combined.contains("subscribed")) {
                byText.recycle()
                root.recycle()
                onResult("Aapne pehle se subscribe kiya hua hai Boss!")
                return
            }
            val ok = byText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            byText.recycle()
            root.recycle()
            if (ok) {
                onResult("Boss, channel ko subscribe kar diya!")
            } else {
                onResult("Boss, subscribe karne mein dikkat aayi.")
            }
            return
        }
        root.recycle()
        onResult("Boss, subscribe button nahi mila.")
    }

    fun toggleCaptions(onResult: (String) -> Unit) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            onResult("Boss, Accessibility Service band hai.")
            return
        }
        tapCenterToRevealControls(service)
        Handler(Looper.getMainLooper()).postDelayed({
            toggleCaptionsViaControls(service, onResult)
        }, 500L)
    }

    private fun toggleCaptionsViaControls(
        service: UltronAccessibilityService,
        onResult: (String) -> Unit,
    ) {
        val root = service.rootInActiveWindow
        if (root == null) {
            onResult("Boss, YouTube controls nahi dikh rahe.")
            return
        }
        val ids = listOf(
            "com.google.android.youtube:id/cc_button",
            "com.google.android.youtube:id/player_cc_button"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.isVisibleToUser) {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    nodes.filter { it != node }.forEach { it.recycle() }
                    root.recycle()
                    if (ok) {
                        onResult("Captions toggle kar diya Boss!")
                    } else {
                        onResult("Captions click nahi ho paya.")
                    }
                    return
                }
                node.recycle()
            }
        }

        val byText = findNodeByTextOrDesc(root, listOf("Captions", "Subtitles", "cc", "CC"))
        if (byText != null) {
            val ok = byText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            byText.recycle()
            root.recycle()
            if (ok) {
                onResult("Captions toggle kar diya Boss!")
            } else {
                onResult("Captions click nahi ho paya.")
            }
            return
        }
        root.recycle()
        onResult("Boss, Captions/CC button nahi mila.")
    }
}
