package com.flexfly.ultron.service

import android.app.Notification
import android.content.pm.PackageManager
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class UltronNotificationListener : NotificationListenerService() {

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        if (IGNORED_PACKAGES.contains(packageName) ||
            sbn.isOngoing ||
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        ) {
            return
        }
        val audioManager = getSystemService("audio") as AudioManager
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT ||
            ringerMode == AudioManager.RINGER_MODE_VIBRATE
        ) {
            Log.d(TAG, "🔕 Phone silent/vibrate — skipping notification")
            return
        }
        val bundle = sbn.notification.extras ?: return
        val title = bundle.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()?.trim() ?: ""
        if (title.length < 2) {
            return
        }
        val key = "$packageName:$title"
        val now = System.currentTimeMillis()
        val last = recentNotifications[key]
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            Log.d(TAG, "🔁 Duplicate skipped: $title")
            return
        }
        recentNotifications[key] = now
        if (recentNotifications.size > 100) {
            val cutoff = now - DEDUP_WINDOW_MS
            recentNotifications.entries.removeAll { it.value < cutoff }
        }
        val appName = try {
            val applicationInfo =
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (unused: Exception) {
            packageName.substringAfterLast(".")
        }
        Log.d(TAG, "📬 Notification from $appName: $title")
        onNotificationReceived?.invoke(appName, title, "")
    }

    override fun onListenerConnected() {
        Log.d(TAG, "✅ Notification Listener connected")
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "❌ Notification Listener disconnected")
    }

    companion object {
        private const val DEDUP_WINDOW_MS = 60000L
        private const val TAG = "UltronNotificationListener"
        var onNotificationReceived: ((String, String, String) -> Unit)? = null

        private val IGNORED_PACKAGES = setOf(
            "com.example.nova",
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.google.android.gms",
            "com.android.vending",
            "com.miui.securitycenter",
            "com.android.providers.downloads",
            "com.vivo.pushclient",
            "com.bbk.account"
        )
        private val recentNotifications: MutableMap<String, Long> = LinkedHashMap()
    }
}
