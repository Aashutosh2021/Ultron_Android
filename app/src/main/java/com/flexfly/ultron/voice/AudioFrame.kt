package com.flexfly.ultron.voice

/**
 * Encapsulates a chunk of audio data.
 * Preserves raw PCM samples (ShortArray) to avoid precision loss 
 * and provide efficient energy calculations.
 */
data class AudioFrame(
    val pcmData: ShortArray,
    val sampleRate: Int = 16000,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /**
     * Converts raw PCM16 samples to normalized float values [-1.0, 1.0].
     * Required for Silero VAD and OpenWakeWord inference.
     */
    fun toFloatArray(): FloatArray {
        val floats = FloatArray(pcmData.size)
        for (i in pcmData.indices) {
            floats[i] = pcmData[i] / 32768.0f
        }
        return floats
    }

    /**
     * Calculates Root Mean Square (RMS) amplitude for energy detection.
     */
    fun calculateRms(): Float {
        if (pcmData.isEmpty()) return 0.0f
        var sum = 0.0
        for (sample in pcmData) {
            val norm = sample / 32768.0
            sum += norm * norm
        }
        return kotlin.math.sqrt(sum / pcmData.size).toFloat()
    }

    /**
     * Calculates the peak amplitude of the frame.
     */
    fun calculatePeak(): Float {
        if (pcmData.isEmpty()) return 0.0f
        var maxAbs = 0
        for (sample in pcmData) {
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > maxAbs) maxAbs = abs
        }
        return maxAbs / 32768.0f
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioFrame
        if (!pcmData.contentEquals(other.pcmData)) return false
        if (sampleRate != other.sampleRate) return false
        if (timestampMs != other.timestampMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pcmData.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}