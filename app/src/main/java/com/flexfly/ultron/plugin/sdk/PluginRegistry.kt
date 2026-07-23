package com.flexfly.ultron.plugin.sdk

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-Safe Dynamic Plugin Registry.
 */
class PluginRegistry {

    companion object {
        private const val TAG = "PluginRegistry"
    }

    private val pluginsMap = ConcurrentHashMap<String, Plugin>()

    fun register(plugin: Plugin) {
        val meta = plugin.metadata()
        pluginsMap[meta.pluginId.lowercase()] = plugin
        pluginsMap[meta.name.lowercase()] = plugin
        Log.d(TAG, "🔌 Registered Plugin: ${meta.name} (${meta.pluginId}) [v${meta.version}]")
    }

    fun unregister(identifier: String) {
        val removed = pluginsMap.remove(identifier.lowercase())
        if (removed != null) {
            Log.d(TAG, "🧹 Unregistered Plugin: $identifier")
        }
    }

    fun findById(id: String): Plugin? {
        return pluginsMap[id.lowercase()]
    }

    fun findByCapability(capability: PluginCapability): List<Plugin> {
        return pluginsMap.values.distinctBy { it.metadata().pluginId }.filter { plugin ->
            plugin.supportedCapabilities().contains(capability)
        }
    }

    fun getAllPlugins(): List<Plugin> {
        return pluginsMap.values.distinctBy { it.metadata().pluginId }
    }

    fun clear() {
        pluginsMap.clear()
        Log.d(TAG, "🧹 Cleared PluginRegistry")
    }
}
