package com.flexfly.ultron.voice.speaker

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.flexfly.ultron.voice.AudioFrame
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.system.measureTimeMillis

/**
 * Abstraction for Speaker Verification embedding extraction.
 *
 * Wraps ECAPA-TDNN ONNX inference behind a clean contract so another embedding
 * model (e.g. ResNet34, x-vector) can replace it without changing the rest of the application.
 */
interface SpeakerVerifier : AutoCloseable {
    /** Last ONNX embedding extraction duration in milliseconds. */
    val lastInferenceTimeMs: Long

    /** Extracts a 192-dimensional normalized [SpeakerEmbedding] from a sequence of audio frames. */
    fun extractEmbedding(frames: List<AudioFrame>): SpeakerEmbedding

    /** Resets internal model state / buffers. */
    fun reset()

    /** Releases native ONNX sessions and resources. */
    override fun close()
}

/**
 * ECAPA-TDNN ONNX Runtime implementation.
 *
 * Loads `ecapa.onnx` from application assets and extracts 192-dimensional
 * speaker embedding vectors using the proven pipeline logic.
 */
class EcapaTdnnVerifier(
    private val context: Context,
    val modelName: String = "ecapa.onnx",
    private val embeddingSize: Int = 192,
) : SpeakerVerifier {

    companion object {
        private const val TAG = "EcapaTdnnVerifier"
    }

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var fallbackVerifier: DslSpeakerVerifier? = null

    // OrtSession.run is NOT thread-safe. Verification and enrollment can run on separate
    // coroutines against the same session — serialize every inference through this lock.
    private val inferenceLock = Any()

    private var _lastInferenceTimeMs: Long = 0L
    override val lastInferenceTimeMs: Long get() = _lastInferenceTimeMs

    init {
        try {
            val modelBytes = context.assets.open(modelName).use { it.readBytes() }
            environment = OrtEnvironment.getEnvironment()
            session = environment?.createSession(modelBytes, OrtSession.SessionOptions())
            Log.d(TAG, "✅ ECAPA-TDNN ONNX model '$modelName' loaded successfully")
        } catch (e: Exception) {
            // PRODUCTION SAFETY: the DSL fallback is a non-biometric projection — verifying
            // against it would be a security hole. Debug-only; release fails hard.
            if (com.flexfly.ultron.BuildConfig.DEBUG) {
                Log.w(TAG, "⚠️ [DEBUG] ECAPA-TDNN asset '$modelName' missing/failed (${e.message}). Using non-biometric fallback.")
                fallbackVerifier = DslSpeakerVerifier(embeddingSize)
            } else {
                Log.e(TAG, "❌ ECAPA-TDNN model '$modelName' missing or failed to load in RELEASE build.", e)
                throw IllegalStateException(
                    "Required speaker model '$modelName' is missing or failed to initialize. " +
                        "Ship the ONNX asset — speaker verification must not run on a stub.", e,
                )
            }
        }
    }

    override fun extractEmbedding(frames: List<AudioFrame>): SpeakerEmbedding {
        val fallback = fallbackVerifier
        if (fallback != null) {
            val emb = fallback.extractEmbedding(frames)
            _lastInferenceTimeMs = fallback.lastInferenceTimeMs
            return emb
        }

        val env = environment ?: return SpeakerEmbedding(FloatArray(embeddingSize))
        val sess = session ?: return SpeakerEmbedding(FloatArray(embeddingSize))

        var resultVector = FloatArray(embeddingSize)

        _lastInferenceTimeMs = measureTimeMillis {
            try {
                synchronized(inferenceLock) {
                    // Proven logic: aggregate all frames into one normalized float array
                    val totalLength = frames.sumOf { it.pcmData.size }
                    if (totalLength < 400) {
                        Log.w(TAG, "Audio too short for ECAPA embedding ($totalLength samples)")
                        return@measureTimeMillis
                    }

                    val floatPcm = FloatArray(totalLength)
                    var offset = 0
                    for (f in frames) {
                        val floats = f.toFloatArray()
                        System.arraycopy(floats, 0, floatPcm, offset, floats.size)
                        offset += floats.size
                    }

                    // Compute 80-bin Log-Mel Spectrogram features [num_frames, 80]
                    val logMelSpec = com.flexfly.ultron.voice.AudioFeatureExtractor.computeLogMelSpectrogram(floatPcm)
                    val numFrames = logMelSpec.size
                    if (numFrames == 0) return@measureTimeMillis

                    // Flatten 2D features into FloatArray for 3D tensor [1, numFrames, 80]
                    val flatSpec = FloatArray(1 * numFrames * 80)
                    var idx = 0
                    for (f in 0 until numFrames) {
                        val frameMels = logMelSpec[f]
                        for (m in 0 until 80) {
                            flatSpec[idx++] = frameMels[m]
                        }
                    }

                    val inputName = sess.inputNames.firstOrNull() ?: "input"
                    val floatBuffer = FloatBuffer.wrap(flatSpec)
                    val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, numFrames.toLong(), 80))

                    val inputs = mapOf(inputName to inputTensor)
                    val results = sess.run(inputs)

                    // Proven flattening logic
                    val outputVal = results.get(0)?.value
                    resultVector = flattenOutput(outputVal) ?: FloatArray(embeddingSize)

                    inputTensor.close()
                    results.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating ECAPA-TDNN embedding: ${e.message}", e)
                resultVector = FloatArray(embeddingSize)
            }
        }

        return SpeakerEmbedding(resultVector).normalize()
    }

    private fun flattenOutput(obj: Any?): FloatArray? {
        return when (obj) {
            is FloatArray -> obj
            is Array<*> -> {
                val first = obj.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is Array<*> -> (first.firstOrNull() as? FloatArray)
                    else -> null
                }
            }
            else -> null
        }
    }

    override fun reset() {
        fallbackVerifier?.reset()
    }

    override fun close() {
        try {
            session?.close()
            session = null
            // OrtEnvironment is a process-wide singleton shared across detectors — drop the
            // reference but never close it here (would invalidate VAD/wake-word sessions).
            environment = null
            fallbackVerifier?.close()
            Log.d(TAG, "🧹 ECAPA-TDNN verifier closed cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ECAPA-TDNN verifier: ${e.message}", e)
        }
    }
}

/**
 * Acoustic spectral feature fallback embedding generator for testing when ONNX asset file is absent.
 */
class DslSpeakerVerifier(private val dimension: Int = 192) : SpeakerVerifier {

    private var _lastInferenceTimeMs: Long = 0L
    override val lastInferenceTimeMs: Long get() = _lastInferenceTimeMs

    override fun extractEmbedding(frames: List<AudioFrame>): SpeakerEmbedding {
        _lastInferenceTimeMs = 2L
        val vector = FloatArray(dimension)
        if (frames.isEmpty()) return SpeakerEmbedding(vector)

        // Deterministic spectral feature projection fallback
        val combined = FloatArray(frames.sumOf { it.pcmData.size })
        var offset = 0
        for (f in frames) {
            val floats = f.toFloatArray()
            System.arraycopy(floats, 0, combined, offset, floats.size)
            offset += floats.size
        }

        for (i in combined.indices) {
            val idx = i % dimension
            vector[idx] += combined[i] * (i + 1)
        }
        return SpeakerEmbedding(vector).normalize()
    }

    override fun reset() {}
    override fun close() {}
}
