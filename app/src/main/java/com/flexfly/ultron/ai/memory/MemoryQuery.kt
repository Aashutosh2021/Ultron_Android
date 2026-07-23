package com.flexfly.ultron.ai.memory

/**
 * Container query passed to Memory Engine search operations.
 */
data class MemoryQuery(
    val queryText: String,
    val categories: List<MemoryCategory> = emptyList(),
    val importanceThreshold: MemoryImportance = MemoryImportance.LOW,
    val strategy: MemoryRetrievalStrategy = MemoryRetrievalStrategy.HYBRID_RANKING,
    val limit: Int = 10,
)

/**
 * Result context assembled by Memory Engine.
 */
data class MemoryContext(
    val query: MemoryQuery,
    val retrievedRecords: List<MemoryRecord> = emptyList(),
    val totalFound: Int = 0,
    val executionTimeMs: Long = 0L,
)
