package com.flexfly.ultron.automation

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * AccessibilityService that injects text and drives the WhatsApp "open chat → type →
 * send" flow in response to broadcast intents.
 *
 * Recovered from `TypingAccessibilityService.kt`. The nested `$$ExternalSyntheticLambda`
 * Runnables (handler post-delays) are restored as inline lambdas, and the
 * `broadcastReceiver$1` inner class as an anonymous `BroadcastReceiver`.
 *
 * RECOVERY NOTE: several `performAction(int)` constants are restored to their named
 * AccessibilityNodeInfo equivalents (see inline notes). The event/flag ints in
 * [onServiceConnected] are the original AccessibilityServiceInfo values.
 */
class TypingAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val MAX_ATTEMPTS = 20

    private var waTaskPending = false
    private var pendingWaNumber: String? = null
    private var pendingWaMessage: String? = null
    private var waAttempts = 0
    private var messageTyped = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TYPE_TEXT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                    Log.d(TAG, "TYPE_TEXT received: $text")
                    handler.postDelayed({ typeText(text) }, 300L)
                }

                ACTION_WA_SEND -> {
                    val number = intent.getStringExtra(EXTRA_WA_NUMBER) ?: return
                    val message = intent.getStringExtra(EXTRA_WA_MESSAGE) ?: return
                    Log.d(TAG, "WA_SEND: number=$number msg=$message")
                    pendingWaNumber = number
                    pendingWaMessage = message
                    waTaskPending = true
                    waAttempts = 0
                    messageTyped = false
                    openWhatsAppChat(number)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility Service connected")
        val info = serviceInfo
        info.eventTypes = 2080
        info.packageNames = null
        info.feedbackType = 16
        info.flags = 80
        info.notificationTimeout = 100L
        serviceInfo = info
        val filter = IntentFilter().apply {
            addAction(ACTION_TYPE_TEXT)
            addAction(ACTION_WA_SEND)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !waTaskPending) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == WHATSAPP_PKG || pkg == WHATSAPP_W4B) {
            waAttempts++
            if (waAttempts > MAX_ATTEMPTS) {
                Log.w(TAG, "Max attempts — giving up")
                resetWaTask()
                return
            }
            Log.d(TAG, "WA event type=${event.eventType} attempt=$waAttempts typed=$messageTyped")
            if (!messageTyped) {
                handler.postDelayed({ tryTypeMessage() }, 1200L)
            } else {
                handler.postDelayed({ trySendButton() }, 600L)
            }
        }
    }

    private fun tryTypeMessage() {
        if (!waTaskPending || messageTyped) return
        val message = pendingWaMessage ?: return
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "tryTypeMessage — root null")
            return
        }
        val input = findWhatsAppInputField(root)
        root.recycle()
        if (input == null) {
            Log.w(TAG, "Input not found — attempt $waAttempts")
            return
        }
        Log.d(TAG, "✅ Input found: id=${input.viewIdResourceName}")
        // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            // RECOVERY NOTE: performAction(1) == ACTION_FOCUS
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            handler.postDelayed({
                val bundle = Bundle().apply {
                    putString(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                }
                // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
                Log.d(TAG, "SET_TEXT result=${input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)}")
                input.recycle()
                handler.postDelayed({ verifyTyped(message) }, 600L)
            }, 250L)
        }, 200L)
    }

    private fun verifyTyped(message: String) {
        val root = rootInActiveWindow ?: return
        val input = findWhatsAppInputField(root)
        val typedText = input?.text?.toString() ?: ""
        input?.recycle()
        root.recycle()
        Log.d(TAG, "Verify text: '$typedText'")
        if (typedText.isNotEmpty()) {
            messageTyped = true
            Log.d(TAG, "✅ Typed!")
            handler.postDelayed({ trySendButton() }, 500L)
        } else {
            Log.w(TAG, "SET_TEXT empty — trying paste")
            tryPaste(message)
        }
    }

    private fun tryPaste(message: String) {
        val clipboard = getSystemService("clipboard") as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("msg", message))
        val root = rootInActiveWindow ?: return
        val input = findWhatsAppInputField(root)
        root.recycle()
        if (input == null) return
        // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            // RECOVERY NOTE: performAction(1) == ACTION_FOCUS
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            handler.postDelayed({
                // RECOVERY NOTE: performAction(32768) == ACTION_PASTE
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "Paste performed")
                input.recycle()
                handler.postDelayed({ verifyPasted() }, 600L)
            }, 200L)
        }, 200L)
    }

    private fun verifyPasted() {
        val root = rootInActiveWindow ?: return
        val input = findWhatsAppInputField(root)
        val typedText = input?.text?.toString() ?: ""
        input?.recycle()
        root.recycle()
        if (typedText.isNotEmpty()) {
            messageTyped = true
            Log.d(TAG, "✅ Pasted!")
            handler.postDelayed({ trySendButton() }, 500L)
            return
        }
        Log.e(TAG, "❌ Both SET_TEXT and paste failed")
    }

    private fun trySendButton() {
        if (!waTaskPending) return
        val root = rootInActiveWindow ?: return
        val sendButton = findSendButton(root)
        root.recycle()
        if (sendButton == null) {
            Log.w(TAG, "Send button not found — attempt $waAttempts")
            return
        }
        // RECOVERY NOTE: performAction(16) == ACTION_CLICK
        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        sendButton.recycle()
        Log.d(TAG, "✅ Message sent!")
        resetWaTask()
    }

    private fun findWhatsAppInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp:id/conversation_entry",
            "com.whatsapp:id/conversation_entry_tray",
            "com.whatsapp:id/input",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp.w4b:id/conversation_entry",
            "com.whatsapp.w4b:id/input",
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isEnabled }
            if (node != null) {
                Log.d(TAG, "Input by ID: $id")
                if (node.isEditable) return node
                val editableChild = findEditableChild(node)
                return if (editableChild != null) {
                    node.recycle()
                    editableChild
                } else {
                    node
                }
            }
        }
        for (hint in listOf("Type a message", "Message", "type a message", "संदेश लिखें")) {
            val node = root.findAccessibilityNodeInfosByText(hint).firstOrNull {
                it.isEditable || (it.className?.contains("EditText") == true)
            }
            if (node != null) {
                Log.d(TAG, "Input by hint: '$hint'")
                return node
            }
        }
        Log.d(TAG, "Input fallback: traversal")
        return findEditableNearBottom(root)
    }

    private fun findEditableChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findEditableChild(child)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    private fun findEditableNearBottom(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 15) return null
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (node.isEditable && bounds.top > screenHeight * 0.35) {
            return node
        }
        val children = ArrayList<AccessibilityNodeInfo>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { children.add(it) }
        }
        // Sort by bounds.top descending (bottom-most first).
        val sorted = children.sortedByDescending {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.top
        }
        for (child in sorted) {
            val found = findEditableNearBottom(child, depth + 1)
            if (found == null) {
                child.recycle()
            } else {
                children.filter { it !== found }.forEach { it.recycle() }
                return found
            }
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_btn",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/conversation_send_button",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp.w4b:id/send_btn",
            "com.whatsapp.w4b:id/send_button",
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isEnabled && it.isClickable }
            if (node != null) {
                Log.d(TAG, "Send by ID: $id")
                return node
            }
        }
        for (desc in listOf("Send", "Send message", "भेजें")) {
            val node = root.findAccessibilityNodeInfosByText(desc)
                .firstOrNull { it.isClickable && it.isEnabled }
            if (node != null) {
                Log.d(TAG, "Send by desc: $desc")
                return node
            }
        }
        return findSendByTraversal(root)
    }

    private fun findSendByTraversal(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val className = node.className?.toString() ?: ""
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            val descHasSend = desc.contains("send")
            val positioned = rect.left > width * 0.65 && rect.top > height * 0.55
            val isButton = className.contains("ImageButton") || className.contains("Button")
            val notOtherControl = !(desc.contains("attach") || desc.contains("emoji") ||
                desc.contains("mic") || desc.contains("camera") ||
                desc.contains("audio") || desc.contains("record"))
            if (descHasSend || (isButton && positioned && notOtherControl)) {
                Log.d(TAG, "Send by traversal: desc='$desc' rect=$rect")
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findSendByTraversal(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun openWhatsAppChat(phoneNumber: String) {
        try {
            var number = Regex("[^0-9+]").replace(phoneNumber, "")
            if (!number.startsWith("+")) {
                number = when {
                    number.length == 10 -> "+91$number"
                    number.startsWith("91") && number.length == 12 -> "+$number"
                    number.startsWith("0") && number.length == 11 -> "+91${number.drop(1)}"
                    else -> "+91$number"
                }
            }
            Log.d(TAG, "Opening WA: $number")
            val uri = Uri.Builder()
                .scheme("https")
                .authority("api.whatsapp.com")
                .path("/send")
                .appendQueryParameter("phone", number)
                .build()
            val packageManager = applicationContext.packageManager
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(WHATSAPP_PKG)
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (packageManager.resolveActivity(intent, 0) != null) {
                applicationContext.startActivity(intent)
                Log.d(TAG, "✅ WA opened")
                return
            }
            val businessIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(WHATSAPP_W4B)
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (packageManager.resolveActivity(businessIntent, 0) != null) {
                applicationContext.startActivity(businessIntent)
                Log.d(TAG, "✅ WA Business opened")
            } else {
                Log.e(TAG, "WhatsApp not found!")
                waTaskPending = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "openWhatsAppChat error: ${e.message}")
            waTaskPending = false
        }
    }

    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val field = findEditableChild(root)
        if (field != null) {
            // RECOVERY NOTE: performAction(1) == ACTION_FOCUS
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val bundle = Bundle().apply {
                putString(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            field.recycle()
            Log.d(TAG, "✅ Typed: $text")
        }
        root.recycle()
    }

    private fun resetWaTask() {
        waTaskPending = false
        pendingWaNumber = null
        pendingWaMessage = null
        waAttempts = 0
        messageTyped = false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
        }
    }

    companion object {
        const val ACTION_TYPE_TEXT = "com.example.nova.TYPE_TEXT"
        const val ACTION_WA_SEND = "com.example.nova.WA_SEND"
        const val EXTRA_TEXT = "text"
        const val EXTRA_WA_MESSAGE = "wa_message"
        const val EXTRA_WA_NUMBER = "wa_number"
        private const val TAG = "TypingAccessService"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_W4B = "com.whatsapp.w4b"

        // RECOVERY NOTE: original companion exposed public getInstance()/setInstance();
        // kept the setter public to preserve that API surface.
        var instance: TypingAccessibilityService? = null

        val isServiceEnabled: Boolean
            get() = instance != null
    }
}
