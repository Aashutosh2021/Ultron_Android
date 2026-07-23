package com.flexfly.ultron.voice.wakeword

/**
 * Sealed class representing the Wake Word state machine.
 */
sealed class WakeWordState {
    /** Paused because VAD reports silence / background noise. */
    data object Idle : WakeWordState()

    /** Active speech detected by VAD; scanning audio window for wake word. */
    data object Listening : WakeWordState()

    /** Wake phrase recognized above confidence threshold. */
    data class WakeDetected(
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis(),
    ) : WakeWordState()

    /** Cooldown period following a trigger to prevent duplicate events in a sentence. */
    data object Cooldown : WakeWordState()

    /** Error state for missing models or ONNX inference failures. */
    data class Error(val message: String, val cause: Throwable? = null) : WakeWordState()
}

/**
 * Snapshot metrics exposed to UI and monitoring tools.
 */
data class WakeWordDebugInfo(
    val state: WakeWordState = WakeWordState.Idle,
    val confidence: Float = 0.0f,
    val inferenceTimeMs: Long = 0L,
    val detectionCount: Int = 0,
    val lastTriggerTimestamp: Long = 0L,
    val modelName: String = "hey_ultron.onnx",
    val timestamp: Long = System.currentTimeMillis(),
)
