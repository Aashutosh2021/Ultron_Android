package com.flexfly.ultron.voice.speaker

/**
 * State machine representing Speaker Verification status.
 */
sealed class VerificationResult {
    /** No owner profile enrolled. Verification cannot take place. */
    data object NotEnrolled : VerificationResult()

    /** Owner profile present, waiting for wake word events. */
    data object Ready : VerificationResult()

    /** Currently computing speaker embedding and comparing cosine similarity. */
    data object Verifying : VerificationResult()

    /** Speaker authenticated as the enrolled owner (Similarity >= threshold). */
    data class Verified(
        val similarity: Float,
        val timestamp: Long = System.currentTimeMillis(),
    ) : VerificationResult()

    /** Speaker rejected as unauthorized / different speaker (Similarity < threshold). */
    data class Rejected(
        val similarity: Float,
        val timestamp: Long = System.currentTimeMillis(),
    ) : VerificationResult()

    /** Error during embedding extraction or storage lookup. */
    data class Error(val message: String, val cause: Throwable? = null) : VerificationResult()
}

/**
 * Snapshot metrics exposed to UI and monitoring tools.
 */
data class SpeakerDebugInfo(
    val isEnrolled: Boolean = false,
    val status: VerificationResult = VerificationResult.NotEnrolled,
    val similarityScore: Float = 0.0f,
    val threshold: Float = 0.70f,
    val inferenceTimeMs: Long = 0L,
    val verificationCount: Int = 0,
    val enrollmentProgress: Int = 0, // Current enrollment samples collected out of total required
    val timestamp: Long = System.currentTimeMillis(),
)
