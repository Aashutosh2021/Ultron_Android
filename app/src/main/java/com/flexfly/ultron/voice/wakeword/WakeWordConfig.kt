package com.flexfly.ultron.voice.wakeword

/**
 * Immutable configuration for the openWakeWord detection engine.
 *
 * All parameters are configurable to prevent hardcoded magic numbers.
 */
data class WakeWordConfig(
    val modelPath: String = "mark.onnx",
    val wakeThreshold: Float = 0.05f, // Confidence threshold for wake word trigger
    val cooldownMs: Long = 2000L, // Cooldown duration to prevent duplicate triggers
    val inferenceWindowMs: Long = 1280L, // Sliding window duration in ms
    val sampleRate: Int = 16000,
    val hopSizeMs: Long = 80L, // Window slide hop size in ms
) {
    val windowSamples: Int get() = ((sampleRate.toLong() * inferenceWindowMs) / 1000L).toInt()
    val hopSamples: Int get() = ((sampleRate.toLong() * hopSizeMs) / 1000L).toInt()
}
