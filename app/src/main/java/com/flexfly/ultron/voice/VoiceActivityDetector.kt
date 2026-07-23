package com.flexfly.ultron.voice

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Abstraction wrapping the underlying Voice Activity Detection engine.
 *
 * Exposes a clean contract so Silero VAD or any future model can be swapped
 * without modifying state machines, services, or downstream components.
 */
interface VoiceActivityDetector : AutoCloseable {
    /** Processes an audio frame and returns speech confidence `[0.0f, 1.0f]`. */
    fun processFrame(frame: AudioFrame): Float

    /** Resets internal model states (e.g. RNN hidden vectors). */
    fun reset()

    /** Releases native resources (ONNX sessions/environments). */
    override fun close()
}

/**
 * Production-ready Silero VAD detector running via ONNX Runtime Android SDK.
 *
 * This implementation adopts the proven state-management logic from the 
 * standalone test project to ensure consistent speech detection.
 */
class SileroVadDetector(
    private val context: Context,
    private val modelFileName: String = "silero_vad.onnx",
) : VoiceActivityDetector {

    companion object {
        private const val TAG = "SileroVadDetector"
    }

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var fallbackDetector: EnergyVadDetector? = null

    // Proven Silero VAD state tensor: [2, 1, 128] floats
    private var state = FloatArray(2 * 1 * 128)
    private var isInitialized = false

    init {
        try {
            environment = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(modelFileName).use { it.readBytes() }
            session = environment?.createSession(modelBytes, OrtSession.SessionOptions())
            isInitialized = true
            reset()
            Log.d(TAG, "✅ Silero VAD model loaded successfully (${modelBytes.size} bytes)")
        } catch (e: Exception) {
            if (com.flexfly.ultron.BuildConfig.DEBUG) {
                Log.w(TAG, "⚠️ [DEBUG] Silero VAD asset '$modelFileName' missing/failed (${e.message}). Using Energy DSP fallback.")
                fallbackDetector = EnergyVadDetector()
            } else {
                Log.e(TAG, "❌ Silero VAD model '$modelFileName' missing or failed to load in RELEASE build.", e)
                throw IllegalStateException("Required VAD model '$modelFileName' is missing or failed to initialize.", e)
            }
        }
    }

    override fun processFrame(frame: AudioFrame): Float {
        val fallback = fallbackDetector
        if (fallback != null) {
            return fallback.processFrame(frame)
        }

        if (!isInitialized || session == null || environment == null) {
            return 0f
        }

        return try {
            val env = environment!!
            val floatPcm = frame.toFloatArray()
            
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatPcm), longArrayOf(1, floatPcm.size.toLong()))
            val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000L)), longArrayOf(1))
            val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))

            val inputs = mapOf(
                "input" to inputTensor,
                "sr" to srTensor,
                "state" to stateTensor
            )

            val outputs = session?.run(inputs)
            
            var prob = 0.0f
            val outputVal = outputs?.get(0)?.value
            if (outputVal is Array<*>) {
                val first = outputVal.firstOrNull()
                if (first is FloatArray) {
                    prob = first[0]
                }
            } else if (outputVal is FloatArray) {
                prob = outputVal[0]
            }

            // Proven state update logic: recurrent state buffer update
            val nextStateVal = if ((outputs?.count() ?: 0) > 1) outputs?.get(1)?.value else null
            if (nextStateVal is Array<*>) {
                var idx = 0
                for (i in nextStateVal.indices) {
                    val sub1 = nextStateVal[i]
                    if (sub1 is Array<*>) {
                        for (j in sub1.indices) {
                            val sub2 = sub1[j]
                            if (sub2 is FloatArray) {
                                System.arraycopy(sub2, 0, state, idx, minOf(sub2.size, state.size - idx))
                                idx += sub2.size
                            }
                        }
                    }
                }
            }

            inputTensor.close()
            srTensor.close()
            stateTensor.close()
            outputs?.close()

            prob.coerceIn(0.0f, 1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame in Silero VAD: ${e.message}", e)
            0.0f
        }
    }

    override fun reset() {
        state = FloatArray(2 * 1 * 128)
        fallbackDetector?.reset()
    }

    override fun close() {
        try {
            session?.close()
            session = null
            environment = null
            fallbackDetector?.close()
            Log.d(TAG, "🧹 Silero VAD session closed cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Silero VAD session: ${e.message}", e)
        }
    }
}

/**
 * Fallback DSP Root-Mean-Square (RMS) Energy VAD.
 *
 * Computes signal amplitude and decibel energy to estimate speech probability
 * when neural VAD assets are not available.
 */
class EnergyVadDetector(
    private val minDb: Float = -50.0f,
    private val maxDb: Float = -10.0f,
) : VoiceActivityDetector {

    override fun processFrame(frame: AudioFrame): Float {
        val pcm = frame.toFloatArray()
        if (pcm.isEmpty()) return 0.0f
        var sumSquare = 0.0
        for (sample in pcm) {
            sumSquare += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquare / pcm.size).toFloat()
        if (rms <= 0.00001f) return 0.0f

        val db = 20.0f * log10(rms.toDouble()).toFloat()
        val normalized = (db - minDb) / (maxDb - minDb)
        return normalized.coerceIn(0.0f, 1.0f)
    }

    override fun reset() {}
    override fun close() {}
}
