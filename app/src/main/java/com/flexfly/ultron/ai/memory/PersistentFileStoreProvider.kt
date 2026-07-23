package com.flexfly.ultron.ai.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Production persistent [MemoryStore] backed by an on-device JSON file.
 *
 * Preserves the [MemoryProvider]/[MemoryStore] abstraction EXACTLY — it is a drop-in
 * replacement for [InMemoryStoreProvider] that additionally survives process death.
 * A [ConcurrentHashMap] serves reads (thread-safe, fast); every mutation is flushed to
 * `<filesDir>/ultron_memory/memory_store.json`.
 *
 * Vector compatibility: [MemoryRecord.embeddingPlaceholder] is persisted, so a future
 * vector-DB provider can migrate existing embeddings without data loss.
 *
 * This does NOT change the Memory Engine architecture — it only makes the storage layer
 * durable behind the existing interface.
 */
class PersistentFileStoreProvider(
    context: Context,
) : MemoryStore {

    companion object {
        private const val TAG = "PersistentFileStore"
        private const val DIR_NAME = "ultron_memory"
        private const val FILE_NAME = "memory_store.json"
    }

    private val file: File = File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)
    private val recordsMap = ConcurrentHashMap<String, MemoryRecord>()
    private val writeLock = Any()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            if (!file.exists()) return
            val text = file.readText()
            if (text.isBlank()) return
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val record = fromJson(arr.getJSONObject(i))
                recordsMap[record.id] = record
            }
            Log.d(TAG, "💾 Loaded ${recordsMap.size} memory records from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memory store (starting empty): ${e.message}", e)
        }
    }

    private fun flushToDisk() {
        synchronized(writeLock) {
            try {
                val arr = JSONArray()
                for (record in recordsMap.values) arr.put(toJson(record))
                file.writeText(arr.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush memory store: ${e.message}", e)
            }
        }
    }

    override suspend fun store(record: MemoryRecord): Boolean {
        recordsMap[record.id] = record
        flushToDisk()
        Log.d(TAG, "💾 Stored MemoryRecord: ${record.id} (${record.summary})")
        return true
    }

    override suspend fun getById(id: String): MemoryRecord? = recordsMap[id]

    override suspend fun search(query: MemoryQuery): List<MemoryRecord> = recordsMap.values.toList()

    override suspend fun update(record: MemoryRecord): Boolean {
        if (!recordsMap.containsKey(record.id)) return false
        recordsMap[record.id] = record
        flushToDisk()
        Log.d(TAG, "✏️ Updated MemoryRecord: ${record.id}")
        return true
    }

    override suspend fun delete(id: String): Boolean {
        val removed = recordsMap.remove(id) ?: return false
        flushToDisk()
        Log.d(TAG, "🗑️ Deleted MemoryRecord: $id")
        return true
    }

    override suspend fun getAll(): List<MemoryRecord> = recordsMap.values.toList()

    override suspend fun clear() {
        recordsMap.clear()
        flushToDisk()
        Log.d(TAG, "🧹 Cleared PersistentFileStoreProvider")
    }

    // --- JSON (de)serialization; keeps the durable format independent of any DB lib ---

    private fun toJson(r: MemoryRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("timestamp", r.timestamp)
        put("category", categoryToString(r.category))
        put("importance", r.importance.name)
        put("scope", r.scope.name)
        put("lifecycle", r.lifecycle.name)
        put("tags", JSONArray(r.tags))
        put("source", r.source)
        put("summary", r.summary)
        put("content", r.content)
        put("metadata", JSONObject(r.metadata as Map<*, *>))
        r.embeddingPlaceholder?.let { emb ->
            put("embedding", JSONArray().apply { emb.forEach { put(it.toDouble()) } })
        }
        r.vectorId?.let { put("vectorId", it) }
    }

    private fun fromJson(o: JSONObject): MemoryRecord {
        val tags = o.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val metaObj = o.optJSONObject("metadata") ?: JSONObject()
        val metadata = metaObj.keys().asSequence().associateWith { metaObj.getString(it) }
        val embedding = o.optJSONArray("embedding")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        return MemoryRecord(
            id = o.getString("id"),
            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
            category = categoryFromString(o.optString("category")),
            importance = enumOrDefault(o.optString("importance"), MemoryImportance.NORMAL),
            scope = enumOrDefault(o.optString("scope"), MemoryScope.LONG_TERM),
            lifecycle = enumOrDefault(o.optString("lifecycle"), MemoryLifecycle.ACTIVE),
            tags = tags,
            source = o.optString("source", "System"),
            summary = o.optString("summary", ""),
            content = o.optString("content", ""),
            metadata = metadata,
            embeddingPlaceholder = embedding,
            vectorId = if (o.has("vectorId")) o.getString("vectorId") else null,
        )
    }

    // MemoryCategory is a sealed class (not an enum); map by simple name, with Custom(name)
    // encoded as "Custom:<name>".
    private fun categoryToString(c: MemoryCategory): String = when (c) {
        is MemoryCategory.Custom -> "Custom:${c.name}"
        else -> c.javaClass.simpleName
    }

    private fun categoryFromString(s: String): MemoryCategory = when {
        s.startsWith("Custom:") -> MemoryCategory.Custom(s.removePrefix("Custom:"))
        s == "Working" -> MemoryCategory.Working
        s == "Conversation" -> MemoryCategory.Conversation
        s == "Personal" -> MemoryCategory.Personal
        s == "Preference" -> MemoryCategory.Preference
        s == "Task" -> MemoryCategory.Task
        s == "Knowledge" -> MemoryCategory.Knowledge
        s == "Tool" -> MemoryCategory.Tool
        s == "System" -> MemoryCategory.System
        s == "Plugin" -> MemoryCategory.Plugin
        else -> MemoryCategory.Conversation
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}
