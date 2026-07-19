package com.flexfly.ultron.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.flexfly.ultron.automation.UltronAccessibilityService

/**
 * Fires Google searches via Chrome (with a default-browser fallback) and can open
 * Chrome and drive the query in through the accessibility service.
 *
 * Recovered from `GoogleSearchManager.kt`.
 */
object GoogleSearchManager {

    private const val TAG = "GoogleSearchManager"

    fun search(context: Context, query: String): Boolean {
        val url = "https://www.google.com/search?q=" + Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.setPackage("com.android.chrome")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (intent.resolveActivity(context.packageManager) != null) {
            Log.d(TAG, "✅ Chrome se search: $query")
            context.startActivity(intent)
            return true
        }
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            context.startActivity(fallback)
            Log.d(TAG, "✅ Default browser se search: $query")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Search nahi ho saki: ${e.message}")
            false
        }
    }

    fun openAndTypeInChrome(context: Context, query: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.android.chrome")
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (launchIntent != null) {
            context.startActivity(launchIntent)
            Log.d(TAG, "🌐 Chrome khol diya, accessibility typing shuru hogi")
            Handler(Looper.getMainLooper()).postDelayed({
                UltronAccessibilityService.getInstance()?.searchInChrome(query)
            }, 2000L)
            return
        }
        search(context, query)
    }
}
