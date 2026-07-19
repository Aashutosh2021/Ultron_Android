package com.flexfly.ultron.data

import android.content.Context
import android.util.Log
import com.flexfly.ultron.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central SharedPreferences configuration for the app.
 *
 * Recovered from `Appreferences.kt`. Backing store is `max_app_prefs`; the constructor
 * one-time-migrates a handful of keys from the legacy `max_prefs` store. Every property
 * below is a live pass-through to prefs (custom get()/set()), preserving the exact key
 * strings and default values from the original.
 *
 * RECOVERY NOTE: the decompiler substituted `HttpUrl.PATH_SEGMENT_ENCODE_SET_URI`
 * (== "[]") for the empty-JSON-array default in the contacts/memories getters. The
 * original literal "[]" is restored here.
 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("max_app_prefs", Context.MODE_PRIVATE)

    init {
        // One-time migration from the legacy "max_prefs" store to "max_app_prefs".
        val old = context.getSharedPreferences("max_prefs", Context.MODE_PRIVATE)

        if (old.contains("access_key") && !prefs.contains("access_key")) {
            val v = old.getString("access_key", "") ?: ""
            if (v.isNotBlank()) {
                prefs.edit().putString("access_key", v).apply()
                Log.d(TAG, "Migrated access_key from old max_prefs to max_app_prefs")
            }
        }
        if (old.contains("user_name") && !prefs.contains("user_name")) {
            val v = old.getString("user_name", "") ?: ""
            if (v.isNotBlank()) {
                prefs.edit().putString("user_name", v).apply()
            }
        }
        if (old.contains("language") && !prefs.contains("language")) {
            val v = old.getString("language", "") ?: ""
            if (v.isNotBlank()) {
                prefs.edit().putString("language", v).apply()
            }
        }
        if (old.contains("google_api_key") && !prefs.contains("google_api_key")) {
            val v = old.getString("google_api_key", "") ?: ""
            if (v.isNotBlank()) {
                prefs.edit().putString("google_api_key", v).apply()
            }
        }
    }

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) { prefs.edit().putBoolean("service_enabled", value).apply() }

    var permissionsRequested: Boolean
        get() = prefs.getBoolean("permissions_requested", false)
        set(value) { prefs.edit().putBoolean("permissions_requested", value).apply() }

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) { prefs.edit().putString("user_name", value).apply() }

    var onboardingName: String
        get() = prefs.getString("onboarding_name", "") ?: ""
        set(value) { prefs.edit().putString("onboarding_name", value).apply() }

    var onboardingLanguage: String
        get() = prefs.getString("onboarding_language", "") ?: ""
        set(value) { prefs.edit().putString("onboarding_language", value).apply() }

    var onboardingAccessKey: String
        get() = prefs.getString("onboarding_access_key", "") ?: ""
        set(value) { prefs.edit().putString("onboarding_access_key", value).apply() }

    var onboardingGoogleApiKey: String
        get() = prefs.getString("onboarding_google_api_key", "") ?: ""
        set(value) { prefs.edit().putString("onboarding_google_api_key", value).apply() }

    var onboardingCurrentStep: Int
        get() = prefs.getInt("onboarding_current_step", 0)
        set(value) { prefs.edit().putInt("onboarding_current_step", value).apply() }

    var onboardingKeyVerified: Boolean
        get() = prefs.getBoolean("onboarding_key_verified", false)
        set(value) { prefs.edit().putBoolean("onboarding_key_verified", value).apply() }

    fun clearOnboardingCache() {
        prefs.edit()
            .remove("onboarding_name")
            .remove("onboarding_language")
            .remove("onboarding_access_key")
            .remove("onboarding_google_api_key")
            .remove("onboarding_current_step")
            .remove("onboarding_key_verified")
            .apply()
    }

    var toolAutoType: Boolean
        get() = prefs.getBoolean("tool_auto_type", true)
        set(value) { prefs.edit().putBoolean("tool_auto_type", value).apply() }

    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) { prefs.edit().putString("language", value).apply() }

    var accessKey: String
        get() = prefs.getString("access_key", "") ?: ""
        set(value) { prefs.edit().putString("access_key", value).apply() }

    var googleApiKey: String
        get() = prefs.getString("google_api_key", "") ?: ""
        set(value) { prefs.edit().putString("google_api_key", value).apply() }

    var llamaVisionApiKey: String
        get() = prefs.getString("llama_vision_api_key", "") ?: ""
        set(value) { prefs.edit().putString("llama_vision_api_key", value).apply() }

    var toolScreenReader: Boolean
        get() = prefs.getBoolean("tool_screen_reader", true)
        set(value) { prefs.edit().putBoolean("tool_screen_reader", value).apply() }

    // RECOVERY NOTE: externalized to BuildConfig (was the literal "744714b5afd21123660d46655a3a8621").
    var openWeatherApiKey: String
        get() = prefs.getString("openweather_api_key", BuildConfig.OPENWEATHER_DEFAULT_KEY) ?: ""
        set(value) { prefs.edit().putString("openweather_api_key", value).apply() }

    var weatherCity: String
        get() = prefs.getString("weather_city", "Ahmedabad,IN") ?: "Ahmedabad,IN"
        set(value) { prefs.edit().putString("weather_city", value).apply() }

    var callControlEnabled: Boolean
        get() = prefs.getBoolean("call_control_enabled", false)
        set(value) { prefs.edit().putBoolean("call_control_enabled", value).apply() }

    var toolCallAnnounce: Boolean
        get() = prefs.getBoolean("tool_call_announce", true)
        set(value) { prefs.edit().putBoolean("tool_call_announce", value).apply() }

    var toolNotifications: Boolean
        get() = prefs.getBoolean("tool_notifications", true)
        set(value) { prefs.edit().putBoolean("tool_notifications", value).apply() }

    var toolCamera: Boolean
        get() = prefs.getBoolean("tool_camera", true)
        set(value) { prefs.edit().putBoolean("tool_camera", value).apply() }

    var toolFlashlight: Boolean
        get() = prefs.getBoolean("tool_flashlight", true)
        set(value) { prefs.edit().putBoolean("tool_flashlight", value).apply() }

    var toolWhatsApp: Boolean
        get() = prefs.getBoolean("tool_whatsapp", true)
        set(value) { prefs.edit().putBoolean("tool_whatsapp", value).apply() }

    // RECOVERY NOTE: original setter writes the literal `true`, not `value` (a bug faithfully preserved).
    var toolSMS: Boolean
        get() = prefs.getBoolean("tool_sms", true)
        set(value) { prefs.edit().putBoolean("tool_sms", true).apply() }

    var toolMaps: Boolean
        get() = prefs.getBoolean("tool_maps", true)
        set(value) { prefs.edit().putBoolean("tool_maps", value).apply() }

    var toolYouTube: Boolean
        get() = prefs.getBoolean("tool_youtube", true)
        set(value) { prefs.edit().putBoolean("tool_youtube", value).apply() }

    var toolVolumeControl: Boolean
        get() = prefs.getBoolean("tool_volume", true)
        set(value) { prefs.edit().putBoolean("tool_volume", value).apply() }

    var toolBrightnessControl: Boolean
        get() = prefs.getBoolean("tool_brightness", true)
        set(value) { prefs.edit().putBoolean("tool_brightness", value).apply() }

    var toolNetworkToggles: Boolean
        get() = prefs.getBoolean("tool_network", true)
        set(value) { prefs.edit().putBoolean("tool_network", value).apply() }

    var toolReminders: Boolean
        get() = prefs.getBoolean("tool_reminders", true)
        set(value) { prefs.edit().putBoolean("tool_reminders", value).apply() }

    var toolSpotify: Boolean
        get() = prefs.getBoolean("tool_spotify", true)
        set(value) { prefs.edit().putBoolean("tool_spotify", value).apply() }

    var toolPhoneCalls: Boolean
        get() = prefs.getBoolean("tool_phone_calls", true)
        set(value) { prefs.edit().putBoolean("tool_phone_calls", value).apply() }

    // RECOVERY NOTE: original setter writes the literal `true`, not `value` (a bug faithfully preserved).
    var toolSystemInfo: Boolean
        get() = prefs.getBoolean("tool_system_info", true)
        set(value) { prefs.edit().putBoolean("tool_system_info", true).apply() }

    var toolWeather: Boolean
        get() = prefs.getBoolean("tool_weather", true)
        set(value) { prefs.edit().putBoolean("tool_weather", value).apply() }

    var toolLocation: Boolean
        get() = prefs.getBoolean("tool_location", true)
        set(value) { prefs.edit().putBoolean("tool_location", value).apply() }

    var toolAppLauncher: Boolean
        get() = prefs.getBoolean("tool_app_launcher", true)
        set(value) { prefs.edit().putBoolean("tool_app_launcher", value).apply() }

    var toolNews: Boolean
        get() = prefs.getBoolean("tool_news", true)
        set(value) { prefs.edit().putBoolean("tool_news", value).apply() }

    var toolSearch: Boolean
        get() = prefs.getBoolean("tool_search", true)
        set(value) { prefs.edit().putBoolean("tool_search", value).apply() }

    var toolWebSearch: Boolean
        get() = prefs.getBoolean("tool_web_search", true)
        set(value) { prefs.edit().putBoolean("tool_web_search", value).apply() }

    var maxActiveTimeout: Int
        get() = prefs.getInt("max_active_timeout", 15)
        set(value) { prefs.edit().putInt("max_active_timeout", value).apply() }

    var lastCapturedPhotoUri: String
        get() = prefs.getString("last_captured_photo_uri", "") ?: ""
        set(value) { prefs.edit().putString("last_captured_photo_uri", value).apply() }

    // --- Favorite contacts (List<FavoriteContact> persisted as JSON) ---

    fun getFavoriteContacts(): List<FavoriteContact> {
        val json = prefs.getString("favorite_contacts", "[]") ?: "[]"
        val result = ArrayList<FavoriteContact>()
        return try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    FavoriteContact(
                        name = obj.getString("name"),
                        number = obj.getString("number"),
                        label = obj.optString("label", ""),
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getFavoriteContacts error: ${e.message}")
            result
        }
    }

    fun saveFavoriteContacts(contacts: List<FavoriteContact>) {
        try {
            val array = JSONArray()
            for (contact in contacts) {
                val obj = JSONObject()
                obj.put("name", contact.name)
                obj.put("number", contact.number)
                obj.put("label", contact.label)
                array.put(obj)
            }
            prefs.edit().putString("favorite_contacts", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveFavoriteContacts error: ${e.message}")
        }
    }

    fun addFavoriteContact(contact: FavoriteContact) {
        val list = getFavoriteContacts().toMutableList()
        if (list.any { it.number == contact.number }) return
        list.add(contact)
        saveFavoriteContacts(list)
    }

    fun removeFavoriteContact(number: String) {
        val list = getFavoriteContacts().toMutableList()
        list.removeAll { it.number == number }
        saveFavoriteContacts(list)
    }

    fun isFavorite(number: String): Boolean =
        getFavoriteContacts().any { it.number == number }

    // --- Emergency contacts (List<FavoriteContact> persisted as JSON) ---

    fun getEmergencyContacts(): List<FavoriteContact> {
        val json = prefs.getString("emergency_contacts", "[]") ?: "[]"
        val result = ArrayList<FavoriteContact>()
        return try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    FavoriteContact(
                        name = obj.getString("name"),
                        number = obj.getString("number"),
                        label = obj.optString("label", "Emergency"),
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getEmergencyContacts error: ${e.message}")
            result
        }
    }

    fun saveEmergencyContacts(contacts: List<FavoriteContact>) {
        try {
            val array = JSONArray()
            for (contact in contacts) {
                val obj = JSONObject()
                obj.put("name", contact.name)
                obj.put("number", contact.number)
                obj.put("label", contact.label)
                array.put(obj)
            }
            prefs.edit().putString("emergency_contacts", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveEmergencyContacts error: ${e.message}")
        }
    }

    fun addEmergencyContact(contact: FavoriteContact) {
        val list = getEmergencyContacts().toMutableList()
        if (list.any { it.number == contact.number }) return
        list.add(contact)
        saveEmergencyContacts(list)
    }

    fun removeEmergencyContact(number: String) {
        val list = getEmergencyContacts().toMutableList()
        list.removeAll { it.number == number }
        saveEmergencyContacts(list)
    }

    var lastSavedLatitude: String
        get() = prefs.getString("last_saved_latitude", "") ?: ""
        set(value) { prefs.edit().putString("last_saved_latitude", value).apply() }

    var lastSavedLongitude: String
        get() = prefs.getString("last_saved_longitude", "") ?: ""
        set(value) { prefs.edit().putString("last_saved_longitude", value).apply() }

    var lastSavedLocationTime: Long
        get() = prefs.getLong("last_saved_location_time", 0L)
        set(value) { prefs.edit().putLong("last_saved_location_time", value).apply() }

    var lastIntruderDescription: String
        get() = prefs.getString("last_intruder_description", "") ?: ""
        set(value) { prefs.edit().putString("last_intruder_description", value).apply() }

    // --- Memories (List<String> persisted as JSON) ---

    fun getMemories(): List<String> {
        val json = prefs.getString("user_memories", "[]") ?: "[]"
        val result = ArrayList<String>()
        return try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                result.add(array.getString(i))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getMemories error: ${e.message}")
            result
        }
    }

    fun saveMemories(memories: List<String>) {
        try {
            val array = JSONArray()
            for (memory in memories) {
                array.put(memory)
            }
            prefs.edit().putString("user_memories", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveMemories error: ${e.message}")
        }
    }

    fun addMemory(memory: String) {
        val list = getMemories().toMutableList()
        if (list.contains(memory)) return
        list.add(memory)
        saveMemories(list)
    }

    fun clearMemories() {
        prefs.edit().remove("user_memories").apply()
    }

    data class FavoriteContact(
        val name: String,
        val number: String,
        val label: String = "",
    )

    companion object {
        private const val TAG = "AppPreferences"
    }
}
