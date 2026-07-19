package com.flexfly.ultron.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import java.util.Locale

/**
 * Resolves a spoken app name to an installed app and launches it.
 *
 * Recovered from `AppLaunchManager.kt`. Resolution order:
 *  1. exact [appAliases] hit → launch by package,
 *  2. exact launcher-label match,
 *  3. label-contains-query match,
 *  4. query-contains-label match (label longer than 3 chars),
 *  5. a handful of well-known system intents ([trySystemIntent]).
 *
 * The launchable-app list is cached in [cachedApps] after the first query.
 */
object AppLaunchManager {

    private const val TAG = "AppLaunchManager"

    private var cachedApps: List<ResolveInfo>? = null

    private val appAliases: Map<String, String> = mapOf(
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "snapchat" to "com.snapchat.android",
        "snap" to "com.snapchat.android",
        "telegram" to "org.telegram.messenger",
        "tele" to "org.telegram.messenger",
        "linkedin" to "com.linkedin.android",
        "threads" to "com.instagram.barcelona",
        "pinterest" to "com.pinterest",
        "discord" to "com.discord",
        "reddit" to "com.reddit.frontpage",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "drive" to "com.google.android.apps.docs",
        "google drive" to "com.google.android.apps.docs",
        "meet" to "com.google.android.apps.meetings",
        "google meet" to "com.google.android.apps.meetings",
        "photos" to "com.google.android.apps.photos",
        "google photos" to "com.google.android.apps.photos",
        "calendar" to "com.google.android.calendar",
        "translate" to "com.google.android.apps.translate",
        "google pay" to "com.google.android.apps.nbu.paisa.user",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "play store" to "com.android.vending",
        "play" to "com.android.vending",
        "google" to "com.google.android.googlequicksearchbox",
        "keep" to "com.google.android.keep",
        "google keep" to "com.google.android.keep",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "hotstar" to "in.startv.hotstar",
        "disney hotstar" to "in.startv.hotstar",
        "amazon prime" to "com.amazon.avod.thirdpartyclient",
        "prime video" to "com.amazon.avod.thirdpartyclient",
        "jio cinema" to "com.jio.jioplay.tv",
        "jiosaavn" to "com.jio.media.jiobeats",
        "gaana" to "com.gaana",
        "wynk" to "com.bsbportal.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "yt music" to "com.google.android.apps.youtube.music",
        "mx player" to "com.mxtech.videoplayer.ad",
        "vlc" to "org.videolan.vlc",
        "zee5" to "com.graymatrix.did",
        "sonyliv" to "com.msmpl.livsports",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "meesho" to "com.meesho.supply",
        "myntra" to "com.myntra.android",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app",
        "phone pe" to "com.phonepe.app",
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "blinkit" to "com.grofers.customerapp",
        "grofers" to "com.grofers.customerapp",
        "bigbasket" to "com.bigbasket",
        "zepto" to "com.zeptonow.app",
        "nykaa" to "com.fsn.nykaa",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "calc" to "com.google.android.calculator",
        "files" to "com.google.android.apps.nbu.files",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera2",
        "zerodha" to "com.zerodha.kite3",
        "groww" to "com.nextbillion.groww",
        "upstox" to "in.upstox.app",
        "moneycontrol" to "com.moneycontrol.mc",
        "inshorts" to "com.nis.android",
        "news" to "com.google.android.apps.magazines",
        "uber" to "com.ubercab",
        "ola" to "com.olacabs.customer",
        "rapido" to "com.rapido.passenger",
        "irctc" to "cris.org.in.prs.ima",
        "makemytrip" to "com.makemytrip",
        "mmt" to "com.makemytrip",
        "notion" to "notion.id",
        "zoom" to "us.zoom.videomeetings",
        "teams" to "com.microsoft.teams",
        "microsoft teams" to "com.microsoft.teams",
        "word" to "com.microsoft.office.word",
        "excel" to "com.microsoft.office.excel",
        "powerpoint" to "com.microsoft.office.powerpoint",
        "outlook" to "com.microsoft.office.outlook",
        "onedrive" to "com.microsoft.skydrive",
        "skype" to "com.skype.raider",
        "chatgpt" to "com.openai.chatgpt",
        "claude" to "com.anthropic.claude",
        "pubg" to "com.pubg.imobile",
        "bgmi" to "com.pubg.imobile",
        "freefire" to "com.dts.freefireth",
        "cod" to "com.activision.callofduty.shooter",
        "ludo" to "com.ludo.king",
        "chess" to "com.chess",
        "rummy" to "com.adda52rummy.cards",
    )

