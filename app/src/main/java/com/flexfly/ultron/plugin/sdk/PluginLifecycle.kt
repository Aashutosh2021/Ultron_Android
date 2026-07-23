package com.flexfly.ultron.plugin.sdk

/**
 * Plugin lifecycle phases.
 */
enum class PluginLifecycle {
    DISCOVERED,
    LOADED,
    INITIALIZED,
    RUNNING,
    PAUSED,
    STOPPED,
    UNLOADED,
    FAILED,
}

/**
 * Deterministic state machine representing Plugin Platform operation.
 */
sealed class PluginState {
    data object Idle : PluginState()
    data object Discovering : PluginState()
    data class Loading(val pluginId: String) : PluginState()
    data class Initializing(val pluginId: String) : PluginState()
    data class Running(val activePluginsCount: Int) : PluginState()
    data class Paused(val pluginId: String) : PluginState()
    data class Stopped(val pluginId: String) : PluginState()
    data class Unloaded(val pluginId: String) : PluginState()
    data class Failed(val pluginId: String, val reason: String) : PluginState()
    data class Error(val message: String, val cause: Throwable? = null) : PluginState()
}
