package com.flexfly.ultron.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flexfly.ultron.automation.UltronAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Toggles WiFi / mobile data / Bluetooth / hotspot / airplane mode by opening the relevant
 * Settings screen and driving the on-screen switch through [UltronAccessibilityService].
 *
 * Recovered from `Networktogglemanager.kt`. Because Android forbids programmatic radio
 * toggles on modern versions, this launches the settings panel then hunts for the toggle
 * with five fallback strategies ([findAndClickToggleUltraStrong]).
 *
 * The decompiled `$findToggleAfterOpen$1` state machine was the coroutine retry-loop body;
 * it is folded back into [findToggleAfterOpen] as a `launch { }` block.
 *
 * RECOVERY NOTE: `performGlobalAction(2)` == [AccessibilityService.GLOBAL_ACTION_BACK];
 * `performAction(16)` == [AccessibilityNodeInfo.ACTION_CLICK];
 * `268435456` == [Intent.FLAG_ACTIVITY_NEW_TASK].
 * The retry loop in [findToggleAfterOpen] was structurally broken in the decompile (jadx
 * flagged "decompiled incorrectly"); reconstructed as: 1800ms settle, up to 15 attempts of
 * (grab root → try click → recycle), with 300ms + 400ms delays per attempt, then BACK.
 */
object NetworkToggleManager {

    private const val TAG = "NetworkToggle"

