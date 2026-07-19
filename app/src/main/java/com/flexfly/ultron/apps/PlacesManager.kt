package com.flexfly.ultron.apps

import android.content.Context
import android.util.Log
import com.flexfly.ultron.info.WeatherManager
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "Nearby places" search backed by the OpenStreetMap Overpass API.
 *
 * Recovered from `PlacesManager.kt`. A spoken query is mapped to an OSM tag filter via
 * [PLACE_TAGS]; if no tag matches we fall back to a fuzzy name search. Results are ranked
 * by Haversine distance from the user's fresh GPS fix and formatted as a Hinglish summary.
 */
object PlacesManager {

    private const val TAG = "PlacesManager"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

    private val PLACE_TAGS: Map<String, String> = mapOf(
        "petrol pump" to "[\"amenity\"=\"fuel\"]",
        "petrol" to "[\"amenity\"=\"fuel\"]",
        "fuel" to "[\"amenity\"=\"fuel\"]",
        "cng" to "[\"amenity\"=\"fuel\"][\"fuel:cng\"=\"yes\"]",
        "hospital" to "[\"amenity\"=\"hospital\"]",
        "clinic" to "[\"amenity\"=\"clinic\"]",
        "pharmacy" to "[\"amenity\"=\"pharmacy\"]",
        "chemist" to "[\"amenity\"=\"pharmacy\"]",
        "doctor" to "[\"amenity\"=\"doctors\"]",
        "atm" to "[\"amenity\"=\"atm\"]",
        "bank" to "[\"amenity\"=\"bank\"]",
        "restaurant" to "[\"amenity\"=\"restaurant\"]",
        "hotel" to "[\"tourism\"=\"hotel\"]",
        "cafe" to "[\"amenity\"=\"cafe\"]",
        "dhaba" to "[\"amenity\"=\"restaurant\"][\"cuisine\"=\"indian\"]",
        "food" to "[\"amenity\"~\"restaurant|fast_food|cafe\"]",
        "bus stop" to "[\"highway\"=\"bus_stop\"]",
        "parking" to "[\"amenity\"=\"parking\"]",
        "ev charging" to "[\"amenity\"=\"charging_station\"]",
        "police" to "[\"amenity\"=\"police\"]",
        "post office" to "[\"amenity\"=\"post_office\"]",
        "school" to "[\"amenity\"=\"school\"]",
        "college" to "[\"amenity\"=\"college\"]",
        "gym" to "[\"leisure\"=\"fitness_centre\"]",
        "park" to "[\"leisure\"=\"park\"]",
        "temple" to "[\"amenity\"=\"place_of_worship\"][\"religion\"=\"hindu\"]",
        "mosque" to "[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]",
        "church" to "[\"amenity\"=\"place_of_worship\"][\"religion\"=\"christian\"]",
        "supermarket" to "[\"shop\"~\"supermarket|grocery\"]",
        "mall" to "[\"shop\"=\"mall\"]",
    )

    /**
     * Resolves a fresh location, then searches for [query] nearby on a background thread.
     * The Hinglish result string is delivered on [onResult].
     *
     * RECOVERY NOTE: the decompiled default for [radiusMeters] was
     * `PathInterpolatorCompat.MAX_NUM_POINTS`, a constant whose value is 3000 — the
     * original literal `3000` (metres) is restored here.
     */
    fun searchNearby(
        context: Context,
        query: String,
        radiusMeters: Int = 3000,
        maxResults: Int = 5,
        onResult: (String) -> Unit,
    ) {
        WeatherManager.getFreshLocation(context) { location ->
            if (location == null) {
                onResult("Boss, location nahi mili — GPS on karo!")
                return@getFreshLocation
            }
            Log.d(TAG, "🔍 Searching '$query' near ${location.latitude}, ${location.longitude}")
            Executors.newSingleThreadExecutor().execute {
                try {
                    val osmTag = findOsmTag(query)
                    val result = if (osmTag != null) {
                        searchByTag(
                            location.latitude, location.longitude,
                            osmTag, radiusMeters, maxResults, query
                        )
                    } else {
                        searchByName(
                            location.latitude, location.longitude,
                            query, radiusMeters, maxResults
                        )
                    }
                    onResult(result)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Search failed: ${e.message}")
                    onResult("Boss, search nahi hua — internet check karo!")
                }
            }
        }
    }

