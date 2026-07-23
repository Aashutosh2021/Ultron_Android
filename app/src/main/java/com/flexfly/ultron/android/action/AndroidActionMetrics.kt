package com.flexfly.ultron.android.action

/**
 * Metric snapshot tracking OS action execution latency, permission requests, and success counts.
 */
data class AndroidActionMetrics(
    val lastActionName: String = "",
    val executionTimeMs: Long = 0L,
    val permissionRequestsCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val timeoutCount: Int = 0,
    val cancellationCount: Int = 0,
)

/**
 * Accumulated statistics for Android Action Layer operations.
 */
data class AndroidActionStatistics(
    val totalExecutions: Int = 0,
    val averageLatencyMs: Long = 0L,
)
