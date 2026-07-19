package com.flexfly.ultron.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import java.util.Locale

/**
 * Resolves a spoken app name to a package and launches it.
 *
 * Recovered from `AppOpener.kt`. Lookup order: exact match in [knownApps], then a
 * fuzzy contains-match over the known aliases, then a scan of every installed app's
 * label via [searchAndLaunchApp].
 */
object AppOpener {

    private const val TAG = "AppOpener"

    private val knownApps: Map<String, String> = mapOf(
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "youtube" to "com.google.android.youtube",
        "drive" to "com.google.android.apps.docs",
        "photos" to "com.google.android.apps.photos",
        "calendar" to "com.google.android.calendar",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "prime video" to "com.amazon.avod.thirdpartyclient",
        "hotstar" to "in.startv.hotstar",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "myntra" to "com.myntra.android",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera2",
        "gallery" to "com.google.android.apps.photos",
        "phone" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
    )

    fun openApp(context: Context, appName: String): Boolean {
        val query = appName.lowercase(Locale.ROOT).trim()
        Log.d(TAG, "🚀 Opening app: '$appName'")

        knownApps[query]?.let { return launchApp(context, it) }

        val entry = knownApps.entries.firstOrNull {
            it.key.contains(query) || query.contains(it.key)
        }
        return if (entry != null) {
            launchApp(context, entry.value)
        } else {
            searchAndLaunchApp(context, appName)
        }
    }

    private fun launchApp(context: Context, packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Log.w(TAG, "⚠️ No launch intent for: $packageName")
                return false
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Log.d(TAG, "✅ Launched: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Launch error: ${e.message}")
            false
        }
    }

    private fun searchAndLaunchApp(context: Context, appName: String): Boolean {
        return try {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val lower = appName.lowercase(Locale.ROOT)
            for (appInfo in installed) {
                val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.ROOT)
                if (label.contains(lower) || lower.contains(label)) {
                    val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        Log.d(TAG, "✅ Launched via search: ${appInfo.packageName}")
                        return true
                    }
                }
            }
            Log.w(TAG, "⚠️ App not found: $appName")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Search error: ${e.message}")
            false
        }
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getInstalledApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return installed.mapNotNull { appInfo ->
            if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                pm.getApplicationLabel(appInfo).toString() to appInfo.packageName
            } else {
                null
            }
        }.sortedBy { it.first }
    }
}
