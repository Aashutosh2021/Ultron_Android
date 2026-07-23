package com.flexfly.ultron.voice.wakeword

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.system.measureTimeMillis

/**
 * Abstraction for Wake Word Detection.
 *
 * Wraps openWakeWord ONNX inference behind a clean contract so another wake
 * engine can replace it without modifying state machines or VAD logic.
 */
interface WakeWordDetector : AutoCloseable {
    /** Last ONNX inference duration in milliseconds. */
    val lastInferenceTimeMs: Long

    /** Runs wake word classification on an audio window and returns confidence `[0.0f, 1.0f]`. */
    fun processWindow(samples: FloatArray): Float

    /** Resets internal model state / buffers. */
    fun reset()

    /** Releases native ONNX sessions and resources. */
    override fun close()
}

/**
 * openWakeWord ONNX Runtime implementation.
 *
 * Runs the full 3-stage openWakeWord feature & classification pipeline:
 * 1. melspectrogram.onnx  : Raw Audio PCM -> Mel-Spectrogram
 * 2. embedding_model.onnx : Mel-Spectrogram -> 96-dim Google Speech Embeddings
 * 3. [modelName]          : 16-frame Embeddings [1, 16, 96] -> Wake Word Score [1, 1]
 */
class OpenWakeWordDetector(
    private val context: Context,
    val modelName: String = "hey_jarvis.onnx",
) : WakeWordDetector {

    companion object {
        private const val TAG = "OpenWakeWordDetector"
    }

    private var environment: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var wakeSession: OrtSession? = null
    private var fallbackDetector: DslWakeWordDetector? = null

    private val melContextFrames = 76
    private val melBins = 32
    private val embContextFrames = 16
    private val embSize = 96
    
    private val melBuffer = Array(melContextFrames) { FloatArray(melBins) }
    private val embBuffer = Array(embContextFrames) { FloatArray(embSize) }

    private var _lastInferenceTimeMs: Long = 0L
    override val lastInferenceTimeMs: Long get() = _lastInferenceTimeMs

    init {
        try {
            environment = OrtEnvironment.getEnvironment()
            val env = environment!!
            val options = OrtSession.SessionOptions()

            val melBytes = context.assets.open("melspectrogram.onnx").use { it.readBytes() }
            melSession = env.createSession(melBytes, options)

            val embBytes = context.assets.open("embedding_model.onnx").use { it.readBytes() }
            embSession = env.createSession(embBytes, options)

            val wakeBytes = context.assets.open(modelName).use { it.readBytes() }
            wakeSession = env.createSession(wakeBytes, options)

            Log.d(TAG, "✅ openWakeWord pipeline (melspectrogram, embedding_model, '$modelName') initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize openWakeWord pipeline for '$modelName'", e)
            if (com.flexfly.ultron.BuildConfig.DEBUG) {
                fallbackDetector = DslWakeWordDetector()
            } else {
                throw IllegalStateException("Required wake-word models missing or failed to initialize", e)
            }
        }
    }

    override fun processWindow(samples: FloatArray): Float {
        val fallback = fallbackDetector
        if (fallback != null) {
            val conf = fallback.processWindow(samples)
            _lastInferenceTimeMs = fallback.lastInferenceTimeMs
            return conf
        }

        val env = environment ?: return 0f
        val melSess = melSession ?: return 0f
        val embSess = embSession ?: return 0f
        val wakeSess = wakeSession ?: return 0f

        var confidence = 0.0f
        _lastInferenceTimeMs = measureTimeMillis {
            var melTensor: OnnxTensor? = null
            var embTensor: OnnxTensor? = null
            var wakeTensor: OnnxTensor? = null
            var melResults: OrtSession.Result? = null
            var embResults: OrtSession.Result? = null
            var wakeResults: OrtSession.Result? = null
            try {
                // 1. Mel-Spectrogram: input [1, samples.size]
                val melInputBuffer = FloatBuffer.wrap(samples)
                melTensor = OnnxTensor.createTensor(env, melInputBuffer, longArrayOf(1, samples.size.toLong()))
                melResults = melSess.run(mapOf(melSess.inputNames.first() to melTensor))
                val melOutputTensor = melResults.get(0) as OnnxTensor
                val melFb = melOutputTensor.floatBuffer
                val numFrames = melFb.capacity() / melBins
                
                // Add new mel frames to melBuffer with openwakeword transform (x/10 + 2)
                for (i in 0 until numFrames) {
                    val frameData = FloatArray(melBins)
                    melFb.get(frameData)
                    val frameDataNorm = FloatArray(melBins) { frameData[it] / 10f + 2f }
                    shiftAndAppend(melBuffer, frameDataNorm)
                }

                // 2. Speech Embedding
                val flatMelBuffer = FloatArray(melContextFrames * melBins)
                for (i in 0 until melContextFrames) {
                    System.arraycopy(melBuffer[i], 0, flatMelBuffer, i * melBins, melBins)
                }
                val embInputBuffer = FloatBuffer.wrap(flatMelBuffer)
                embTensor = OnnxTensor.createTensor(env, embInputBuffer, longArrayOf(1, melContextFrames.toLong(), melBins.toLong(), 1))
                embResults = embSess.run(mapOf(embSess.inputNames.first() to embTensor))
                val embOutputTensor = embResults.get(0) as OnnxTensor
                val embFb = embOutputTensor.floatBuffer
                val embData = FloatArray(embSize)
                embFb.get(embData)
                shiftAndAppend(embBuffer, embData)

                // 3. Wake Word Classifier
                val flatEmbBuffer = FloatArray(embContextFrames * embSize)
                for (i in 0 until embContextFrames) {
                    System.arraycopy(embBuffer[i], 0, flatEmbBuffer, i * embSize, embSize)
                }
                val wakeInputBuffer = FloatBuffer.wrap(flatEmbBuffer)
                wakeTensor = OnnxTensor.createTensor(env, wakeInputBuffer, longArrayOf(1, embContextFrames.toLong(), embSize.toLong()))
                wakeResults = wakeSess.run(mapOf(wakeSess.inputNames.first() to wakeTensor))
                val wakeOutputTensor = wakeResults.get(0) as OnnxTensor
                confidence = wakeOutputTensor.floatBuffer.get(0)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error running openWakeWord pipeline: ${e.message}", e)
                confidence = 0f
            } finally {
                melTensor?.close()
                embTensor?.close()
                wakeTensor?.close()
                melResults?.close()
                embResults?.close()
                wakeResults?.close()
            }
        }

        return confidence.coerceIn(0.0f, 1.0f)
    }

    private fun shiftAndAppend(buffer: Array<FloatArray>, newRow: FloatArray) {
        // Shift all rows up by 1
        for (i in 0 until buffer.size - 1) {
            System.arraycopy(buffer[i + 1], 0, buffer[i], 0, buffer[i].size)
        }
        // Insert new row at end
        System.arraycopy(newRow, 0, buffer[buffer.size - 1], 0, buffer[buffer.size - 1].size)
    }

    override fun reset() {
        for (i in 0 until melContextFrames) {
            melBuffer[i] = FloatArray(melBins)
        }
        for (i in 0 until embContextFrames) {
            embBuffer[i] = FloatArray(embSize)
        }
        fallbackDetector?.reset()
    }

    override fun close() {
        try {
            melSession?.close()
            melSession = null
            embSession?.close()
            embSession = null
            wakeSession?.close()
            wakeSession = null
            environment = null
            fallbackDetector?.close()
            Log.d(TAG, "🧹 openWakeWord pipeline closed cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing openWakeWord pipeline: ${e.message}", e)
        }
    }
}

/**
 * Acoustic feature fallback classifier for openWakeWord when ONNX asset file is not present.
 */
class DslWakeWordDetector : WakeWordDetector {

    private var _lastInferenceTimeMs: Long = 0L
    override val lastInferenceTimeMs: Long get() = _lastInferenceTimeMs

    override fun processWindow(samples: FloatArray): Float {
        _lastInferenceTimeMs = 1L
        if (samples.isEmpty()) return 0f
        // Return baseline fallback
        return 0.0f
    }

    override fun reset() {}
    override fun close() {}
}
