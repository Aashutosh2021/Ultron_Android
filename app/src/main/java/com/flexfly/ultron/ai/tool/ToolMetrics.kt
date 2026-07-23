package com.flexfly.ultron.ai.tool

/**
 * Metric snapshot tracking tool execution duration, success counts, and permission requests.
 */
data class ToolMetrics(
    val lastToolName: String = "",
    val executionTimeMs: Long = 0L,
    val queueTimeMs: Long = 0L,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val cancellationCount: Int = 0,
    val permissionRequestsCount: Int = 0,
    val timeoutCount: Int = 0,
)

/**
 * Accumulated statistics for Tool Framework executions.
 */
data class ToolStatistics(
    val totalExecutions: Int = 0,
    val averageDurationMs: Long = 0L,
)
