package com.flexfly.ultron.ai.memory

/**
 * Extensible categories for ULTRON Memory Engine.
 */
sealed class MemoryCategory {
    data object Working : MemoryCategory()
    data object Conversation : MemoryCategory()
    data object Personal : MemoryCategory()
    data object Preference : MemoryCategory()
    data object Task : MemoryCategory()
    data object Knowledge : MemoryCategory()
    data object Tool : MemoryCategory()
    data object System : MemoryCategory()
    data object Plugin : MemoryCategory()
    data class Custom(val name: String) : MemoryCategory()
}

/**
 * Importance levels for ranking memory relevance.
 */
enum class MemoryImportance {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
    SYSTEM,
}

/**
 * Scope defining memory persistence domain.
 */
enum class MemoryScope {
    SHORT_TERM,
    SESSION,
    LONG_TERM,
    PERMANENT,
}

/**
 * Lifecycle states for memory records.
 */
enum class MemoryLifecycle {
    DRAFT,
    ACTIVE,
    ARCHIVED,
    EXPIRED,
    DELETED,
}
