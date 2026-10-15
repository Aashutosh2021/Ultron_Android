package com.flexfly.ultron.voice.speaker

import android.content.Context
import android.util.Log
import com.flexfly.ultron.voice.AudioFrame
import com.flexfly.ultron.voice.wakeword.WakeWordManager
import com.flexfly.ultron.voice.wakeword.WakeWordState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates Speaker Verification downstream of [WakeWordManager].
 *
 * Subscribes to `WakeWordManager.state` and performs ECAPA-TDNN embedding
 * extraction & cosine similarity verification whenever a wake word trigger occurs.
 */
class SpeakerVerificationManager(
    private val context: Context,
    val config: SpeakerConfig = SpeakerConfig(),
    private val wakeWordManager: WakeWordManager,
    private val customVerifier: SpeakerVerifier? = null,
) {
    companion object {
        private const val TAG = "SpeakerVerificationManager"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeObservationJob: Job? = null

    val store = SpeakerStore(context)
    val enrollmentManager = EnrollmentManager(store, config)
    private val verifier: SpeakerVerifier = customVerifier ?: EcapaTdnnVerifier(context, config.modelPath, config.embeddingSize)

    private val _verificationState = MutableStateFlow<VerificationResult>(
        // Start as NotEnrolled; wake-word trigger will auto-verify if no profile exists.
        if (store.hasOwnerProfile()) VerificationResult.Ready else VerificationResult.NotEnrolled
    )
    val verificationState: StateFlow<VerificationResult> = _verificationState.asStateFlow()

    private val _debugInfo = MutableStateFlow(
        SpeakerDebugInfo(
            isEnrolled = store.hasOwnerProfile(),
            status = _verificationState.value,
            threshold = config.similarityThreshold,
        )
    )
    val debugInfo: StateFlow<SpeakerDebugInfo> = _debugInfo.asStateFlow()

    private var verificationCount = 0

    fun start() {
        if (wakeObservationJob?.isActive == true) return

        wakeObservationJob = scope.launch {
            Log.d(TAG, "🚀 SpeakerVerificationManager observing WakeWordManager.state")
            wakeWordManager.state.collect { wakeState ->
                handleWakeStateChange(wakeState)
            }
        }
    }

    private fun handleWakeStateChange(wakeState: WakeWordState) {
        if (wakeState is WakeWordState.WakeDetected) {
            val current = _verificationState.value
            if (current is VerificationResult.Verifying || current is VerificationResult.Verified) {
                Log.d(TAG, "⚠️ Speaker verification already active (${current.javaClass.simpleName}). Ignoring duplicate wake event.")
                return
            }
            Log.d(TAG, "⚡ Wake word detected event received. Starting speaker verification...")
            // Verify against the ACTUAL utterance that triggered the wake word, captured by
            // the wake-word stage — not a silent default buffer.
            scope.launch {
                val utterance = wakeWordManager.snapshotRecentAudio()
                if (utterance.isNotEmpty()) {
                    verifyCurrentSpeaker(utterance)
                } else {
                    verifyCurrentSpeaker()
                }
            }
        }
    }

    fun forceVerify() {
        Log.d(TAG, "⚡ Manual trigger — auto-verifying speaker")
        updateState(VerificationResult.Verified(1.0f, System.currentTimeMillis()))
    }

    suspend fun verifyCurrentSpeaker(audioSegment: List<AudioFrame> = emptyList()): VerificationResult {
        val ownerProfile = store.getOwnerProfile()

        if (ownerProfile == null) {
            // No profile enrolled — auto-approve so voice works without enrollment.
            Log.d(TAG, "⚡ No owner profile enrolled — auto-verifying speaker")
            val result = VerificationResult.Verified(1.0f, System.currentTimeMillis())
            updateState(result)
            return result
        }

        return kotlinx.coroutines.withContext(Dispatchers.Default) {
            updateState(VerificationResult.Verifying)
            val currentEmbedding = verifier.extractEmbedding(audioSegment)
            val inferenceTime = verifier.lastInferenceTimeMs

            if (enrollmentManager.isEnrolling) {
                val done = enrollmentManager.addSample(currentEmbedding)
                _debugInfo.value = _debugInfo.value.copy(
                    enrollmentProgress = enrollmentManager.currentSampleCount,
                )
                if (done) {
                    updateState(VerificationResult.Ready)
                }
                // Return a special rejected state so the pipeline doesn't wake up during enrollment.
                return@withContext VerificationResult.Rejected(0f, System.currentTimeMillis())
            }

            val similarity = ownerProfile.cosineSimilarity(currentEmbedding)
            verificationCount++
            val timestamp = System.currentTimeMillis()

            if (similarity >= config.similarityThreshold) {
                Log.d(TAG, "✅ [VERIFIED OWNER] Cosine Similarity: ${"%.3f".format(similarity)} >= ${config.similarityThreshold}")
                val result = VerificationResult.Verified(similarity, timestamp)
                updateState(result)
                _debugInfo.value = _debugInfo.value.copy(
                    status = result,
                    similarityScore = similarity,
                    inferenceTimeMs = inferenceTime,
                    verificationCount = verificationCount,
                    timestamp = timestamp,
                )
                result
            } else {
                Log.w(TAG, "❌ [REJECTED SPEAKER] Cosine Similarity: ${"%.3f".format(similarity)} < ${config.similarityThreshold}")
                val result = VerificationResult.Rejected(similarity, timestamp)
                updateState(result)
                _debugInfo.value = _debugInfo.value.copy(
                    status = result,
                    similarityScore = similarity,
                    inferenceTimeMs = inferenceTime,
                    verificationCount = verificationCount,
                    timestamp = timestamp,
                )
                result
            }
        }
    }

    fun startEnrollment() {
        enrollmentManager.startEnrollment()
        _debugInfo.value = _debugInfo.value.copy(
            enrollmentProgress = 0,
            status = VerificationResult.NotEnrolled,
        )
    }

    fun cancelEnrollment() {
        enrollmentManager.cancelEnrollment()
        resetVerificationState()
        _debugInfo.value = _debugInfo.value.copy(
            enrollmentProgress = 0,
        )
    }

    fun clearEnrollment() {
        store.clearOwnerProfile()
        enrollmentManager.cancelEnrollment()
        updateState(VerificationResult.NotEnrolled)
        _debugInfo.value = _debugInfo.value.copy(
            isEnrolled = false,
            status = VerificationResult.NotEnrolled,
            similarityScore = 0f,
            enrollmentProgress = 0,
        )
    }

    fun resetVerificationState() {
        val defaultState = if (store.hasOwnerProfile()) VerificationResult.Ready else VerificationResult.NotEnrolled
        updateState(defaultState)
    }

    private fun updateState(newState: VerificationResult) {
        val previous = _verificationState.value
        if (previous != newState) {
            Log.d(TAG, "🔄 [SPEAKER TRANSITION] ${previous.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _verificationState.value = newState
            _debugInfo.value = _debugInfo.value.copy(
                isEnrolled = store.hasOwnerProfile(),
                status = newState,
            )
        }
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping SpeakerVerificationManager")
        wakeObservationJob?.cancel()
        wakeObservationJob = null
    }

    fun release() {
        stop()
        try {
            verifier.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing SpeakerVerifier: ${e.message}", e)
        }
    }
}
