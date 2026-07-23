package com.flexfly.ultron.comms

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.flexfly.ultron.ai.TranslationManager
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.info.WeatherManager
import com.flexfly.ultron.voice.VoiceService
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Fires an emergency SOS to the user's saved emergency contacts, optionally filtered
 * by a spoken query (name / number fragment), attaching the best-available location.
 *
 * Recovered from `EmergencySOSManager.kt`. Location comes from [WeatherManager]'s
 * fresh-location callback, falling back to the last saved location in [AppPreferences].
 *
 * CROSS-FILE REFS TO VERIFY:
 *  - [WeatherManager]  ? import guessed as `com.flexfly.ultron.info.WeatherManager`; it is an
 *    `object` in the original `com.flexfly.ultron` root (getFreshLocation(context, (Location?) -> Unit)).
 *    Fix the import if it is placed in a different subpackage during conversion.
 *  - [AppPreferences] / `AppPreferences.FavoriteContact` ? `com.flexfly.ultron.data`
 *  - [WakeWordService] ? `com.flexfly.ultron.service`
 *  - [TranslationManager] ? `com.flexfly.ultron.ai`
 */
object EmergencySOSManager {

    private const val TAG = "EmergencySOSManager"
    private val scope = CoroutineScope(Dispatchers.Default)

    fun triggerSOS(context: Context, query: String): String {
        Log.d(TAG, "?? triggerSOS: query='$query'")
        val emergencyContacts = AppPreferences(context).getEmergencyContacts()
        if (emergencyContacts.isEmpty()) {
            return "Boss, aapka koi emergency contact set nahi hai. Kripya app settings mein add karein."
        }

        if (query.isBlank()) {
            scope.launch { sendSOSToSavedContacts(context, emergencyContacts) }
            return "Emergency SOS message sabhi emergency contacts ko bhej rahi hoon Boss!"
        }

        val needle = query.lowercase(Locale.ROOT).trim()
        val matched = emergencyContacts.filter { contact ->
            contact.name.lowercase(Locale.ROOT).contains(needle) ||
                contact.number.replace(" ", "").contains(needle)
        }

        if (matched.isNotEmpty()) {
            scope.launch { sendSOSToSavedContacts(context, matched) }
            return "Emergency SOS message " +
                matched.joinToString(" aur ") { it.name } +
                " ko bhej rahi hoon Boss!"
        }

        scope.launch { sendSOSToSavedContacts(context, emergencyContacts) }
        return "Boss, '$query' emergency contacts mein nahi mila. " +
            "Sabhi emergency contacts ko message bhej rahi hoon Boss!"
    }

    private fun sendSOSToSavedContacts(
        context: Context,
        contacts: List<AppPreferences.FavoriteContact>,
    ) {
        val prefs = AppPreferences(context)
        WeatherManager.getFreshLocation(context) { location: Location? ->
            val locationLink: String? = if (location != null) {
                "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                val lat = prefs.lastSavedLatitude
                val lon = prefs.lastSavedLongitude
                val savedTime = prefs.lastSavedLocationTime
                if (lat.isBlank() || lon.isBlank()) {
                    null
                } else {
                    val minsAgo = (System.currentTimeMillis() - savedTime) / 60000
                    val ageText = when {
                        minsAgo < 1 -> "just now"
                        minsAgo < 60 -> "$minsAgo mins ago"
                        else -> "${minsAgo / 60} hours ago"
                    }
                    "https://maps.google.com/?q=$lat,$lon (Approx location, $ageText)"
                }
            }

            val message = if (locationLink != null) {
                "EMERGENCY! Boss (${prefs.userName}) is in danger! Current location: $locationLink"
            } else {
                "EMERGENCY! Boss (${prefs.userName}) is in danger! (Location could not be fetched immediately)"
            }

            var sentCount = 0
            for (contact in contacts) {
                if (UltronSmsManager.sendSmsToNumber(contact.number, message)) {
                    sentCount++
                }
            }

            val userMessage = if (sentCount > 0) {
                "Boss, " +
                    contacts.joinToString(" aur ") { it.name } +
                    " ko location ke saath emergency SMS bhej diya hai!"
            } else {
                "Boss, emergency SMS send nahi ho paya. Please mobile network check karein."
            }
            notifyUser(context, userMessage)
        }
    }

    private fun notifyUser(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            var text = message
            val userLang = AppPreferences(context).language
                .ifBlank { "Hinglish" }
            if (userLang.lowercase(Locale.ROOT) != "hinglish" &&
                userLang.lowercase(Locale.ROOT) != "hindi"
            ) {
                try {
                    text = runBlocking {
                        TranslationManager.translateToUserLanguage(message, userLang)
                    }
                } catch (ignored: Exception) {
                }
            }
            VoiceService.getActiveInstance()?.injectReminderMessage(text)
        }
    }
}