    private fun searchByTag(
        lat: Double,
        lon: Double,
        osmTag: String,
        radius: Int,
        maxResults: Int,
        query: String,
    ): String {
        val overpassQuery = """
            [out:json][timeout:10];
            (
              node$osmTag(around:$radius,$lat,$lon);
              way$osmTag(around:$radius,$lat,$lon);
            );
            out center $maxResults;
        """.trimIndent()
        Log.d(TAG, "📡 Overpass query: $overpassQuery")

        val response = String(
            URL("$OVERPASS_URL?data=" + URLEncoder.encode(overpassQuery, "UTF-8")).readBytes(),
            Charsets.UTF_8
        )
        val elements = JSONObject(response).optJSONArray("elements")
        if (elements == null || elements.length() == 0) {
            return "Boss, aas-paas koi $query nahi mila ${radius / 1000} km mein!"
        }

        val results = ArrayList<Triple<String, Double, String>>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue

            val name = tags.optString("name", "").ifBlank {
                tags.optString("brand", "").ifBlank {
                    if (query.isNotEmpty()) {
                        query[0].toString().uppercase(Locale.ROOT) + query.substring(1)
                    } else {
                        query
                    }
                }
            }

            // Nodes carry lat/lon directly; ways carry a "center" object.
            val placeLat = if (element.has("lat")) {
                element.getDouble("lat")
            } else {
                element.getJSONObject("center").getDouble("lat")
            }
            val placeLon = if (element.has("lon")) {
                element.getDouble("lon")
            } else {
                element.getJSONObject("center").getDouble("lon")
            }
            val distance = haversineDistance(lat, lon, placeLat, placeLon)

            val extras = StringBuilder()
            tags.optString("opening_hours").takeIf { it.isNotBlank() }?.let {
                extras.append("⏰ $it")
            }
            tags.optString("phone").takeIf { it.isNotBlank() }?.let {
                if (extras.isNotEmpty()) extras.append(", ")
                extras.append("📞 $it")
            }

            results.add(Triple(name, distance, extras.toString()))
        }

        if (results.isEmpty()) {
            return "Boss, aas-paas koi $query nahi mila!"
        }

        val sorted = results.sortedBy { it.second }
        val sb = StringBuilder()
        sb.append("Boss, aas-paas ${results.size} $query mile:\n")
        sorted.take(maxResults).forEachIndexed { index, (placeName, distance, extras) ->
            val distanceText = if (distance < 1000.0) {
                "${distance.toInt()} meter"
            } else {
                "${String.format("%.1f", distance / 1000.0)} km"
            }
            sb.append("${index + 1}. $placeName — $distanceText door")
            if (extras.isNotBlank()) {
                sb.append(" ($extras)")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun searchByName(
        lat: Double,
        lon: Double,
        query: String,
        radius: Int,
        maxResults: Int,
    ): String {
        val overpassQuery = """
            [out:json][timeout:10];
            (
              node["name"~"$query",i](around:$radius,$lat,$lon);
              way["name"~"$query",i](around:$radius,$lat,$lon);
            );
            out center $maxResults;
        """.trimIndent()

        val response = String(
            URL("$OVERPASS_URL?data=" + URLEncoder.encode(overpassQuery, "UTF-8")).readBytes(),
            Charsets.UTF_8
        )
        val elements = JSONObject(response).optJSONArray("elements")
        if (elements == null || elements.length() == 0) {
            return "Boss, '$query' naam se kuch nahi mila aas-paas!"
        }

        val results = ArrayList<Pair<String, Double>>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags")
            val name = tags?.optString("name", query) ?: query

            // RECOVERY NOTE: elements with neither direct coords nor a "center" object are
            // skipped (the decompiled control flow incremented the index and fell through
            // without adding a result — reconstructed here as `?: continue`).
            val placeLat = if (element.has("lat")) {
                element.getDouble("lat")
            } else {
                element.optJSONObject("center")?.getDouble("lat") ?: continue
            }
            val placeLon = if (element.has("lon")) {
                element.getDouble("lon")
            } else {
                element.optJSONObject("center")?.getDouble("lon") ?: continue
            }

            results.add(name to haversineDistance(lat, lon, placeLat, placeLon))
        }

        val sorted = results.sortedBy { it.second }
        val sb = StringBuilder()
        sb.append("Boss, '$query' ke liye ${results.size} results mile:\n")
        sorted.take(maxResults).forEachIndexed { index, (placeName, distance) ->
            val distanceText = if (distance < 1000.0) {
                "${distance.toInt()} meter"
            } else {
                "${String.format("%.1f", distance / 1000.0)} km"
            }
            sb.append("${index + 1}. $placeName — $distanceText door\n")
        }
        return sb.toString().trim()
    }

    private fun findOsmTag(query: String): String? {
        val normalized = query.lowercase(Locale.ROOT).trim()
        return PLACE_TAGS.entries.firstOrNull { normalized.contains(it.key) }?.value
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2.0), 2.0) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.pow(Math.sin(Math.toRadians(lon2 - lon1) / 2.0), 2.0)
        return 1.2742e7 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
    }
}
