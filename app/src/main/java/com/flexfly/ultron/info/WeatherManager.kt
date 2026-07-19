package com.flexfly.ultron.info

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.flexfly.ultron.data.AppPreferences
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Current-weather lookup via the OpenWeatherMap API, with layered location resolution:
 * FusedLocationProviderClient first, falling back to a legacy [LocationManager] request,
 * and finally to the last cached fix / configured city.
 *
 * Results are delivered as ready-to-speak Hinglish sentences ([parseWeather]).
 *
 * Recovered from `Weathermanager.kt`.
 */
object WeatherManager {

    private const val TAG = "WeatherManager"
    private const val BASE_URL = "https://api.openweathermap.org/data/2.5/weather"

    /**
     * Resolves a fresh location, then fetches weather for it (or the configured city if no
     * location is available), delivering a spoken-form report to [onResult].
     */
    fun getWeather(context: Context, onResult: (String) -> Unit) {
        val prefs = AppPreferences(context)
        val apiKey = prefs.openWeatherApiKey.trim()
        if (apiKey.isBlank()) {
            onResult("Boss, weather ke liye OpenWeatherMap API key set karo Settings mein!")
            return
        }
        getFreshLocation(context) { location ->
            Executors.newSingleThreadExecutor().execute {
                try {
                    val url = if (location != null) {
                        Log.d(TAG, "📍 Using GPS location: ${location.latitude}, ${location.longitude}")
                        "$BASE_URL?lat=${location.latitude}&lon=${location.longitude}&appid=$apiKey&units=metric&lang=en"
                    } else {
                        val city = prefs.weatherCity.ifBlank { "Ahmedabad,IN" }
                        Log.d(TAG, "📍 No location — using city: $city")
                        "$BASE_URL?q=$city&appid=$apiKey&units=metric&lang=en"
                    }
                    Log.d(TAG, "🌤 Fetching: $url")
                    val json = JSONObject(String(URL(url).readBytes(), Charsets.UTF_8))
                    if (json.optInt("cod", 200) != 200) {
                        onResult("Boss, weather nahi mila — ${json.optString("message", "unknown error")}")
                    } else {
                        onResult(parseWeather(json))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Weather fetch failed: ${e.message}")
                    onResult("Boss, weather abhi nahi mil raha — internet check karo!")
                }
            }
        }
    }

    /**
     * Like [getWeather] but requires a fresh location — reports an error to [onResult]
     * if none can be resolved (never falls back to a city).
     */
    fun getCurrentLocationWeather(context: Context, onResult: (String) -> Unit) {
        val apiKey = AppPreferences(context).openWeatherApiKey.trim()
        if (apiKey.isBlank()) {
            onResult("Boss, weather ke liye OpenWeatherMap API key set karo Settings mein!")
            return
        }
        getFreshLocation(context) { location ->
            if (location == null) {
                onResult("Boss, location nahi mili — GPS on hai? Ek baar Google Maps kholo phir try karo.")
                return@getFreshLocation
            }
            Executors.newSingleThreadExecutor().execute {
                try {
                    val url = "$BASE_URL?lat=${location.latitude}&lon=${location.longitude}&appid=$apiKey&units=metric&lang=en"
                    Log.d(TAG, "📍 Current location weather: $url")
                    onResult(parseWeather(JSONObject(String(URL(url).readBytes(), Charsets.UTF_8))))
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Current location weather failed: ${e.message}")
                    onResult("Boss, weather nahi mila — internet check karo!")
                }
            }
        }
    }

    /** Turns an OpenWeatherMap response into a spoken-form Hinglish weather sentence. */
    private fun parseWeather(json: JSONObject): String {
        val name = json.optString("name", "")
        val main = json.optJSONObject("main")
        val weatherArr = json.optJSONArray("weather")
        val wind = json.optJSONObject("wind")

        val temp = if (main != null) main.optDouble("temp", 0.0).toInt() else 0
        val feelsLike = if (main != null) main.optDouble("feels_like", 0.0).toInt() else 0
        val humidity = main?.optInt("humidity", 0) ?: 0
        val description = weatherArr?.optJSONObject(0)?.optString("description", "") ?: ""
        val windKmh = if (wind != null) (wind.optDouble("speed", 0.0) * 3.6).toInt() else 0

        val place = if (name.isNotEmpty()) "$name mein " else ""
        val windText = if (windKmh > 15) ", hawa $windKmh km/h chal rahi hai" else ""
        val humidityText = when {
            humidity > 75 -> ", umass bhi kaafi hai"
            humidity < 30 -> ", hawa dry hai"
            else -> ""
        }
        val feelsText = if (Math.abs(feelsLike - temp) >= 3) " — feel hoga $feelsLike degree jaisa" else ""

        return "Boss, ${place}abhi $temp degree hai, $description$feelsText$windText$humidityText."
    }

    /**
     * Returns the best (most accurate) cached fix across enabled providers, or null if
     * no location permission / no cached fix.
     */
    fun getLastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_FINE_LOCATION") == 0
        val coarse = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_COARSE_LOCATION") == 0
        if (!fine && !coarse) {
            Log.w(TAG, "⚠️ No location permission")
            return null
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            val providers = buildList {
                if (locationManager.isProviderEnabled("gps")) add("gps")
                if (locationManager.isProviderEnabled("network")) add("network")
                if (locationManager.isProviderEnabled("passive")) add("passive")
            }
            Log.d(TAG, "📍 Enabled providers: $providers")
            var best: Location? = null
            for (provider in providers) {
                try {
                    val cached = locationManager.getLastKnownLocation(provider)
                    if (cached != null) {
                        Log.d(
                            TAG,
                            "📍 Cached from $provider: ${cached.latitude}, ${cached.longitude} " +
                                "age=${(System.currentTimeMillis() - cached.time) / 1000}s"
                        )
                        if (best == null || cached.accuracy < best.accuracy) {
                            best = cached
                        }
                    }
                } catch (_: Exception) {
                }
            }
            Log.d(
                TAG,
                if (best != null) "📍 Best cached: ${best.latitude}, ${best.longitude}" else "📍 No cached location"
            )
            best
        } catch (e: Exception) {
            Log.e(TAG, "❌ Location error: ${e.message}")
            null
        }
    }

