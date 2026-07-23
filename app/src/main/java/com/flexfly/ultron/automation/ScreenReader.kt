package com.flexfly.ultron.automation

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flexfly.ultron.i18n.HindiTransliterator
import com.flexfly.ultron.voice.VoiceService
import java.util.Locale

/**
 * Reads on-screen text via the accessibility tree and drives "find & click" of a
 * label, with Hinglish/Devanagari-aware fuzzy matching and WebView-friendly gesture
 * fallbacks.
 *
 * Recovered from `ScreenReader.kt`. The `$$ExternalSyntheticLambda` callbacks are
 * folded back as inline lambdas.
 *
 * RECOVERY NOTE: `performAction(16)` is restored to ACTION_CLICK.
 *
 * Cross-file refs (best-guess imports):
 *  - [WakeWordService] (com.flexfly.ultron.service) — its companion exposes a Compose
 *    `MutableState<PointF> ghostPointerCoordinates` used to animate the "ghost" tap.
 *  - [HindiTransliterator] (com.flexfly.ultron.i18n).
 */
object ScreenReader {

    private const val TAG = "ScreenReader"

    fun readScreen(context: Context): String {
        Log.d(TAG, "?? Reading screen...")
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "? Accessibility service not bound (enabledInSettings=${UltronAccessibilityService.isEnabledInSettings(context)})")
            return if (UltronAccessibilityService.isEnabledInSettings(context))
                "Boss, accessibility service abhi reconnect ho rahi hai — ek second baad dobara bolo."
            else
                "Boss, accessibility service on nahi hai — settings mein Ultron ko enable karo."
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "? No active window")
            return "Boss, screen nahi mil rahi!"
        }
        val pkg = root.packageName?.toString() ?: ""
        val appName = getAppName(pkg)
        val allText = extractAllText(root)
        root.recycle()
        if (allText.isBlank()) {
            Log.w(TAG, "?? No text found on screen")
            return "Boss, screen pe koi text nahi hai!"
        }
        Log.d(TAG, "? Extracted ${allText.length} characters")
        return buildScreenSummaryPrompt(appName, allText)
    }

    fun readAppScreen(context: Context, appName: String): String {
        Log.d(TAG, "?? Reading $appName screen...")
        val service = UltronAccessibilityService.getInstance()
            ?: return if (UltronAccessibilityService.isEnabledInSettings(context))
                "Boss, accessibility service abhi reconnect ho rahi hai — ek second baad dobara bolo."
            else "Boss, accessibility service on nahi hai — settings mein Ultron ko enable karo."
        val root = service.rootInActiveWindow ?: return "Boss, screen nahi mil rahi!"
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains(appName, ignoreCase = true)) {
            root.recycle()
            return "Boss, $appName khuli nahi hai!"
        }
        val allText = extractAllText(root)
        root.recycle()
        if (allText.isBlank()) {
            return "Boss, screen pe koi text nahi hai!"
        }
        return buildScreenSummaryPrompt(appName, allText)
    }

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectText(node, sb, LinkedHashSet())
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, builder: StringBuilder, seenTexts: MutableSet<String>) {
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (text != null && text.isNotBlank() && text.length > 1 && seenTexts.add(text)) {
            builder.append(text).append("\n")
        }
        if (desc != null && desc.isNotBlank() && desc != text && desc.length > 1 && seenTexts.add(desc)) {
            builder.append(desc).append("\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectText(child, builder, seenTexts)
                child.recycle()
            }
        }
    }

    private fun buildScreenSummaryPrompt(appName: String, screenText: String): String {
        return """
Boss, main dekh pa rahi hoon screen pe kya hai.

App: $appName

Screen pe yeh text hai:
---
$screenText
---

Isko padh ke mujhe short mein batao ki screen pe kya chal raha hai.
2-3 sentences mein, natural Hinglish mein.
Important points only.
        """.trimIndent()
    }

    private fun getAppName(packageName: String): String = when {
        packageName.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
        packageName.contains("instagram", ignoreCase = true) -> "Instagram"
        packageName.contains("youtube", ignoreCase = true) -> "YouTube"
        packageName.contains("chrome", ignoreCase = true) -> "Chrome"
        packageName.contains("gmail", ignoreCase = true) -> "Gmail"
        packageName.contains("twitter", ignoreCase = true) -> "Twitter"
        packageName.contains("facebook", ignoreCase = true) -> "Facebook"
        packageName.contains("telegram", ignoreCase = true) -> "Telegram"
        packageName.contains("netflix", ignoreCase = true) -> "Netflix"
        packageName.contains("spotify", ignoreCase = true) -> "Spotify"
        else -> packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
    }

    fun getRawScreenText(context: Context): String {
        val service = UltronAccessibilityService.getInstance() ?: return ""
        val root = service.rootInActiveWindow ?: return ""
        val allText = extractAllText(root)
        root.recycle()
        return allText
    }

    fun hasTextOnScreen(context: Context, searchText: String): Boolean {
        return getRawScreenText(context).contains(searchText, ignoreCase = true)
    }

    fun clickTextOnScreen(context: Context, searchText: String): Boolean {
        Log.d(TAG, "?? Finding and clicking: $searchText")
        val service = UltronAccessibilityService.getInstance() ?: return false
        val root = service.rootInActiveWindow ?: return false
        val node = findNodeWithText(root, searchText)
        if (node != null) {
            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
            val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, if (performed) "? Clicked" else "? Click failed")
            node.recycle()
            root.recycle()
            return performed
        }
        Log.w(TAG, "?? Text not found: $searchText")
        root.recycle()
        return false
    }

    private fun findNodeWithText(node: AccessibilityNodeInfo, searchText: String): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if ((text.contains(searchText, ignoreCase = true) || desc.contains(searchText, ignoreCase = true)) && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeWithText(child, searchText)
                if (found != null) return found
                child.recycle()
            }
        }
        return null
    }

    fun smartClickText(context: Context, searchText: String): Boolean {
        Log.d(TAG, "Smart click request for: $searchText")
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "Accessibility service not available")
            return false
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "No active window root")
            return false
        }
        val pkg = root.packageName?.toString() ?: ""
        val isBrowser = pkg.contains("chrome", ignoreCase = true) ||
            pkg.contains("browser", ignoreCase = true) ||
            pkg.contains("webview", ignoreCase = true)

        val candidates = ArrayList<AccessibilityNodeInfo>()
        val seen = LinkedHashSet<String>()

        fun addCandidate(node: AccessibilityNodeInfo) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}:${node.text}:${node.contentDescription}"
            if (seen.contains(key)) return
            seen.add(key)
            candidates.add(AccessibilityNodeInfo.obtain(node))
        }

        try {
            root.findAccessibilityNodeInfosByText(searchText)?.forEach { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (node.isVisibleToUser || (rect.width() > 0 && rect.height() > 0)) {
                    addCandidate(node)
                }
                node.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native text search failed: ${e.message}")
        }

        findNodesWithTextSmart(root, searchText) { addCandidate(it) }
        root.recycle()

        if (candidates.isEmpty()) {
            Log.w(TAG, "No candidates found for: $searchText")
            return false
        }

        val scored = candidates.map { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val hint = node.hintText?.toString() ?: ""
            var score = if (isExactMatchSmart(text, searchText) || isExactMatchSmart(desc, searchText)) {
                500
            } else if (isSubstringMatchSmart(text, searchText) || isSubstringMatchSmart(desc, searchText) || isSubstringMatchSmart(hint, searchText)) {
                100
            } else {
                0
            }
            if (node.isClickable) {
                score += 50
            } else {
                var parent = node.parent
                var depth = 0
                var clickableAncestor = false
                while (parent != null && depth < 5) {
                    if (parent.isClickable) {
                        clickableAncestor = true
                        break
                    }
                    val next = parent.parent
                    parent.recycle()
                    depth++
                    parent = next
                }
                parent?.recycle()
                if (clickableAncestor) {
                    score += 20
                }
            }
            node to score
        }.sortedByDescending { it.second }

        Log.d(TAG, "Found ${scored.size} candidates. Clicking the best match.")

        var clicked = false
        for ((node, score) in scored) {
            if (clicked) {
                node.recycle()
                continue
            }
            Log.d(TAG, "Trying candidate: text='${node.text}' desc='${node.contentDescription}' score=$score")
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val onScreen = rect.width() > 0 && rect.height() > 0 &&
                centerX in 0..screenWidth && centerY in 0..screenHeight
            val inWebView = isNodeInWebView(node)

            if ((isBrowser || inWebView) && onScreen) {
                Log.d(TAG, "WebView/Chrome detected. Prioritizing Gesture Tap Click at ($centerX, $centerY)")
                clicked = service.performTapGesture(centerX.toFloat(), centerY.toFloat())
                Log.d(TAG, "Gesture Click result: $clicked")
                if (!clicked && node.isClickable) {
                    // RECOVERY NOTE: performAction(16) == ACTION_CLICK
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "WebView Fallback Direct Click result: $clicked")
                }
            } else {
                if (node.isClickable) {
                    if (onScreen) {
                        Handler(Looper.getMainLooper()).post {
                            // RECOVERY NOTE: cross-file — WakeWordService companion Compose state.
                            VoiceService.ghostPointerCoordinates.value = PointF(centerX.toFloat(), centerY.toFloat())
                        }
                    }
                    // RECOVERY NOTE: performAction(16) == ACTION_CLICK
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Strategy 1 (Direct Click) result: $clicked")
                }
                if (!clicked && onScreen) {
                    Log.d(TAG, "Strategy 2 (Gesture Tap) at ($centerX, $centerY)")
                    clicked = service.performTapGesture(centerX.toFloat(), centerY.toFloat())
                    Log.d(TAG, "Strategy 2 result: $clicked")
                }
                if (!clicked) {
                    var parent = node.parent
                    var depth = 0
                    while (parent != null && depth < 5) {
                        if (parent.isClickable) {
                            val pRect = Rect()
                            parent.getBoundsInScreen(pRect)
                            if (pRect.width() > 0 && pRect.height() > 0) {
                                val pCenterX = pRect.centerX().toFloat()
                                val pCenterY = pRect.centerY().toFloat()
                                Handler(Looper.getMainLooper()).post {
                                    VoiceService.ghostPointerCoordinates.value = PointF(pCenterX, pCenterY)
                                }
                            }
                            // RECOVERY NOTE: performAction(16) == ACTION_CLICK
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Strategy 3 (Parent Click at depth $depth) result: $clicked")
                            if (clicked) {
                                parent.recycle()
                                break
                            }
                        }
                        val next = parent.parent
                        parent.recycle()
                        depth++
                        parent = next
                    }
                    parent?.recycle()
                }
            }
            node.recycle()
        }
        return clicked
    }

    private fun findNodesWithTextSmart(
        node: AccessibilityNodeInfo,
        searchText: String,
        onMatch: (AccessibilityNodeInfo) -> Unit,
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visible = node.isVisibleToUser || (rect.width() > 0 && rect.height() > 0)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        if (visible && (isSubstringMatchSmart(text, searchText) || isSubstringMatchSmart(desc, searchText) || isSubstringMatchSmart(hint, searchText))) {
            onMatch(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesWithTextSmart(child, searchText, onMatch)
                child.recycle()
            }
        }
    }

    private fun containsHindiCharacters(text: String): Boolean {
        for (c in text) {
            if (c in '?'..'?') return true
        }
        return false
    }

    private fun transliterateToEnglish(text: String): String {
        if (text.isBlank()) return text
        if (!containsHindiCharacters(text)) {
            return text.lowercase(Locale.ROOT).trim()
        }
        var result = text
        try {
            result = HindiTransliterator.manualTransliterate(text)
        } catch (e: Exception) {
        }
        return result.lowercase(Locale.ROOT).trim()
    }

    private fun cleanTransliteratedText(text: String): String {
        val lower = text.lowercase(Locale.ROOT).trim()
        return if (!lower.endsWith("a") || lower.length <= 3) {
            lower
        } else {
            lower.substring(0, lower.length - 1)
        }
    }

    private fun isExactMatchSmart(val1: String, val2: String): Boolean {
        val a = val1.trim()
        val b = val2.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.equals(b, ignoreCase = true)) return true
        val ta = transliterateToEnglish(a)
        val tb = transliterateToEnglish(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta.equals(tb, ignoreCase = true)) return true
        val ca = cleanTransliteratedText(ta)
        val cb = cleanTransliteratedText(tb)
        return ca.isNotEmpty() && cb.isNotEmpty() && ca.equals(cb, ignoreCase = true)
    }

    private fun isSubstringMatchSmart(val1: String, val2: String): Boolean {
        var a = val1.trim().lowercase(Locale.ROOT)
        var b = val2.trim().lowercase(Locale.ROOT)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.contains(b) || b.contains(a)) return true

        val aHindi = containsHindiCharacters(a)
        val bHindi = containsHindiCharacters(b)
        if (aHindi || bHindi) {
            if (aHindi) a = transliterateToEnglish(a)
            if (bHindi) b = transliterateToEnglish(b)
            if (a.isEmpty() || b.isEmpty()) return false
            if (a.contains(b) || b.contains(a)) return true
            val ca = cleanTransliteratedText(a)
            val cb = cleanTransliteratedText(b)
            if (ca.isEmpty() || cb.isEmpty()) return false
            if (ca.contains(cb) || cb.contains(ca)) return true
            return wordOverlap(ca, cb)
        }
        return wordOverlap(a, b)
    }

    /**
     * True if any word (>2 chars) from one string equals or is contained in a word
     * of the other. Extracted from the decompiled nested-loop match logic.
     */
    private fun wordOverlap(a: String, b: String): Boolean {
        val wordsA = a.split(' ').filter { it.length > 2 }
        val wordsB = b.split(' ').filter { it.length > 2 }
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        for (wa in wordsA) {
            for (wb in wordsB) {
                if (wa == wb || wa.contains(wb) || wb.contains(wa)) return true
            }
        }
        return false
    }

    private fun isNodeInWebView(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            val className = current.className?.toString() ?: ""
            if (className.contains("WebView", ignoreCase = true) || className.contains("Web", ignoreCase = true)) {
                current.recycle()
                return true
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return false
    }
}
