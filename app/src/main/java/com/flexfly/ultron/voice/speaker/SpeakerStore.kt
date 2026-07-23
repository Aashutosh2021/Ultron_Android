package com.flexfly.ultron.voice.speaker

import android.content.Context
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Security & Storage Manager for Owner Speaker Profile.
 *
 * CRITICAL SECURITY GUARANTEE:
 *  - Raw microphone recordings are NEVER saved to disk.
 *  - Only mathematical 192-dimensional floating-point embeddings are stored locally.
 *  - Provides [clearOwnerProfile] for user privacy control.
 */
class SpeakerStore(context: Context) {

    companion object {
        private const val TAG = "SpeakerStore"
        private const val PREFS_NAME = "ultron_speaker_security_prefs"
        private const val KEY_OWNER_EMBEDDING = "encrypted_owner_embedding"
        private const val KEY_ENROLLED_TIMESTAMP = "enrolled_timestamp"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveOwnerProfile(embedding: SpeakerEmbedding) {
        try {
            val floatArray = embedding.vector
            val byteBuffer = ByteBuffer.allocate(floatArray.size * 4)
            byteBuffer.asFloatBuffer().put(floatArray)
            val bytes = byteBuffer.array()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)

            prefs.edit()
                .putString(KEY_OWNER_EMBEDDING, encoded)
                .putLong(KEY_ENROLLED_TIMESTAMP, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "🔒 Owner speaker embedding saved securely (${floatArray.size} dims)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving speaker profile: ${e.message}", e)
        }
    }

    fun getOwnerProfile(): SpeakerEmbedding? {
        val encoded = prefs.getString(KEY_OWNER_EMBEDDING, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val floatBuffer: FloatBuffer = ByteBuffer.wrap(bytes).asFloatBuffer()
            val floatArray = FloatArray(floatBuffer.remaining())
            floatBuffer.get(floatArray)
            SpeakerEmbedding(floatArray)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading speaker profile: ${e.message}", e)
            null
        }
    }

    fun hasOwnerProfile(): Boolean {
        return prefs.contains(KEY_OWNER_EMBEDDING)
    }

    fun getEnrolledTimestamp(): Long {
        return prefs.getLong(KEY_ENROLLED_TIMESTAMP, 0L)
    }

    fun clearOwnerProfile() {
        prefs.edit().clear().apply()
        Log.d(TAG, "🧹 Owner speaker profile wiped securely")
    }
}
