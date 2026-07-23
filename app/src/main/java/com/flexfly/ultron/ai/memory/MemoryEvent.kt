package com.flexfly.ultron.ai.memory

/**
 * High-level memory lifecycle events broadcast via SharedFlow.
 */
sealed class MemoryEvent {
    data class MemoryStored(val recordId: String) : MemoryEvent()
    data class MemoryUpdated(val recordId: String) : MemoryEvent()
    data class MemoryDeleted(val recordId: String) : MemoryEvent()
    data class MemoryRetrieved(val recordId: String) : MemoryEvent()
    data class MemorySearchCompleted(val queryText: String, val resultCount: Int) : MemoryEvent()
    data class MemoryExpired(val recordId: String) : MemoryEvent()
    data class MemoryArchived(val recordId: String) : MemoryEvent()
    data class MemoryError(val message: String, val cause: Throwable? = null) : MemoryEvent()
}
