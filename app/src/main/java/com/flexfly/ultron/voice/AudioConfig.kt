package com.flexfly.ultron.voice

import android.media.AudioFormat
import android.media.MediaRecorder

/**
 * Immutable configuration for the Voice Activity Detection (VAD) audio pipeline.
 *
 * All parameters are fully configurable to avoid hardcoded magic numbers.
 * Defaults are optimized for 16kHz PCM16 Mono Silero VAD execution.
 */
data class AudioConfig(
    val sampleRate: Int = 16000,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val frameSizeSamples: Int = 512, // 32 ms frame size at 16kHz
    val speechThreshold: Float = 0.50f, // Probability above which frame is speech
    val silenceThreshold: Float = 0.35f, // Probability below which frame is silence
    val minSpeechDurationMs: Long = 250L, // Onset duration to enter SpeechStarted
    val silenceTimeoutMs: Long = 1200L, // Silence duration to trigger SpeechEnded
    val bufferSizeMultiplier: Int = 4,
    val chunkSizeSamples: Int = 1280, // 80ms chunk for WakeWord/ECAPA buffering
) {
    val bytesPerSample: Int get() = 2 // PCM16 = 2 bytes per sample
    val frameSizeBytes: Int get() = frameSizeSamples * bytesPerSample
    val frameDurationMs: Float get() = (frameSizeSamples.toFloat() / sampleRate.toFloat()) * 1000f

    fun calculateMinBufferSize(): Int {
        val minSize = android.media.AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioFormat,
        )
        return (minSize * bufferSizeMultiplier).coerceAtLeast(frameSizeBytes * 8)
    }
}
