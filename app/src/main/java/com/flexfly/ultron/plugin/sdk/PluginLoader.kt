package com.flexfly.ultron.plugin.sdk

import android.util.Log

/**
 * Instantiates and validates plugins safely off the main thread.
 */
class PluginLoader {

    companion object {
        private const val TAG = "PluginLoader"
    }

    suspend fun loadPlugin(plugin: Plugin): Boolean {
        return try {
            val meta = plugin.metadata()
            Log.d(TAG, "📦 Loading plugin '${meta.name}' (${meta.pluginId})...")
            val pContext = PluginContext(meta.pluginId)
            val initialized = plugin.initialize(pContext)
            if (initialized) {
                plugin.start()
            } else {
                Log.w(TAG, "⚠️ Plugin '${meta.name}' failed initialization")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading plugin '${plugin.metadata().name}': ${e.message}", e)
            false
        }
    }
}
