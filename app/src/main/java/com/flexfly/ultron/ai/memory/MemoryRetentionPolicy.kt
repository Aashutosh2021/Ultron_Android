package com.flexfly.ultron.ai.memory

import android.util.Log

/**
 * Configurable Retention Policy Manager.
 */
class MemoryRetentionPolicy {

    companion object {
        private const val TAG = "MemoryRetentionPolicy"

        // Default TTL durations in milliseconds
        private const val SHORT_TERM_TTL_MS = 2 * 3600 * 1000L      // 2 hours
        private const val SESSION_TTL_MS = 24 * 3600 * 1000L        // 24 hours
        private const val LONG_TERM_TTL_MS = 30 * 24 * 3600 * 1000L // 30 days
    }

    fun isExpired(record: MemoryRecord, now: Long = System.currentTimeMillis()): Boolean {
        if (record.scope == MemoryScope.PERMANENT || record.importance == MemoryImportance.CRITICAL || record.importance == MemoryImportance.SYSTEM) {
            return false
        }

        val age = now - record.timestamp
        return when (record.scope) {
            MemoryScope.SHORT_TERM -> age > SHORT_TERM_TTL_MS
            MemoryScope.SESSION -> age > SESSION_TTL_MS
            MemoryScope.LONG_TERM -> age > LONG_TERM_TTL_MS
            MemoryScope.PERMANENT -> false
        }
    }

    fun purgeExpired(records: List<MemoryRecord>): List<MemoryRecord> {
        val now = System.currentTimeMillis()
        val (valid, expired) = records.partition { !isExpired(it, now) }
        if (expired.isNotEmpty()) {
            Log.d(TAG, "🧹 Purged ${expired.size} expired memory records")
        }
        return valid
    }
}
