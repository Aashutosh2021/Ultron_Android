package com.flexfly.ultron.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.flexfly.ultron.model.Message
import org.json.JSONArray
import org.json.JSONObject

/**
 * SQLite persistence for the assistant conversation history.
 *
 * Recovered from `DatabaseHelper.kt`. A single `messages` table stores the rolling
 * transcript; [getHistoryAsJson] serialises it into the Gemini `contents` format.
 *
 * RECOVERY NOTE: the decompiler substituted constants whose values equal the real
 * column/key strings — `TranslateLanguage.INDONESIAN` == "id" and
 * `TypingAccessibilityService.EXTRA_TEXT` == "text". The original literals are restored here.
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
    }

    fun saveMessage(role: String, content: String): Long {
        val timestamp = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("role", role)
            put("content", content)
            put("timestamp", timestamp)
        }
        val id = writableDatabase.insert("messages", null, values)
        if (id != -1L) {
            notifyMessageAdded(Message(id, role, content, timestamp))
        }
        return id
    }

    fun getRecentMessages(limit: Int = 20): List<Message> {
        val result = ArrayList<Message>()
        readableDatabase.rawQuery(
            "SELECT * FROM messages ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Message(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    role = cursor.getString(cursor.getColumnIndexOrThrow("role")),
                    content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                )
            }
        }
        // Stored DESC for the LIMIT, returned chronologically.
        return result.reversed()
    }

    /** Serialises the recent transcript into Gemini `contents` JSON (role + parts[].text). */
    fun getHistoryAsJson(): JSONArray {
        val messages = getRecentMessages(20)
        val array = JSONArray()
        for (message in messages) {
            val obj = JSONObject()
            obj.put("role", if (message.role == "assistant") "model" else "user")
            val parts = JSONArray()
            parts.put(JSONObject().put("text", message.content))
            obj.put("parts", parts)
            array.put(obj)
        }
        return array
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM messages")
    }

    /** Human-readable dump of the last 10 rows, for debugging. */
    fun debugDump(): String = try {
        val db = readableDatabase
        val sb = StringBuilder("DB total: ")
        db.rawQuery("SELECT COUNT(*) FROM messages", null).use {
            it.moveToFirst()
            sb.append(it.getInt(0)).append("\n")
        }
        db.rawQuery(
            "SELECT role, content, timestamp FROM messages ORDER BY timestamp DESC LIMIT 10",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val role = cursor.getString(0)
                val content = cursor.getString(1)
                sb.append("$role: ${content.take(30)}...\n")
            }
        }
        sb.toString()
    } catch (e: Exception) {
        "DB Error: ${e.message}"
    }

    interface MessageListener {
        fun onMessageAdded(message: Message)
    }

    companion object {
        private const val DB_NAME = "mj_conversations.db"
        private const val DB_VERSION = 1

        private val listeners = mutableListOf<MessageListener>()

        fun registerListener(listener: MessageListener) {
            synchronized(listeners) {
                listeners.add(listener)
            }
        }

        fun unregisterListener(listener: MessageListener) {
            synchronized(listeners) {
                listeners.remove(listener)
            }
        }

        private fun notifyMessageAdded(message: Message) {
            synchronized(listeners) {
                for (listener in listeners) {
                    try {
                        listener.onMessageAdded(message)
                    } catch (unused: Exception) {
                    }
                }
            }
        }
    }
}