    /**
     * Requests a fresh location via FusedLocationProviderClient, falling back to
     * [getLegacyFreshLocation] on timeout/failure. Delivers the result (possibly null)
     * to [onResult]. Fresh fixes are cached to [AppPreferences].
     */
    fun getFreshLocation(context: Context, onResult: (Location?) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_FINE_LOCATION") == 0
        val coarse = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_COARSE_LOCATION") == 0
        if (!fine && !coarse) {
            Log.w(TAG, "⚠️ No location permission")
            onResult(null)
            return
        }
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            fusedClient.lastLocation
                .addOnSuccessListener { cached ->
                    if (cached == null || System.currentTimeMillis() - cached.time >= 120000) {
                        Log.d(TAG, "📍 Fused cached location null or too old, requesting fresh location from FusedLocationProviderClient...")
                        var handled = false
                        val handler = Handler(Looper.getMainLooper())
                        val timeout = Runnable {
                            if (handled) return@Runnable
                            handled = true
                            Log.w(TAG, "⏱ FusedLocation getCurrentLocation timeout — falling back to legacy LocationManager")
                            getLegacyFreshLocation(context, cached, onResult)
                        }
                        handler.postDelayed(timeout, 5000L)
                        fusedClient.getCurrentLocation(100, null)
                            .addOnSuccessListener { fresh ->
                                handler.removeCallbacks(timeout)
                                if (!handled) {
                                    handled = true
                                    if (fresh == null) {
                                        Log.w(TAG, "⚠️ FusedLocation returned null, falling back to legacy LocationManager")
                                        getLegacyFreshLocation(context, cached, onResult)
                                    } else {
                                        Log.d(TAG, "✅ Fresh location from FusedLocation: ${fresh.latitude}, ${fresh.longitude}")
                                        val prefs = AppPreferences(context)
                                        prefs.lastSavedLatitude = fresh.latitude.toString()
                                        prefs.lastSavedLongitude = fresh.longitude.toString()
                                        prefs.lastSavedLocationTime = System.currentTimeMillis()
                                        onResult(fresh)
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                handler.removeCallbacks(timeout)
                                if (!handled) {
                                    handled = true
                                    Log.e(TAG, "❌ FusedLocation getCurrentLocation failed: ${e.message} — falling back to legacy LocationManager")
                                    getLegacyFreshLocation(context, cached, onResult)
                                }
                            }
                    } else {
                        Log.d(TAG, "📍 Using fresh cached location from FusedLocation (age: ${(System.currentTimeMillis() - cached.time) / 1000}s)")
                        val prefs = AppPreferences(context)
                        prefs.lastSavedLatitude = cached.latitude.toString()
                        prefs.lastSavedLongitude = cached.longitude.toString()
                        prefs.lastSavedLocationTime = System.currentTimeMillis()
                        onResult(cached)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ FusedLocation lastLocation failed: ${e.message} — falling back to legacy LocationManager")
                    getLegacyFreshLocation(context, null, onResult)
                }
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Error using FusedLocationProviderClient: ${t.message} — falling back to legacy LocationManager")
            getLegacyFreshLocation(context, null, onResult)
        }
    }

    /**
     * Legacy [LocationManager] fresh-location request. Registers for updates on enabled
     * providers, delivering the first fix (or the [cached] fallback after an 8s timeout).
     */
    private fun getLegacyFreshLocation(
        context: Context,
        cached: Location?,
        onResult: (Location?) -> Unit,
    ) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fallback = cached ?: getLastKnownLocation(context)

        val providers = buildList {
            if (locationManager.isProviderEnabled("network")) add("network")
            if (locationManager.isProviderEnabled("gps")) add("gps")
        }
        if (providers.isEmpty()) {
            Log.e(TAG, "❌ No legacy providers enabled — using cached")
            onResult(fallback)
            return
        }

        Log.d(TAG, "📍 Requesting fresh legacy location from enabled providers: $providers...")
        var handled = false
        Handler(Looper.getMainLooper()).post {
            try {
                val listener = object : LocationListener {
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    }

                    override fun onLocationChanged(location: Location) {
                        if (handled) return
                        handled = true
                        Log.d(TAG, "✅ Fresh legacy location from ${location.provider}: ${location.latitude}, ${location.longitude}")
                        val prefs = AppPreferences(context)
                        prefs.lastSavedLatitude = location.latitude.toString()
                        prefs.lastSavedLongitude = location.longitude.toString()
                        prefs.lastSavedLocationTime = System.currentTimeMillis()
                        locationManager.removeUpdates(this)
                        onResult(location)
                    }
                }
                for (provider in providers) {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (handled) return@postDelayed
                    handled = true
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                    Log.w(TAG, "⏱ Timeout legacy — using cached: ${fallback?.latitude}, ${fallback?.longitude}")
                    onResult(fallback)
                }, 8000L)
            } catch (e: Exception) {
                if (!handled) {
                    handled = true
                    Log.e(TAG, "❌ requestLocationUpdates legacy failed: ${e.message}")
                    onResult(fallback)
                }
            }
        }
    }
}
