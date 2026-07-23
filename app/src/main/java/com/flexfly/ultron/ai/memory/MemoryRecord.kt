package com.flexfly.ultron.ai.memory

import java.util.UUID

/**
 * Immutable Memory Record entity in ULTRON Memory Engine.
 *
 * Designed for full compatibility with future Room/SQLite databases and Vector Embeddings.
 */
data class MemoryRecord(
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val category: MemoryCategory = MemoryCategory.Conversation,
    val importance: MemoryImportance = MemoryImportance.NORMAL,
    val scope: MemoryScope = MemoryScope.LONG_TERM,
    val lifecycle: MemoryLifecycle = MemoryLifecycle.ACTIVE,
    val tags: List<String> = emptyList(),
    val source: String = "System",
    val summary: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val embeddingPlaceholder: FloatArray? = null,
    val vectorId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
