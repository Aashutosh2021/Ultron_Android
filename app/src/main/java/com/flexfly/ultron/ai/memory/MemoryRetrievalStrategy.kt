package com.flexfly.ultron.ai.memory

/**
 * Retrieval Strategy algorithms supported by Memory Engine.
 */
enum class MemoryRetrievalStrategy {
    RECENT_FIRST,
    IMPORTANCE_FIRST,
    SEMANTIC_SIMILARITY,
    CATEGORY_MATCH,
    HYBRID_RANKING,
    CONTEXT_AWARE,
}