    fun toggleWifi(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "🔄 Toggle WiFi")
        openSettingAndToggle(
            context,
            "android.settings.WIFI_SETTINGS",
            listOf("wi-fi", "wifi", "वाई-फाई", "wlan"),
            onResult,
        )
    }

    fun toggleMobileData(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "🔄 Toggle Mobile Data")
        openMobileDataAndToggle(context, onResult)
    }

    fun toggleBluetooth(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "🔄 Toggle Bluetooth")
        openSettingAndToggle(
            context,
            "android.settings.BLUETOOTH_SETTINGS",
            listOf("bluetooth", "ब्लूटूथ", "bt"),
            onResult,
        )
    }

    fun toggleHotspot(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "🔄 Toggle Hotspot")
        openHotspotAndToggle(context, onResult)
    }

    fun toggleAirplaneMode(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "🔄 Toggle Airplane Mode")
        openSettingAndToggle(
            context,
            "android.settings.AIRPLANE_MODE_SETTINGS",
            listOf("airplane", "flight", "aeroplane", "एयरप्लेन", "हवाई जहाज"),
            onResult,
        )
    }

    fun toggle(context: Context, query: String, onResult: ((Boolean) -> Unit)? = null) {
        val q = query.lowercase(Locale.ROOT)
        when {
            q.contains("wifi") || q.contains("wi-fi") -> toggleWifi(context, onResult)
            q.contains("bluetooth") || q.contains("bt") -> toggleBluetooth(context, onResult)
            q.contains("hotspot") -> toggleHotspot(context, onResult)
            q.contains("airplane") || q.contains("flight") -> toggleAirplaneMode(context, onResult)
            q.contains("data") || q.contains("mobile") -> toggleMobileData(context, onResult)
            else -> toggleWifi(context, onResult)
        }
    }

    private fun openMobileDataAndToggle(context: Context, onResult: ((Boolean) -> Unit)?) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "❌ Accessibility service not available")
            onResult?.invoke(false)
            return
        }

        var launched = false
        for (intent in buildMobileDataIntents()) {
            try {
                context.startActivity(intent)
                Log.d(TAG, "✅ Launched: ${intent.action}")
                launched = true
                break
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Failed: ${intent.action}")
            }
        }

        if (!launched) {
            Log.e(TAG, "❌ All intents failed")
            onResult?.invoke(false)
            return
        }

        findToggleAfterOpen(
            service,
            listOf(
                "mobile data", "data", "cellular", "डेटा", "internet", "4g", "5g", "lte",
                "3g", "use mobile data", "मोबाइल डेटा", "enable data", "data connection",
                "cellular data", "packet data",
            ),
            onResult,
        )
    }

    private fun buildMobileDataIntents(): List<Intent> {
        val intents = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= 29) {
            intents.add(
                Intent("android.settings.panel.action.INTERNET_CONNECTIVITY")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        intents.add(
            Intent().apply {
                setClassName("com.android.settings", "com.android.settings.Settings\$DataUsageSummaryActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        intents.add(Intent("android.settings.DATA_ROAMING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        intents.add(Intent("android.settings.DATA_ROAMING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        intents.add(Intent("android.settings.NETWORK_OPERATOR_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        intents.add(Intent("android.settings.WIRELESS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return intents
    }

    private fun openHotspotAndToggle(context: Context, onResult: ((Boolean) -> Unit)?) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "❌ Accessibility service not available")
            onResult?.invoke(false)
            return
        }

        var launched = false
        for (intent in buildHotspotIntents()) {
            try {
                context.startActivity(intent)
                Log.d(TAG, "✅ Launched: ${intent.action}")
                launched = true
                break
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Failed: ${intent.action}")
            }
        }

        if (!launched) {
            Log.e(TAG, "❌ All intents failed")
            onResult?.invoke(false)
            return
        }

        findToggleAfterOpen(
            service,
            listOf(
                "hotspot", "tethering", "हॉटस्पॉट", "portable hotspot", "mobile hotspot",
                "personal hotspot", "wi-fi hotspot", "internet sharing", "wifi hotspot",
                "tether", "share internet",
            ),
            onResult,
        )
    }

    private fun buildHotspotIntents(): List<Intent> {
        val intents = ArrayList<Intent>()
        intents.add(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        intents.add(
            Intent().apply {
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        intents.add(
            Intent().apply {
                setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        intents.add(Intent("android.settings.WIRELESS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return intents
    }

    private fun openSettingAndToggle(
        context: Context,
        settingsAction: String,
        toggleKeywords: List<String>,
        onResult: ((Boolean) -> Unit)?,
    ) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "❌ Accessibility service not available")
            onResult?.invoke(false)
            return
        }
        try {
            val intent = Intent(settingsAction)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "✅ Launched: $settingsAction")
            findToggleAfterOpen(service, toggleKeywords, onResult)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Launch failed: ${e.message}")
            onResult?.invoke(false)
        }
    }

    private fun findToggleAfterOpen(
        service: UltronAccessibilityService,
        keywords: List<String>,
        onResult: ((Boolean) -> Unit)?,
    ) {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            try {
                delay(1800L)

                var clicked = false
                var attemptCount = 0
                val maxAttempts = 15

                while (!clicked && attemptCount < maxAttempts) {
                    val root = service.rootInActiveWindow
                    if (root != null) {
                        clicked = findAndClickToggleUltraStrong(root, keywords)
                        root.recycle()
                        if (clicked) {
                            Log.d(TAG, "✅ Toggle clicked successfully!")
                        }
                    }
                    attemptCount++
                    delay(300L)
                    delay(400L)
                }

                // RECOVERY NOTE: performGlobalAction(2) == GLOBAL_ACTION_BACK — dismiss settings.
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (!clicked) {
                    Log.w(TAG, "⚠️ Toggle not found after $attemptCount attempts")
                }
                onResult?.invoke(clicked)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error: ${e.message}")
                onResult?.invoke(false)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun findAndClickToggleUltraStrong(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
    ): Boolean {
        Log.d(TAG, "🔍 Searching for toggle with keywords: ${keywords.joinToString()}")

        findSwitchByKeywordDirect(root, keywords)?.let { node ->
            Log.d(TAG, "✅ Strategy 1: Direct switch found")
            val clicked = clickNode(node)
            node.recycle()
            if (clicked) return true
        }

        findSwitchByLabel(root, keywords)?.let { node ->
            Log.d(TAG, "✅ Strategy 2: Label-based switch found")
            val clicked = clickNode(node)
            node.recycle()
            if (clicked) return true
        }

        findClickableRowWithKeyword(root, keywords)?.let { node ->
            Log.d(TAG, "✅ Strategy 3: Clickable row found")
            val clicked = clickNode(node)
            node.recycle()
            if (clicked) return true
        }

        findToggleByScoring(root, keywords)?.let { node ->
            Log.d(TAG, "✅ Strategy 4: Scored toggle found")
            val clicked = clickNode(node)
            node.recycle()
            if (clicked) return true
        }

        findFirstVisibleSwitch(root)?.let { node ->
            Log.d(TAG, "✅ Strategy 5: First switch found (fallback)")
            val clicked = clickNode(node)
            node.recycle()
            if (clicked) return true
        }

        Log.w(TAG, "⚠️ All strategies failed")
        return false
    }

    private fun findSwitchByKeywordDirect(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
    ): AccessibilityNodeInfo? {
        if (isSwitchNode(node) && node.isEnabled) {
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (keywords.any { desc.contains(it) || text.contains(it) }) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSwitchByKeywordDirect(child, keywords)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findSwitchByLabel(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
    ): AccessibilityNodeInfo? {
        val labelNode = findNodeWithKeyword(root, keywords) ?: return null

        labelNode.parent?.let { parent ->
            val switch = findSwitchInSubtree(parent)
            if (switch != null) {
                parent.recycle()
                labelNode.recycle()
                return switch
            }
            parent.recycle()
        }

        labelNode.parent?.let { parent ->
            parent.parent?.let { grandparent ->
                val switch = findSwitchInSubtree(grandparent)
                if (switch != null) {
                    grandparent.recycle()
                    parent.recycle()
                    labelNode.recycle()
                    return switch
                }
                grandparent.recycle()
            }
            parent.recycle()
        }

        labelNode.recycle()
        return null
    }

    private fun findClickableRowWithKeyword(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
    ): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled && hasKeywordInSubtree(node, keywords)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableRowWithKeyword(child, keywords)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findToggleByScoring(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
    ): AccessibilityNodeInfo? {
        val toggles = ArrayList<AccessibilityNodeInfo>()
        collectAllToggles(root, toggles)
        if (toggles.isEmpty()) {
            return null
        }
        Log.d(TAG, "🎯 Scoring ${toggles.size} toggle candidates")

        val scored = toggles.map { it to scoreToggle(it, keywords) }

        scored.sortedByDescending { it.second }.take(3).forEachIndexed { index, pair ->
            val (node, score) = pair
            val desc = node.contentDescription ?: node.text
            Log.d(TAG, "  #${index + 1} score=$score desc=$desc")
        }

        val best = scored.maxByOrNull { it.second }
        if (best == null || best.second < 10) {
            return null
        }
        return best.first
    }

    private fun collectAllToggles(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (isSwitchNode(node) && node.isEnabled) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllToggles(it, list) }
        }
    }

    private fun scoreToggle(toggle: AccessibilityNodeInfo, keywords: List<String>): Int {
        val desc = toggle.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = toggle.text?.toString()?.lowercase(Locale.ROOT) ?: ""

        var score = 0
        if (keywords.any { desc.contains(it) || text.contains(it) }) {
            score = 50
        }

        toggle.parent?.let { parent ->
            if (hasKeywordInSubtree(parent, keywords)) {
                score += 30
            }
            parent.recycle()
        }

        if (toggle.isVisibleToUser) {
            score += 10
        }

        return if (toggle.isEnabled) score + 10 else score
    }

    private fun findFirstVisibleSwitch(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchNode(node) && node.isEnabled && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstVisibleSwitch(child)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun isSwitchNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        return cls.contains("Switch", ignoreCase = true) ||
            cls.contains("ToggleButton", ignoreCase = true) ||
            cls.contains("CheckBox", ignoreCase = true) ||
            cls.contains("CompoundButton", ignoreCase = true)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        if (!node.isEnabled || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return false
        }
        val label = node.contentDescription ?: node.text
        Log.d(TAG, "✅ Node clicked: $label")
        return true
    }

    private fun findNodeWithKeyword(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        if (keywords.any { desc.contains(it) || text.contains(it) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeWithKeyword(child, keywords)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findSwitchInSubtree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchNode(node) && node.isEnabled) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSwitchInSubtree(child)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun hasKeywordInSubtree(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        if (keywords.any { desc.contains(it) || text.contains(it) }) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = hasKeywordInSubtree(child, keywords)
            child.recycle()
            if (found) {
                return true
            }
        }
        return false
    }
}
