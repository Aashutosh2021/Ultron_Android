package com.flexfly.ultron.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Drives Google Maps for turn-by-turn navigation, dropping a pin, and place search,
 * each with a browser fallback via [openMapsSearch].
 *
 * Recovered from `MapsManager.kt`. All user-facing return strings are Hinglish.
 */
object MapsManager {

    private const val TAG = "MapsManager"

    fun navigateTo(context: Context, query: String): String {
        Log.d(TAG, "🗺 Navigate to: $query")
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=" + Uri.encode(query) + "&mode=d")
        )
        intent.setPackage("com.google.android.apps.maps")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.d(TAG, "✅ Navigation started: $query")
            "Navigation shuru kar diya Boss — '$query' ke liye!"
        } catch (e: Exception) {
            Log.e(TAG, "❌ Navigation failed: ${e.message}")
            openMapsSearch(context, query)
            "Google Maps mein '$query' khol diya Boss!"
        }
    }

    fun showOnMap(context: Context, query: String): String {
        Log.d(TAG, "🗺 Show on map: $query")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
        intent.setPackage("com.google.android.apps.maps")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Map pe '$query' dikhaya Boss!"
        } catch (e: Exception) {
            openMapsSearch(context, query)
            "Maps mein '$query' search kar diya Boss!"
        }
    }

    fun searchPlaces(context: Context, query: String): String {
        Log.d(TAG, "🔍 Places search: $query")
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/" + Uri.encode(query))
        )
        intent.setPackage("com.google.android.apps.maps")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.d(TAG, "✅ Places search opened: $query")
            "Google Maps pe '$query' search kar diya Boss — dekho results!"
        } catch (e: Exception) {
            Log.e(TAG, "❌ Maps search failed: ${e.message}")
            openMapsSearch(context, query)
            "Maps mein '$query' khola Boss!"
        }
    }

    fun searchNearby(context: Context, placeType: String): String {
        Log.d(TAG, "📍 Nearby search: $placeType")
        val query = "nearby $placeType"
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/" + Uri.encode(query))
        )
        intent.setPackage("com.google.android.apps.maps")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Aas-paas ke '$placeType' Maps pe dikha diye Boss!"
        } catch (e: Exception) {
            openMapsSearch(context, query)
            "Maps pe '$placeType' dhundha Boss!"
        }
    }

    private fun openMapsSearch(context: Context, query: String) {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/maps?q=" + Uri.encode(query))
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Browser fallback also failed: ${e.message}")
        }
    }
}
