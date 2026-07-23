package com.flexfly.ultron.plugin.sdk

/**
 * Lifecycle events broadcast via SharedFlow by Plugin Manager.
 */
sealed class PluginEvent {
    data class PluginDiscovered(val pluginId: String) : PluginEvent()
    data class PluginLoaded(val pluginId: String) : PluginEvent()
    data class PluginInitialized(val pluginId: String) : PluginEvent()
    data class PluginStarted(val pluginId: String) : PluginEvent()
    data class PluginStopped(val pluginId: String) : PluginEvent()
    data class PluginPaused(val pluginId: String) : PluginEvent()
    data class PluginResumed(val pluginId: String) : PluginEvent()
    data class PluginCrashed(val pluginId: String, val reason: String) : PluginEvent()
    data class PluginUpdated(val pluginId: String) : PluginEvent()
    data class PluginRemoved(val pluginId: String) : PluginEvent()
}
