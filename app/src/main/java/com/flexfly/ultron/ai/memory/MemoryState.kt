package com.flexfly.ultron.ai.memory

/**
 * Deterministic 9-state machine representing memory engine operations.
 */
sealed class MemoryState {
    data object Idle : MemoryState()
    data class Reading(val recordId: String) : MemoryState()
    data class Writing(val recordId: String) : MemoryState()
    data class Updating(val recordId: String) : MemoryState()
    data class Deleting(val recordId: String) : MemoryState()
    data class Searching(val queryText: String) : MemoryState()
    data class Ranking(val count: Int) : MemoryState()
    data class Completed(val message: String) : MemoryState()
    data class Error(val message: String, val cause: Throwable? = null) : MemoryState()
}
