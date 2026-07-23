package com.flexfly.ultron.voice.speaker

import android.util.Log

/**
 * Manages owner speaker voice enrollment flow.
 *
 * Collects sample embeddings from multiple utterances of "Hey Ultron",
 * calculates the L2-normalized centroid mean vector, and persists it securely.
 */
class EnrollmentManager(
    private val store: SpeakerStore,
    private val config: SpeakerConfig = SpeakerConfig(),
) {
    companion object {
        private const val TAG = "EnrollmentManager"
    }

    private val samples = mutableListOf<SpeakerEmbedding>()
    private var _isEnrolling = false

    val isEnrolling: Boolean get() = _isEnrolling
    val currentSampleCount: Int get() = samples.size
    val requiredSampleCount: Int get() = config.enrollmentSampleCount

    fun startEnrollment() {
        samples.clear()
        _isEnrolling = true
        Log.d(TAG, "🎙️ Enrollment started (Target: $requiredSampleCount samples)")
    }

    fun addSample(embedding: SpeakerEmbedding): Boolean {
        if (!_isEnrolling) return false

        samples.add(embedding)
        Log.d(TAG, "➕ Enrollment sample added (${samples.size}/$requiredSampleCount)")

        if (samples.size >= requiredSampleCount) {
            finishEnrollment()
            return true
        }
        return false
    }

    fun finishEnrollment(): SpeakerEmbedding {
        val centroid = SpeakerEmbedding.computeCentroid(samples)
        store.saveOwnerProfile(centroid)
        _isEnrolling = false
        samples.clear()
        Log.d(TAG, "✅ Speaker enrollment completed successfully")
        return centroid
    }

    fun cancelEnrollment() {
        samples.clear()
        _isEnrolling = false
        Log.d(TAG, "🚫 Enrollment cancelled")
    }
}
