package com.flexfly.ultron.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the VAD pipeline, managing the hardware recorder, neural model,
 * and anti-flickering State Machine.
 *
 * Implements hysteresis thresholds and timing windows to guarantee smooth,
 * natural state transitions:
 * IDLE -> SpeechStarted -> Speaking -> SpeechEnded -> IDLE
 */
class VadManager(
    private val context: Context,
    val config: AudioConfig = AudioConfig(),
    private val customDetector: VoiceActivityDetector? = null,
) {
    companion object {
        private const val TAG = "VadManager"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private val recorder = AudioRecorder(context, config)
    private val detector: VoiceActivityDetector = customDetector ?: SileroVadDetector(context)

    private val _state = MutableStateFlow<VadState>(VadState.Idle)
    val state: StateFlow<VadState> = _state.asStateFlow()

    // Raw audio-frame bus. Downstream stages (wake word, speaker) subscribe to receive
    // the actual PCM frames — previously VAD published only state, so no audio reached them.
    // DROP_OLDEST guarantees a slow consumer can never stall the capture loop.
    private val _audioFrames = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames.asSharedFlow()

    private val _debugInfo = MutableStateFlow(VadDebugInfo(sampleRate = config.sampleRate))
    val debugInfo: StateFlow<VadDebugInfo> = _debugInfo.asStateFlow()

    // State machine hysteresis counters
    private var continuousSpeechMs = 0L
    private var continuousSilenceMs = 0L
    private var totalDetections = 0
    private var speechFrames = 0
    private var silenceFrames = 0

    fun start() {
        if (job?.isActive == true) {
            Log.w(TAG, "⚠️ VadManager start ignored — already active")
            return
        }

        if (!recorder.hasMicPermission()) {
            val errorState = VadState.Error("Microphone permission (RECORD_AUDIO) missing")
            updateState(errorState)
            _debugInfo.value = _debugInfo.value.copy(
                state = errorState,
                micStatus = "ERROR: Permission Denied",
            )
            return
        }

        detector.reset()
        continuousSpeechMs = 0L
        continuousSilenceMs = 0L

        job = scope.launch {
            Log.d(TAG, "🚀 VadManager starting audio pipeline loop")
            _debugInfo.value = _debugInfo.value.copy(micStatus = "RECORDING")

            try {
                recorder.startStreaming().collect { frame ->
                    processAudioFrame(frame)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Security Exception: ${e.message}")
                updateState(VadState.Error("Permission denied: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "❌ VAD Pipeline Exception: ${e.message}", e)
                updateState(VadState.Error("Pipeline error: ${e.message}", e))
            } finally {
                _debugInfo.value = _debugInfo.value.copy(micStatus = "STOPPED")
            }
        }
    }

    suspend fun processAudioFrame(frame: AudioFrame) {
        // Publish the raw frame to downstream stages (wake word / speaker) BEFORE state
        // logic — they gate their own processing on VadState, but need the audio itself.
        _audioFrames.tryEmit(frame)

        val confidence = detector.processFrame(frame)
        totalDetections++
        val frameMs = config.frameDurationMs.toLong()

        if (totalDetections % 20 == 0) {
            val maxAmp = frame.pcmData.maxOfOrNull { kotlin.math.abs(it.toInt()) }?.toFloat()?.div(32768.0f) ?: 0f
            Log.d(TAG, "VAD Confidence: ${"%.4f".format(confidence)} (MaxAmp: ${"%.4f".format(maxAmp)})")
        }

        val isFrameSpeech = confidence >= config.speechThreshold
        val isFrameSilence = confidence <= config.silenceThreshold

        if (isFrameSpeech) {
            speechFrames++
            continuousSpeechMs += frameMs
            continuousSilenceMs = 0L
        } else if (isFrameSilence) {
            silenceFrames++
            continuousSilenceMs += frameMs
            // Maintain continuous speech duration if brief pause < silenceTimeout
        } else {
            // Indeterminate region between silenceThreshold and speechThreshold
            continuousSilenceMs += frameMs
        }

        val currentState = _state.value

        when (currentState) {
            is VadState.Idle -> {
                if (continuousSpeechMs >= config.minSpeechDurationMs) {
                    Log.d(TAG, "State Transition: IDLE ➔ SpeechStarted (Confidence: ${"%.2f".format(confidence)})")
                    updateState(VadState.SpeechStarted)
                    // Immediately progress to Speaking
                    updateState(VadState.Speaking)
                }
            }

            is VadState.SpeechStarted -> {
                updateState(VadState.Speaking)
            }

            is VadState.Speaking -> {
                if (continuousSilenceMs >= config.silenceTimeoutMs) {
                    Log.d(TAG, "State Transition: Speaking ➔ SpeechEnded (Silence: ${continuousSilenceMs}ms)")
                    updateState(VadState.SpeechEnded)
                    continuousSpeechMs = 0L
                    continuousSilenceMs = 0L
                    // Reset back to Idle after publishing SpeechEnded
                    updateState(VadState.Idle)
                }
            }

            is VadState.SpeechEnded -> {
                continuousSpeechMs = 0L
                continuousSilenceMs = 0L
                updateState(VadState.Idle)
            }

            is VadState.Error -> {
                // If error is resolved, resume Idle
                if (isFrameSpeech) {
                    updateState(VadState.Idle)
                }
            }
        }

        // Publish live metrics for debug UI
        _debugInfo.value = _debugInfo.value.copy(
            state = _state.value,
            confidence = confidence,
            micStatus = if (recorder.isActive) "RECORDING" else "IDLE",
            detectionCount = totalDetections,
            speechFramesCount = speechFrames,
            silenceFramesCount = silenceFrames,
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun updateState(newState: VadState) {
        val previous = _state.value
        if (previous != newState) {
            Log.d(TAG, "🔄 [VAD TRANSITION] ${previous.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _state.value = newState
        }
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping VadManager")
        recorder.stop()
        job?.cancel()
        job = null
        updateState(VadState.Idle)
        _debugInfo.value = _debugInfo.value.copy(micStatus = "STOPPED")
    }

    fun release() {
        stop()
        try {
            detector.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing detector: ${e.message}", e)
        }
    }
}
