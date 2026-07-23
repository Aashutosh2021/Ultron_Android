package com.flexfly.ultron.ai.memory

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface contract for database-agnostic Memory Persistence Providers.
 *
 * Future SQLite, Room, Realm, and Vector Database plugins implement this contract.
 */
interface MemoryProvider {
    suspend fun store(record: MemoryRecord): Boolean
    suspend fun getById(id: String): MemoryRecord?
    suspend fun search(query: MemoryQuery): List<MemoryRecord>
    suspend fun update(record: MemoryRecord): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun getAll(): List<MemoryRecord>
    suspend fun clear()
}

/**
 * Interface contract for top-level Memory Store storage operations.
 */
interface MemoryStore : MemoryProvider

/**
 * Thread-Safe In-Memory Storage Reference Implementation.
 */
class InMemoryStoreProvider : MemoryStore {

    companion object {
        private const val TAG = "InMemoryStoreProvider"
    }

    private val recordsMap = ConcurrentHashMap<String, MemoryRecord>()

    override suspend fun store(record: MemoryRecord): Boolean {
        recordsMap[record.id] = record
        Log.d(TAG, "💾 Stored MemoryRecord: ${record.id} (${record.summary})")
        return true
    }

    override suspend fun getById(id: String): MemoryRecord? {
        return recordsMap[id]
    }

    override suspend fun search(query: MemoryQuery): List<MemoryRecord> {
        return recordsMap.values.toList()
    }

    override suspend fun update(record: MemoryRecord): Boolean {
        if (!recordsMap.containsKey(record.id)) return false
        recordsMap[record.id] = record
        Log.d(TAG, "✏️ Updated MemoryRecord: ${record.id}")
        return true
    }

    override suspend fun delete(id: String): Boolean {
        val removed = recordsMap.remove(id)
        if (removed != null) {
            Log.d(TAG, "🗑️ Deleted MemoryRecord: $id")
            return true
        }
        return false
    }

    override suspend fun getAll(): List<MemoryRecord> {
        return recordsMap.values.toList()
    }

    override suspend fun clear() {
        recordsMap.clear()
        Log.d(TAG, "🧹 Cleared InMemoryStoreProvider")
    }
}