    private fun getAllLaunchableApps(context: Context): List<ResolveInfo> {
        cachedApps?.let { return it }

        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(intent, 131072)
        cachedApps = apps
        Log.d(TAG, "Total launchable apps: ${apps.size}")
        for (resolveInfo in apps) {
            Log.d(
                TAG,
                "App: ${resolveInfo.loadLabel(context.packageManager)} | ${resolveInfo.activityInfo.packageName}"
            )
        }
        return apps
    }

    fun launchApp(context: Context, appName: String): LaunchResult {
        val query = appName.lowercase(Locale.ROOT).trim()
        Log.d(TAG, "=== Launch request: '$query' ===")
        val packageManager = context.packageManager

        val aliasPkg = appAliases[query]
        if (aliasPkg != null) {
            Log.d(TAG, "Alias found: $aliasPkg")
            val launchIntent = packageManager.getLaunchIntentForPackage(aliasPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    context.startActivity(launchIntent)
                    Log.d(TAG, "✅ Launched via alias: $aliasPkg")
                    return LaunchResult.Success(appName)
                } catch (e: Exception) {
                    Log.e(TAG, "Alias launch error: ${e.message}")
                }
            }
            Log.w(TAG, "Alias not launchable: $aliasPkg")
            return LaunchResult.NotInstalled(appName, aliasPkg)
        }

        val allApps = getAllLaunchableApps(context)

        val exact = allApps.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).trim() == query
        }
        if (exact != null) {
            Log.d(TAG, "✅ Exact match: ${exact.loadLabel(packageManager)}")
            return launchFromResolveInfo(context, exact, appName)
        }

        val contains = allApps.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).trim().contains(query)
        }
        if (contains != null) {
            Log.d(TAG, "✅ Contains match: ${contains.loadLabel(packageManager)}")
            return launchFromResolveInfo(context, contains, appName)
        }

        val reverse = allApps.firstOrNull {
            val label = it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).trim()
            query.contains(label) && label.length > 3
        }
        if (reverse != null) {
            Log.d(TAG, "✅ Reverse match: ${reverse.loadLabel(packageManager)}")
            return launchFromResolveInfo(context, reverse, appName)
        }

        val systemResult = trySystemIntent(context, query)
        if (systemResult != null) {
            return systemResult
        }

        Log.w(TAG, "❌ No app found for: '$query'")
        return LaunchResult.NotFound(appName)
    }

    private fun launchFromResolveInfo(
        context: Context,
        resolveInfo: ResolveInfo,
        appName: String,
    ): LaunchResult {
        return try {
            val activityInfo = resolveInfo.activityInfo
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setClassName(activityInfo.packageName, activityInfo.name)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
            Log.d(TAG, "✅ Launched: ${activityInfo.packageName}")
            LaunchResult.Success(appName)
        } catch (e: Exception) {
            Log.e(TAG, "Launch error: ${e.message}")
            LaunchResult.Error(appName, e.message ?: "Launch failed")
        }
    }

    private fun trySystemIntent(context: Context, name: String): LaunchResult? {
        val intent: Intent = when {
            name.contains("camera") -> Intent("android.media.action.IMAGE_CAPTURE")
            name.contains("setting") -> Intent("android.settings.SETTINGS")
            name.contains("gallery") || name.contains("photo") ->
                Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
            name.contains("dial") || name == "phone" -> Intent(Intent.ACTION_DIAL)
            name.contains("contact") ->
                Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("content://contacts/people/") }
            name.contains("message") || name == "sms" ->
                Intent(Intent.ACTION_MAIN).apply { addCategory("android.intent.category.APP_MESSAGING") }
            name.contains("browser") || name.contains("internet") ->
                Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("http://") }
            else -> return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                context.startActivity(intent)
                Log.d(TAG, "✅ System intent: $name")
                LaunchResult.Success(name)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "System intent error: ${e.message}")
            null
        }
    }

    fun clearCache() {
        cachedApps = null
    }

    sealed class LaunchResult {
        data class Success(val appName: String) : LaunchResult()
        data class NotFound(val appName: String) : LaunchResult()
        data class NotInstalled(val appName: String, val pkg: String) : LaunchResult()
        data class Error(val appName: String, val reason: String) : LaunchResult()
    }
}
