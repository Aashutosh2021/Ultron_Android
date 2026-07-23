package com.flexfly.ultron.apps

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flexfly.ultron.automation.UltronAccessibilityService
import com.flexfly.ultron.voice.VoiceService
import java.util.Locale

/**
 * Drives Spotify: deep-link search + accessibility-tree automation to pick the right
 * result filter, click the first song row, and hit play, plus media-button playback
 * controls (play/pause/next/previous).
 *
 * Recovered from `SpotifyManager.kt`. The multi-stage automation is a chain of
 * [Handler]-based polling loops ([startFilterPolling] ? [startSongRowPolling] ?
 * [startPlayButtonPolling]); [delay] wraps `postDelayed` with the [POLL_INTERVAL_MS]
 * cadence.
 *
 * RECOVERY NOTE: the decompiler substituted `okhttp3...WebSocketProtocol.PAYLOAD_SHORT`
 * (== 126) for the media keycode literal; restored to [KeyEvent.KEYCODE_MEDIA_PLAY].
 * [findNodeByDescriptions] and [findFilterTabByTraversal] were emitted with jadx's
 * boolean-flag inference broken ("Multi-variable type inference failed"); both are
 * reconstructed here from their clear intent (match desc/text against the target list,
 * then click the node or its nearest clickable ancestor when visible) — matching the
 * structurally-intact `findFilterTab` path.
 */
object SpotifyManager {

    private const val TAG = "SpotifyManager"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_POLL_TIME_MS = 10000L
    private const val MAX_POLL_ATTEMPTS = 50
    private const val FILTER_CLICK_DELAY_MS = 1500L

    fun playSong(context: Context, songName: String) {
        Log.d(TAG, "?? playSong: '$songName'")
        if (!checkAccessibilityService(context)) return

        val lower = songName.lowercase(Locale.ROOT)
        if (lower.contains("liked song") || lower.contains("liked songs") ||
            lower.contains("liked wale") || lower.contains("favorite song") ||
            lower.contains("favorite songs") || lower.contains("favorite gaane") ||
            lower.contains("liked wale gaane")
        ) {
            Log.d(TAG, "?? Intercepted liked songs query: '$songName', redirecting to playLikedSongs")
            playLikedSongs(context)
            return
        }

        val filter = when {
            lower.contains("playlist") || lower.contains("list") -> "Playlists"
            lower.contains("artist") || lower.contains("singer") || lower.contains("singers") -> "Artists"
            lower.contains("album") -> "Albums"
            else -> "Songs"
        }

        val cleanedName = Regex("(?i)\\b(playlist|list|artist|singer|singers|album)\\b")
            .replace(songName, "").trim()
        if (tryDeepLinkSearch(context, cleanedName)) {
            Log.d(TAG, "? Deep link launched — waiting for search results with filter $filter...")
            startFilterPolling(filter, 0)
        } else {
            openSpotifyAndSearch(context, cleanedName)
        }
    }

    fun playLikedSongs(context: Context) {
        Log.d(TAG, "?? playLikedSongs")
        if (!checkAccessibilityService(context)) return

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:collection:tracks"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setPackage(SPOTIFY_PACKAGE)
        try {
            context.startActivity(intent)
            Log.d(TAG, "? Liked songs Deep Link launched — waiting for play button...")
            startPlayButtonPolling(0)
        } catch (e: Exception) {
            Log.e(TAG, "? Liked songs launch failed: ${e.message}")
            if (tryOpenSpotify(context)) {
                startPlayButtonPolling(0)
            } else {
                notifyUser("Boss, Spotify install nahi hai.")
            }
        }
    }

    fun resumeCurrentSong(context: Context) = sendMediaControl(context, "play")

    fun pauseCurrentSong(context: Context) = sendMediaControl(context, "pause")

    fun playPauseToggle(context: Context) = sendMediaControl(context, "togglepause")

    fun nextSong(context: Context) = sendMediaControl(context, "next")

    fun previousSong(context: Context) = sendMediaControl(context, "previous")

