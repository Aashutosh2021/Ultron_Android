package com.flexfly.ultron.plugin.sdk

import android.util.Log

/**
 * Isolated Context container provided to plugins during initialization.
 *
 * Prevents direct access to Core internal classes.
 */
class PluginContext(
    val pluginId: String,
) {
    fun logInfo(tag: String, message: String) {
        Log.i("Plugin:$pluginId:$tag", message)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("Plugin:$pluginId:$tag", message, throwable)
    }
}
