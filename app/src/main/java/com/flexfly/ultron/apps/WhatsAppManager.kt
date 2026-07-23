package com.flexfly.ultron.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flexfly.ultron.automation.UltronAccessibilityService
import com.flexfly.ultron.comms.ContactManager
import com.flexfly.ultron.voice.VoiceService

/**
 * Drives WhatsApp for sending messages and placing voice/video calls by resolving a
 * contact, firing the `api.whatsapp.com/send` deep link, then polling the accessibility
 * tree to auto-click the send / call button once the chat screen settles.
 *
 * Recovered from `WhatsAppManager.kt`.
 *
 * RECOVERY NOTE: the decompiler substituted framework constants whose values equal the
 * intended literals — `org.slf4j.Marker.ANY_NON_NULL_MARKER` == "+" and
 * `NotificationCompat.CATEGORY_CALL` == "call". The original literals are restored here.
 */
object WhatsAppManager {

    private const val TAG = "WhatsAppManager"
    private const val WHATSAPP_PKG = "com.whatsapp"
    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_POLL_TIME_MS = 10000L
    private const val MAX_POLL_ATTEMPTS = 50
    private const val STABILIZATION_DELAY_MS = 2500L

    sealed class WhatsAppResult {
        data class Success(val name: String) : WhatsAppResult()
        data class MultipleContacts(val contacts: List<ContactManager.Contact>) : WhatsAppResult()
        data class NotFound(val query: String) : WhatsAppResult()
        data class Error(val reason: String) : WhatsAppResult()
    }

    fun sendMessage(context: Context, contactName: String, message: String): WhatsAppResult {
        Log.d(TAG, "?? sendMessage: contact='$contactName'")
        val contacts = ContactManager.searchContacts(context, contactName)
        if (contacts.isEmpty()) {
            return WhatsAppResult.NotFound(contactName)
        }
        if (contacts.size > 1) {
            return WhatsAppResult.MultipleContacts(contacts)
        }
        openChatWithMessage(context, contacts[0].number, message)
        return WhatsAppResult.Success(contacts[0].name)
    }

    private fun normalizeToE164(raw: String): String {
        val cleaned = Regex("[^\\d+]").replace(raw, "")
        var normalized = if (cleaned.startsWith("+")) {
            "+" + cleaned.replace("+", "")
        } else {
            cleaned.replace("+", "")
        }
        if (!normalized.startsWith("+")) {
            normalized = when {
                normalized.startsWith("00") -> "+" + normalized.substring(2)
                normalized.startsWith("0") -> "+91" + normalized.substring(1)
                normalized.length == 10 -> "+91$normalized"
                // RECOVERY NOTE: the original had `if (len > 10) "+"+x else "+"+x` —
                // both branches were identical, collapsed here.
                else -> "+$normalized"
            }
        }
        Log.d(TAG, "?? Number normalized: '$raw' ? '$normalized'")
        return normalized
    }

    fun sendMessageToNumber(context: Context, name: String, number: String, message: String) {
        Log.d(TAG, "?? sendMessageToNumber: $name ($number)")
        openChatWithMessage(context, number, message)
    }

    fun voiceCall(context: Context, contactName: String): WhatsAppResult {
        Log.d(TAG, "?? WhatsApp voice call: '$contactName'")
        val contacts = ContactManager.searchContacts(context, contactName)
        if (contacts.isEmpty()) {
            return WhatsAppResult.NotFound(contactName)
        }
        if (contacts.size > 1) {
            return WhatsAppResult.MultipleContacts(contacts)
        }
        makeWhatsAppCall(context, contacts[0].number, false)
        return WhatsAppResult.Success(contacts[0].name)
    }

    fun voiceCallToNumber(context: Context, number: String) {
        makeWhatsAppCall(context, number, false)
    }

    fun videoCall(context: Context, contactName: String): WhatsAppResult {
        Log.d(TAG, "?? WhatsApp video call: '$contactName'")
        val contacts = ContactManager.searchContacts(context, contactName)
        if (contacts.isEmpty()) {
            return WhatsAppResult.NotFound(contactName)
        }
        if (contacts.size > 1) {
            return WhatsAppResult.MultipleContacts(contacts)
        }
        makeWhatsAppCall(context, contacts[0].number, true)
        return WhatsAppResult.Success(contacts[0].name)
    }

    fun videoCallToNumber(context: Context, number: String) {
        makeWhatsAppCall(context, number, true)
    }

