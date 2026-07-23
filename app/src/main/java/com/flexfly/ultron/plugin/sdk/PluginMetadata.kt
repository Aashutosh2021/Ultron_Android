package com.flexfly.ultron.plugin.sdk

/**
 * Metadata descriptor for registered plugins.
 */
data class PluginMetadata(
    val pluginId: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "First Party",
    val description: String,
    val minCoreVersion: String = "1.0.0",
    val requiredPermissions: List<PluginPermission> = emptyList(),
)

/**
 * Configuration container for plugin settings.
 */
data class PluginConfiguration(
    val settings: Map<String, String> = emptyMap(),
    val isEnabled: Boolean = true,
)