    private fun startFilterPolling(filterType: String, attemptCount: Int) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            Log.e(TAG, "? Timeout: $filterType filter tab nahi mila")
            notifyUser("Boss, Spotify mein $filterType filter nahi mila.")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            delay { startFilterPolling(filterType, attemptCount + 1) }
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("spotify")) {
            root.recycle()
            delay { startFilterPolling(filterType, attemptCount + 1) }
            return
        }
        val filterTab = findFilterTab(root, filterType)
        if (filterTab != null) {
            Log.d(TAG, "? '$filterType' filter tab mila! Clicking...")
            Log.d(
                TAG,
                if (filterTab.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    "? $filterType filter clicked!"
                else
                    "? $filterType filter click failed"
            )
            filterTab.recycle()
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSongRowPolling(filterType, 0)
            }, FILTER_CLICK_DELAY_MS)
            return
        }
        root.recycle()
        delay { startFilterPolling(filterType, attemptCount + 1) }
    }

    private fun findFilterTab(root: AccessibilityNodeInfo, filterType: String): AccessibilityNodeInfo? {
        val targets = when (filterType) {
            "Songs" -> listOf("Songs", "Tracks", "????", "???")
            "Playlists" -> listOf("Playlists", "?????????")
            "Artists" -> listOf("Artists", "??????")
            "Albums" -> listOf("Albums", "?????")
            else -> listOf(filterType)
        }
        for (target in targets) {
            for (node in root.findAccessibilityNodeInfosByText(target)) {
                val text = node.text?.toString() ?: ""
                if (text.trim().equals(target, ignoreCase = true) && node.isVisibleToUser) {
                    if (node.isClickable) return node
                    val parent = findClickableParent(node)
                    if (parent != null) {
                        node.recycle()
                        return parent
                    }
                }
                node.recycle()
            }
        }
        Log.d(TAG, "?? findFilterTab: findAccessibilityNodeInfosByText failed, trying recursive traversal fallback...")
        return findFilterTabByTraversal(root, targets)
    }

    private fun findFilterTabByTraversal(
        node: AccessibilityNodeInfo,
        targetTexts: List<String>,
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val textMatches = targetTexts.any { text.trim().equals(it, ignoreCase = true) }
        val descMatches = targetTexts.any { desc.trim().equals(it, ignoreCase = true) }
        if ((textMatches || descMatches) && node.isVisibleToUser) {
            if (node.isClickable) return AccessibilityNodeInfo.obtain(node)
            findClickableParent(node)?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFilterTabByTraversal(child, targetTexts)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun dpToPx(dp: Int): Int {
        val service = UltronAccessibilityService.getInstance() ?: return dp * 3
        return (dp * service.resources.displayMetrics.density).toInt()
    }

    private fun getScreenWidth(): Int {
        val service = UltronAccessibilityService.getInstance() ?: return 1080
        return service.resources.displayMetrics.widthPixels
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent ?: return null
        var i = 0
        while (i < 5) {
            if (parent.isClickable && parent.isVisibleToUser) {
                return parent
            }
            val next = parent.parent ?: return null
            parent.recycle()
            i++
            parent = next
        }
        return null
    }

    private fun startSongRowPolling(filterType: String, attemptCount: Int) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            Log.e(TAG, "? Timeout: Row nahi mila")
            notifyUser("Boss, Spotify mein content nahi mila.")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            delay { startSongRowPolling(filterType, attemptCount + 1) }
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("spotify")) {
            root.recycle()
            delay { startSongRowPolling(filterType, attemptCount + 1) }
            return
        }
        val songRow = findFirstSongRow(root)
        if (songRow != null) {
            val text = songRow.text?.toString() ?: ""
            val bounds = Rect()
            songRow.getBoundsInScreen(bounds)
            Log.d(TAG, "?? Row mila: '${text.take(40)}' bounds=$bounds")
            val clicked = songRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, if (clicked) "? Row clicked successfully!" else "? Row click failed!")
            songRow.recycle()
            root.recycle()
            if (!clicked || filterType == "Songs") {
                return
            }
            Handler(Looper.getMainLooper()).postDelayed({
                startPlayButtonPolling(0)
            }, FILTER_CLICK_DELAY_MS)
            return
        }
        root.recycle()
        delay { startSongRowPolling(filterType, attemptCount + 1) }
    }

    private fun findFirstSongRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        collectSongRowCandidates(root, candidates)
        if (candidates.isEmpty()) return null
        val best = candidates.maxByOrNull { it.second }
        candidates.filter { it != best }.forEach { it.first.recycle() }
        return best?.first
    }

    private fun collectSongRowCandidates(
        node: AccessibilityNodeInfo,
        list: MutableList<Pair<AccessibilityNodeInfo, Int>>,
    ) {
        if (node.isClickable && node.isVisibleToUser && node.isEnabled) {
            val className = node.className?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val width = bounds.width()
            val height = bounds.height()

            if (className.contains("EditText")) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { collectSongRowCandidates(it, list) }
                }
                return
            }
            if (desc.contains("Now Playing", ignoreCase = true)) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { collectSongRowCandidates(it, list) }
                }
                return
            }
            if (width < dpToPx(100)) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { collectSongRowCandidates(it, list) }
                }
                return
            }

            val screenWidth = getScreenWidth()
            val minHeight = dpToPx(45)
            val maxHeight = dpToPx(135)
            if (width > screenWidth * 0.7 && height in minHeight..maxHeight) {
                var score = if (hasDirectImageViewChild(node)) 3 else 0
                if (countDirectTextViewChildren(node) >= 2) {
                    score += 2
                }
                if (height in 150..300) {
                    score++
                }
                val combined = "$text $desc"
                if (combined.contains("Playlist", ignoreCase = true) ||
                    combined.contains("Podcast", ignoreCase = true) ||
                    combined.contains("Episode", ignoreCase = true) ||
                    combined.contains("Follow", ignoreCase = true)
                ) {
                    score -= 2
                }
                if (score > 0) {
                    Log.d(TAG, "?? Candidate score=$score: '${text.take(30)}' [${width}x${height}]")
                    list.add(node to score)
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSongRowCandidates(it, list) }
        }
    }

    private fun hasDirectImageViewChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val className = child.className?.toString() ?: ""
            child.recycle()
            if (className.contains("ImageView") || className.contains("ImageButton")) {
                return true
            }
        }
        return false
    }

    private fun countDirectTextViewChildren(node: AccessibilityNodeInfo): Int {
        var count = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val className = child.className?.toString() ?: ""
            if (className.contains("TextView")) {
                count++
            }
            child.recycle()
        }
        return count
    }

    private fun startPlayButtonPolling(attemptCount: Int) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            Log.e(TAG, "? Timeout: Play button nahi mila")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            delay { startPlayButtonPolling(attemptCount + 1) }
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("spotify")) {
            root.recycle()
            delay { startPlayButtonPolling(attemptCount + 1) }
            return
        }
        val playButton = findPlayButton(root)
        if (playButton != null) {
            Log.d(TAG, "? Play button clicked=${playButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
            playButton.recycle()
            root.recycle()
        } else {
            root.recycle()
            delay { startPlayButtonPolling(attemptCount + 1) }
        }
    }

    private fun findPlayButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.spotify.music:id/play_button",
            "com.spotify.music:id/button_play_and_pause",
            "com.spotify.music:id/header_play_button",
            "com.spotify.music:id/play_pause_button",
            "com.spotify.music:id/control_play",
            "com.spotify.music:id/play_btn"
        )
        for (id in ids) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isClickable && it.isVisibleToUser }
            if (found != null) {
                Log.d(TAG, "? Play button found by ID: $id")
                return found
            }
        }
        val byDesc = findNodeByDescriptions(
            root,
            listOf("Play", "play", "Pause", "pause", "Shuffle play", "Play playlist", "????", "????", "???")
        )
        if (byDesc != null) {
            Log.d(TAG, "? Play button found by description")
            return byDesc
        }
        return findPlayButtonByHeuristic(root)
    }

    private fun findPlayButtonByHeuristic(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val service = UltronAccessibilityService.getInstance() ?: return null
        val metrics: DisplayMetrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val density = metrics.density

        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectPlayButtonCandidates(node, candidates)
        if (candidates.isEmpty()) return null

        val scored = candidates.map { candidate ->
            val rect = Rect()
            candidate.getBoundsInScreen(rect)
            val centerX = rect.centerX().toFloat()
            val centerY = rect.centerY().toFloat()
            var score = if (centerX > screenW * 0.5f) 5 else 0
            val screenHf = screenH.toFloat()
            if (centerY <= screenHf * 0.65f && centerY >= 0.2f * screenHf) {
                score += 5
            }
            score += ((rect.width() / density) * (rect.height() / density) / 300.0f).toInt()
            candidate to score
        }
        val bestNode = scored.maxByOrNull { it.second }?.first
        candidates.filter { it != bestNode }.forEach { it.recycle() }
        return bestNode
    }

    private fun collectPlayButtonCandidates(
        node: AccessibilityNodeInfo,
        list: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.isClickable && node.isVisibleToUser && node.isEnabled) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()
            val minSize = dpToPx(32)
            val maxSize = dpToPx(95)
            val sizeInRange = width in minSize..maxSize && height in minSize..maxSize
            val roughlySquare = Math.abs(width - height) < dpToPx(15)
            if (sizeInRange && roughlySquare) {
                list.add(node)
                return
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectPlayButtonCandidates(it, list) }
        }
    }

    private fun sendMediaControl(context: Context, action: String) {
        try {
            val keyCode = when (action) {
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "togglepause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                else -> return
            }
            val down = Intent(Intent.ACTION_MEDIA_BUTTON)
            down.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            context.sendBroadcast(down)
            val up = Intent(Intent.ACTION_MEDIA_BUTTON)
            up.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            context.sendBroadcast(up)
            Log.d(TAG, "? Media control: $action")
        } catch (e: Exception) {
            Log.e(TAG, "? Media control failed: ${e.message}")
        }
    }

    private fun tryDeepLinkSearch(context: Context, songName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(songName)))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage(SPOTIFY_PACKAGE)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "? Deep link failed: ${e.message}")
            false
        }
    }

    private fun tryOpenSpotify(context: Context): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
                ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openSpotifyAndSearch(context: Context, songName: String) {
        if (tryOpenSpotify(context)) {
            return
        }
        notifyUser("Boss, Spotify install nahi hai.")
    }

    private fun startSmartPlayPausePolling(attemptCount: Int, firstDetection: Boolean) {
        val service = UltronAccessibilityService.getInstance() ?: return
        if (attemptCount >= MAX_POLL_ATTEMPTS) return

        val root = service.rootInActiveWindow
        if (root == null) {
            delay { startSmartPlayPausePolling(attemptCount + 1, firstDetection) }
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("spotify")) {
            root.recycle()
            delay { startSmartPlayPausePolling(attemptCount + 1, firstDetection) }
            return
        }
        val playPause = findSpotifyPlayPause(root)
        when {
            playPause == null -> {
                root.recycle()
                delay { startSmartPlayPausePolling(attemptCount + 1, firstDetection) }
            }
            firstDetection -> {
                playPause.recycle()
                root.recycle()
                Handler(Looper.getMainLooper()).postDelayed({
                    startSmartPlayPausePolling(0, false)
                }, FILTER_CLICK_DELAY_MS)
            }
            else -> {
                playPause.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                playPause.recycle()
                root.recycle()
            }
        }
    }

    private fun findSpotifyPlayPause(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findNodeByDescriptions(root, listOf("Play", "play", "Pause", "pause", "????", "????"))
            ?.let { return it }

        val ids = listOf(
            "com.spotify.music:id/btn_play_pause",
            "com.spotify.music:id/play_pause_button",
            "com.spotify.music:id/player_button"
        )
        return ids.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isClickable && it.isVisibleToUser }
        }
    }

    private fun findNodeByDescriptions(
        node: AccessibilityNodeInfo,
        descriptions: List<String>,
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val descMatches = descriptions.any {
            desc.equals(it, ignoreCase = true) || desc.contains(it, ignoreCase = true)
        }
        val textMatches = descriptions.any {
            text.equals(it, ignoreCase = true) || text.contains(it, ignoreCase = true)
        }
        if ((descMatches || textMatches) && node.isVisibleToUser) {
            if (node.isClickable) return node
            findClickableParent(node)?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByDescriptions(child, descriptions)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun delay(block: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({ block() }, POLL_INTERVAL_MS)
    }

    private fun checkAccessibilityService(context: Context): Boolean {
        if (UltronAccessibilityService.getInstance() != null) {
            return true
        }
        notifyUser("Boss, Accessibility Service band hai.")
        return false
    }

    private fun notifyUser(message: String) {
        Handler(Looper.getMainLooper()).post {
            VoiceService.getActiveInstance()?.injectReminderMessage(message)
        }
    }
}
