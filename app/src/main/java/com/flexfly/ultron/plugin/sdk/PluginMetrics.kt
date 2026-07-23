package com.flexfly.ultron.plugin.sdk

/**
 * Metric snapshot tracking active plugins, exposed tool counts, load times, and health scores.
 */
data class PluginMetrics(
    val totalPluginsCount: Int = 0,
    val activePluginsCount: Int = 0,
    val totalExposedToolsCount: Int = 0,
    val loadTimeMs: Long = 0L,
    val crashCount: Int = 0,
    val averageHealthScore: Float = 1.0f,
)

/**
 * Accumulated statistics for Plugin Platform.
 */
data class PluginStatistics(
    val uptimeMs: Long = 0L,
    val averageResponseTimeMs: Long = 0L,
)
