package com.flexfly.ultron.voice

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe ring audio buffer for 16-bit PCM samples.
 *
 * Converts raw PCM16 short values to normalized float values `[-1.0f, +1.0f]`
 * required by neural network VAD models like Silero.
 */
class AudioBuffer(val capacity: Int = 16384) {

    private val buffer = ShortArray(capacity)
    private var head = 0
    private var tail = 0
    private var count = 0
    private val lock = ReentrantLock()

    fun push(samples: ShortArray, readSize: Int) {
        lock.withLock {
            for (i in 0 until readSize) {
                buffer[tail] = samples[i]
                tail = (tail + 1) % capacity
                if (count < capacity) {
                    count++
                } else {
                    // Buffer overflow: drop oldest sample to avoid block
                    head = (head + 1) % capacity
                }
            }
        }
    }

    fun popFrame(frameSize: Int): FloatArray? {
        lock.withLock {
            if (count < frameSize) return null
            val frame = FloatArray(frameSize)
            for (i in 0 until frameSize) {
                val shortVal = buffer[head]
                // Convert Short [-32768, 32767] -> Float [-1.0f, 1.0f]
                frame[i] = shortVal / 32768.0f
                head = (head + 1) % capacity
                count--
            }
            return frame
        }
    }

    fun availableSamples(): Int {
        lock.withLock {
            return count
        }
    }

    fun clear() {
        lock.withLock {
            head = 0
            tail = 0
            count = 0
        }
    }
}