    private fun makeWhatsAppCall(context: Context, number: String, isVideo: Boolean) {
        val normalized = normalizeToE164(number)
        Log.d(TAG, "?? WhatsApp ${if (isVideo) "video" else "voice"} call ? $normalized")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("https://api.whatsapp.com/send?phone=$normalized")
        intent.setPackage(WHATSAPP_PKG)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.d(TAG, "? WhatsApp intent sent")
            startSmartCallPolling(isVideo, 0, true)
        } catch (e: Exception) {
            Log.e(TAG, "? WhatsApp open failed: ${e.message}")
        }
    }

    private fun openChatWithMessage(context: Context, number: String, message: String) {
        val url = "https://api.whatsapp.com/send?phone=${normalizeToE164(number)}&text=${Uri.encode(message)}"
        Log.d(TAG, "?? Opening WhatsApp: $url")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        intent.setPackage(WHATSAPP_PKG)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.d(TAG, "? WhatsApp intent sent")
            startSmartMessagePolling(0, true)
        } catch (e: Exception) {
            Log.e(TAG, "? WhatsApp open failed: ${e.message}")
        }
    }

    private fun startSmartCallPolling(isVideo: Boolean, attemptCount: Int, firstDetection: Boolean) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "? AccessibilityService not available")
            return
        }
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            Log.e(TAG, "? Timeout: WhatsApp screen not ready")
            Handler(Looper.getMainLooper()).post {
                VoiceService.getActiveInstance()
                    ?.injectReminderMessage("Boss, WhatsApp kholne mein bahut time lag raha hai.")
            }
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            if (attemptCount % 5 == 0) {
                Log.d(TAG, "? Waiting for WhatsApp... (${attemptCount * POLL_INTERVAL_MS}ms)")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartCallPolling(isVideo, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("whatsapp")) {
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartCallPolling(isVideo, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val callButton = findCallButton(root, isVideo)
        if (callButton == null) {
            if (attemptCount % 10 == 0) {
                Log.d(TAG, "? Looking for call button... (${attemptCount * POLL_INTERVAL_MS}ms)")
            }
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartCallPolling(isVideo, attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        if (firstDetection) {
            Log.d(TAG, "? Call button detected! Stabilizing for 2500ms...")
            callButton.recycle()
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartCallPolling(isVideo, 0, false)
            }, STABILIZATION_DELAY_MS)
            return
        }
        Log.d(TAG, "? Stabilization complete - clicking call button")
        Log.d(
            TAG,
            if (callButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "? Call button clicked!" else "? Click failed!"
        )
        callButton.recycle()
        root.recycle()
    }

    private fun startSmartMessagePolling(attemptCount: Int, firstDetection: Boolean) {
        val service = UltronAccessibilityService.getInstance()
        if (service == null) {
            Log.e(TAG, "? AccessibilityService not available")
            return
        }
        if (attemptCount >= MAX_POLL_ATTEMPTS) {
            Log.e(TAG, "? Timeout: WhatsApp screen not ready")
            Handler(Looper.getMainLooper()).post {
                VoiceService.getActiveInstance()
                    ?.injectReminderMessage("Boss, WhatsApp kholne mein bahut time lag raha hai.")
            }
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            if (attemptCount % 5 == 0) {
                Log.d(TAG, "? Waiting for WhatsApp... (${attemptCount * POLL_INTERVAL_MS}ms)")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartMessagePolling(attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("whatsapp")) {
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartMessagePolling(attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        val sendButton = findSendButton(root)
        if (sendButton == null) {
            if (attemptCount % 10 == 0) {
                Log.d(TAG, "? Looking for send button... (${attemptCount * POLL_INTERVAL_MS}ms)")
            }
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartMessagePolling(attemptCount + 1, firstDetection)
            }, POLL_INTERVAL_MS)
            return
        }
        if (firstDetection) {
            Log.d(TAG, "? Send button detected! Stabilizing for 2500ms...")
            sendButton.recycle()
            root.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                startSmartMessagePolling(0, false)
            }, STABILIZATION_DELAY_MS)
            return
        }
        Log.d(TAG, "? Stabilization complete - clicking send button")
        Log.d(
            TAG,
            if (sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "? Message sent!" else "? Click failed!"
        )
        sendButton.recycle()
        root.recycle()
    }

    private fun findCallButton(root: AccessibilityNodeInfo, isVideo: Boolean): AccessibilityNodeInfo? {
        val descriptions = if (isVideo) {
            listOf("Video call", "video call", "Video Call", "?????? ???", "video", "Video")
        } else {
            listOf(
                "Voice call", "voice call", "Voice Call", "Audio call", "audio call",
                "Call", "call", "???? ???", "???"
            )
        }
        findNodeByDescriptions(root, descriptions)?.let { return it }

        val ids = if (isVideo) {
            listOf(
                "com.whatsapp:id/video_call_btn",
                "com.whatsapp:id/videocall_btn",
                "com.whatsapp:id/menuitem_video_call",
                "com.whatsapp:id/video_call"
            )
        } else {
            listOf(
                "com.whatsapp:id/voice_call_btn",
                "com.whatsapp:id/voicecall_btn",
                "com.whatsapp:id/menuitem_voice_call",
                "com.whatsapp:id/voice_call",
                "com.whatsapp:id/call_btn"
            )
        }
        return ids.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isClickable && it.isEnabled }
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByResourceId(
            root,
            listOf("com.whatsapp:id/send", "com.whatsapp:id/send_btn", "com.whatsapp:id/send_button")
        )?.let { return it }
        findByContentDesc(root, listOf("Send", "send", "?????", "Send Message"))?.let { return it }
        return findSendImageButton(root)
    }

    private fun findNodeByDescriptions(
        node: AccessibilityNodeInfo,
        descriptions: List<String>,
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        if (node.isClickable && node.isEnabled) {
            if (descriptions.any { desc.equals(it, ignoreCase = true) }) return node
            if (descriptions.any { text.equals(it, ignoreCase = true) }) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByDescriptions(child, descriptions)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findByResourceId(
        node: AccessibilityNodeInfo,
        ids: List<String>,
    ): AccessibilityNodeInfo? {
        return ids.firstNotNullOfOrNull { id ->
            val nodes = node.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                nodes.firstOrNull { it.isClickable && it.isEnabled }
            } else {
                null
            }
        }
    }

    private fun findByContentDesc(
        node: AccessibilityNodeInfo,
        descs: List<String>,
    ): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val desc = child.contentDescription?.toString() ?: ""
            if (child.isClickable && descs.any { desc.equals(it, ignoreCase = true) }) {
                return child
            }
            val found = findByContentDesc(child, descs)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findSendImageButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val buttons = ArrayList<AccessibilityNodeInfo>()
        collectImageButtons(root, buttons)
        return buttons.lastOrNull()
    }

    private fun collectImageButtons(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        if ((className.contains("ImageButton") || className.contains("ImageView")) &&
            node.isClickable && node.isEnabled
        ) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectImageButtons(child, list)
        }
    }
}
