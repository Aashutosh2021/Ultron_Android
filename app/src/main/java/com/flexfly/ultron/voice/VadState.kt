package com.flexfly.ultron.voice

/**
 * State machine states for Voice Activity Detection.
 *
 * Designed for non-flickering, robust speech boundary detection.
 */
sealed class VadState {
    /** Baseline silence / background noise. */
    data object Idle : VadState()

    /** Speech onset detected exceeding confidence and minimum duration thresholds. */
    data object SpeechStarted : VadState()

    /** Sustained human voice active. */
    data object Speaking : VadState()

    /** Speech offset / pause timeout completed. Transitioning back to [Idle]. */
    data object SpeechEnded : VadState()

    /** Error state for permissions, mic availability, or model errors. */
    data class Error(val message: String, val cause: Throwable? = null) : VadState()
}

/**
 * Snapshot metrics exposed to debug screen and monitoring tools.
 */
data class VadDebugInfo(
    val state: VadState = VadState.Idle,
    val confidence: Float = 0.0f,
    val micStatus: String = "IDLE",
    val sampleRate: Int = 16000,
    val detectionCount: Int = 0,
    val speechFramesCount: Int = 0,
    val silenceFramesCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)
