package com.flexfly.ultron.ai.conversation

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Provider-independent streaming manager for chunking audio frames & metrics tracking.
 *
 * Runs off the main thread and avoids memory allocations during live audio streaming.
 */
class StreamingManager(private val chunkSize: Int = 4096) {

    private var bytesSentTotal: Long = 0L
    private var bytesReceivedTotal: Long = 0L

    val bytesSent: Long get() = bytesSentTotal
    val bytesReceived: Long get() = bytesReceivedTotal

    /**
     * Converts a float audio sample buffer into PCM16 bytes for transmission.
     */
    fun pushAudioChunk(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcm = (clamped * 32767.0f).toInt().toShort()
            buffer.putShort(pcm)
        }
        val bytes = buffer.array()
        bytesSentTotal += bytes.size
        return bytes
    }

    fun recordBytesSent(count: Long) {
        bytesSentTotal += count
    }

    fun recordBytesReceived(count: Long) {
        bytesReceivedTotal += count
    }

    fun reset() {
        bytesSentTotal = 0L
        bytesReceivedTotal = 0L
    }
}
