package com.flexfly.ultron.ai.memory

/**
 * Metric snapshot tracking memory retrieval times, read/write counts, and storage volume.
 */
data class MemoryMetrics(
    val totalMemories: Int = 0,
    val readsCount: Int = 0,
    val writesCount: Int = 0,
    val deletesCount: Int = 0,
    val retrievalTimeMs: Long = 0L,
    val rankingTimeMs: Long = 0L,
    val searchCount: Int = 0,
    val archiveCount: Int = 0,
    val expirationCount: Int = 0,
)

/**
 * Accumulated statistics for Memory Engine.
 */
data class MemoryStatistics(
    val averageRetrievalTimeMs: Long = 0L,
    val retainedRatio: Float = 1.0f,
)
