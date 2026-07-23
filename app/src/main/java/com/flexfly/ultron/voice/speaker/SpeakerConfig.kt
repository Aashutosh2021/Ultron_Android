package com.flexfly.ultron.voice.speaker

/**
 * Immutable configuration for ECAPA-TDNN Speaker Verification.
 *
 * All parameters are configurable to prevent hardcoded magic numbers.
 */
data class SpeakerConfig(
    val modelPath: String = "ecapa_tdnn.onnx",
    val embeddingSize: Int = 192, // ECAPA-TDNN output vector dimensionality
    val similarityThreshold: Float = 0.65f, // Cosine similarity threshold for owner authentication
    val enrollmentSampleCount: Int = 10, // Number of utterances required for enrollment
    val sampleRate: Int = 16000,
)
