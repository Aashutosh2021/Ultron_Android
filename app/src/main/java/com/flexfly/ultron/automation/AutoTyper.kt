package com.flexfly.ultron.automation

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Character-by-character (or paste) text injection into the focused/editable field of
 * the active window, driven through [UltronAccessibilityService].
 *
 * Recovered from `AutoTyper.kt`. The suspend helpers were decompiled as `$1`/`$2`
 * SuspendLambda state machines and are restored here as inline `launch { }` /
 * `withContext { }` bodies.
 */
object AutoTyper {

    private const val TAG = "AutoTyper"

    fun typeText(
        context: Context,
        text: String,
        usePaste: Boolean = false,
        onResult: (Boolean, String) -> Unit,
    ) {
        Log.d(TAG, "⌨️ Typing: '$text' | paste=$usePaste")
        val service = UltronAccessibilityService.getInstance()
        if (service != null) {
            CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                try {
                    val root = service.rootInActiveWindow
                    if (root == null) {
                        Log.e(TAG, "❌ No active window")
                        onResult(false, "Screen nahi mil rahi Boss!")
                        return@launch
                    }
                    val field = findInputField(root)
                    root.recycle()
                    if (field == null) {
                        Log.w(TAG, "⚠️ No input field found")
                        onResult(false, "Input field nahi mila Boss! Pehle kisi text box pe tap karo.")
                        return@launch
                    }
                    Log.d(TAG, "✅ Input field found")
                    if (!field.isFocused) {
                        Log.d(TAG, "🎯 Focusing input field...")
                        // RECOVERY NOTE: performAction(1) == ACTION_FOCUS
                        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        delay(300L)
                    }
                    val success = if (usePaste) {
                        setTextDirectly(field, text)
                    } else {
                        typeCharByChar(field, text)
                    }
                    field.recycle()
                    if (success) {
                        Log.d(TAG, "✅ Text typed successfully")
                        onResult(true, "Type kar diya Boss!")
                    } else {
                        Log.e(TAG, "❌ Typing failed")
                        onResult(false, "Type karne mein problem hui Boss!")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error: ${e.message}")
                    onResult(false, "Type karne mein error aa gayi Boss!")
                }
            }
        } else {
            Log.e(TAG, "❌ Accessibility service not available")
            onResult(false, "Accessibility service nahi chal rahi Boss!")
        }
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findFocusedEditText(root)?.let {
            Log.d(TAG, "🎯 Found focused field")
            return it
        }
        findFirstEditableField(root)?.let {
            Log.d(TAG, "👀 Found visible field")
            return it
        }
        return null
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && isEditableField(node)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findFocusedEditText(child)
                if (found != null) {
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    private fun findFirstEditableField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableField(node) && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findFirstEditableField(child)
                if (found != null) {
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    private fun isEditableField(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        return (className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInputEditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true)) && node.isEditable
    }

    private suspend fun typeCharByChar(node: AccessibilityNodeInfo, text: String): Boolean =
        withContext(Dispatchers.Main) {
            try {
                var currentText = node.text?.toString() ?: ""
                Log.d(TAG, "📝 Starting text: '$currentText'")
                for (char in text) {
                    currentText += char
                    Log.d(TAG, "⌨️ Typing '$char' → Current: '$currentText'")
                    val bundle = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            currentText,
                        )
                    }
                    // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
                    val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    if (!performed) {
                        Log.w(TAG, "⚠️ Character typing failed at '$char' - falling back to paste")
                        return@withContext setTextDirectly(node, text)
                    }
                    delay(80L)
                }
                Log.d(TAG, "✅ Typing complete: '$currentText'")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Typing error: ${e.message}")
                setTextDirectly(node, text)
            }
        }

    private suspend fun setTextDirectly(node: AccessibilityNodeInfo, text: String): Boolean =
        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "⚡ Setting text directly: '$text'")
                val bundle = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                    Log.d(TAG, "✅ Text set successfully")
                    true
                } else {
                    Log.w(TAG, "⚠️ Direct set failed - trying clipboard")
                    tryClipboardPaste(node, text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Set text error: ${e.message}")
                false
            }
        }

    private fun tryClipboardPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            Log.d(TAG, "📋 Trying clipboard paste...")
            // RECOVERY NOTE: performAction(32768) == ACTION_PASTE
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Clipboard paste error: ${e.message}")
            false
        }
    }

    fun clearTextField(onResult: (Boolean) -> Unit) {
        Log.d(TAG, "🗑️ Clearing text field")
        val service = UltronAccessibilityService.getInstance()
        if (service != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val root = service.rootInActiveWindow
                if (root == null) {
                    onResult(false)
                    return@launch
                }
                val field = findInputField(root)
                root.recycle()
                if (field == null) {
                    onResult(false)
                    return@launch
                }
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                // RECOVERY NOTE: performAction(2097152) == ACTION_SET_TEXT
                val performed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                field.recycle()
                onResult(performed)
            }
        } else {
            onResult(false)
        }
    }
}
