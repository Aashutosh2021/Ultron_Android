package com.flexfly.ultron.voice.wakeword

import android.content.Context
import android.util.Log
import com.flexfly.ultron.voice.AudioBuffer
import com.flexfly.ultron.voice.AudioFrame
import com.flexfly.ultron.voice.VadManager
import com.flexfly.ultron.voice.VadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates Wake Word Detection downstream of [VadManager].
 *
 * Runs openWakeWord inference ONLY when VAD reports active speech ([VadState.Speaking]
 * or [VadState.SpeechStarted]). When VAD is idle, inference is completely paused,
 * conserving battery and CPU.
 */
class WakeWordManager(
    private val context: Context,
    val config: WakeWordConfig = WakeWordConfig(),
    private val vadManager: VadManager,
    private val customDetector: WakeWordDetector? = null,
    var sessionManager: com.flexfly.ultron.voice.session.VoiceSessionManager? = null,
) {
    companion object {
        private const val TAG = "WakeWordManager"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var vadObservationJob: Job? = null
    private var audioObservationJob: Job? = null
    private var cooldownJob: Job? = null

    private val detector: WakeWordDetector = customDetector ?: OpenWakeWordDetector(context, config.modelPath)
    private val audioWindow = AudioBuffer(capacity = config.windowSamples * 2)

    // Rolling un-normalized float buffer for openWakeWord chunking (1280 samples).
    private var pcmBuffer = FloatArray(0)

    // Recent captured audio frames (up to ~1s) retained so the downstream Speaker stage can verify
    // against the actual utterance that triggered the wake word.
    private val recentFrames = ArrayList<AudioFrame>()
    private val recentFramesLock = Any()

    /** Snapshot of the most recent audio frames for speaker verification. */
    fun snapshotRecentAudio(): List<AudioFrame> {
        synchronized(recentFramesLock) {
            return ArrayList(recentFrames)
        }
    }

    private val _state = MutableStateFlow<WakeWordState>(WakeWordState.Idle)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    private val _debugInfo = MutableStateFlow(
        WakeWordDebugInfo(modelName = config.modelPath)
    )
    val debugInfo: StateFlow<WakeWordDebugInfo> = _debugInfo.asStateFlow()

    private var detectionCount = 0
    private var lastTriggerTime = 0L

    fun start() {
        if (vadObservationJob?.isActive == true) return

        vadObservationJob = scope.launch {
            Log.d(TAG, "🚀 WakeWordManager observing VadManager.state")
            vadManager.state.collect { vadState ->
                handleVadStateChange(vadState)
            }
        }

        // Subscribe to the raw audio-frame bus so wake-word inference actually receives
        // PCM. Frames are processed only while in Listening (gated below), so this is
        // cheap when VAD is idle.
        audioObservationJob = scope.launch {
            vadManager.audioFrames.collect { frame ->
                processAudioFrame(frame)
            }
        }
    }

    /**
     * Accepts an audio frame from [VadManager.audioFrames], buffers raw PCM, 
     * and runs openWakeWord inference on 1280-sample chunks.
     */
    private fun processAudioFrame(frame: AudioFrame) {
        val currentState = _state.value
        if (currentState is WakeWordState.Cooldown || currentState is WakeWordState.WakeDetected) return

        // CRITICAL GATE: Do not detect wake words if a voice session is already active!
        val sessionState = sessionManager?.sessionState?.value
        if (sessionState != null && sessionState !is com.flexfly.ultron.voice.session.VoiceSessionState.Idle && sessionState !is com.flexfly.ultron.voice.session.VoiceSessionState.Ended) {
            return
        }

        // Convert raw PCM to float (un-normalized) for WakeWord and Speaker stages
        val rawPcmFloats = FloatArray(frame.pcmData.size) { frame.pcmData[it].toFloat() }

        // 1. Maintain rolling ~1s tail for Speaker stage using raw AudioFrames
        synchronized(recentFramesLock) {
            recentFrames.add(frame)
            var totalSamples = recentFrames.sumOf { it.pcmData.size }
            while (totalSamples > config.sampleRate) {
                val removed = recentFrames.removeAt(0)
                totalSamples -= removed.pcmData.size
            }
        }

        // 2. Append raw PCM to the chunking buffer
        val newBuffer = FloatArray(pcmBuffer.size + rawPcmFloats.size)
        System.arraycopy(pcmBuffer, 0, newBuffer, 0, pcmBuffer.size)
        System.arraycopy(rawPcmFloats, 0, newBuffer, pcmBuffer.size, rawPcmFloats.size)
        pcmBuffer = newBuffer

        var latestScore = 0.0f

        // 3. Process 1280-sample chunks (the proven a-periodic loop)
        while (pcmBuffer.size >= 1280) {
            val chunk = pcmBuffer.copyOfRange(0, 1280)
            pcmBuffer = pcmBuffer.copyOfRange(1280, pcmBuffer.size)

            val confidence = detector.processWindow(chunk)
            val inferenceTime = detector.lastInferenceTimeMs

            if (confidence > latestScore) {
                latestScore = confidence
            }
        }

        if (latestScore > 0f) {
            Log.d(TAG, "WakeWord score: $latestScore")
            if (latestScore >= config.wakeThreshold) {
                triggerWakeWord(latestScore)
            } else {
                _debugInfo.value = _debugInfo.value.copy(
                    state = _state.value,
                    confidence = latestScore,
                    inferenceTimeMs = detector.lastInferenceTimeMs,
                    timestamp = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun handleVadStateChange(vadState: VadState) {
        when (vadState) {
            is VadState.SpeechStarted, is VadState.Speaking -> {
                if (_state.value is WakeWordState.Idle) {
                    Log.d(TAG, "listening for wake word")
                    Log.d(TAG, "🎙️ VAD speech active ➔ WakeWordManager entering LISTENING")
                    updateState(WakeWordState.Listening)
                }
            }

            is VadState.Idle, is VadState.SpeechEnded -> {
                if (_state.value !is WakeWordState.Cooldown) {
                    // DO NOT clear pcmBuffer or reset detector — standalone never does this.
                    // Mel/embedding context buffers must persist across speech segments
                    // to keep scores meaningful.
                    if (_state.value !is WakeWordState.Idle) {
                        Log.d(TAG, "💤 VAD silent ➔ WakeWordManager entering IDLE (buffers preserved)")
                        updateState(WakeWordState.Idle)
                    }
                }
            }

            is VadState.Error -> {
                updateState(WakeWordState.Error(vadState.message, vadState.cause))
            }
        }
    }


    private fun triggerWakeWord(confidence: Float) {
        detectionCount++
        lastTriggerTime = System.currentTimeMillis()

        Log.d(TAG, "wake word detected")
        Log.d(TAG, "⚡ [WAKE WORD DETECTED!] Confidence: ${"%.3f".format(confidence)}, Model: ${config.modelPath}")

        val detectedState = WakeWordState.WakeDetected(confidence, lastTriggerTime)
        updateState(detectedState)

        _debugInfo.value = _debugInfo.value.copy(
            state = detectedState,
            confidence = confidence,
            inferenceTimeMs = detector.lastInferenceTimeMs,
            detectionCount = detectionCount,
            lastTriggerTimestamp = lastTriggerTime,
            timestamp = lastTriggerTime,
        )

        // Start cooldown timer to prevent duplicate triggers within the same utterance
        startCooldown()
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            Log.d(TAG, "⏳ WakeWord Cooldown started (${config.cooldownMs}ms)")
            updateState(WakeWordState.Cooldown)
            delay(config.cooldownMs)
            Log.d(TAG, "✅ WakeWord Cooldown ended")

            val vadActive = vadManager.state.value is VadState.Speaking || vadManager.state.value is VadState.SpeechStarted
            if (vadActive) {
                updateState(WakeWordState.Listening)
            } else {
                updateState(WakeWordState.Idle)
            }
        }
    }

    private fun updateState(newState: WakeWordState) {
        val previous = _state.value
        if (previous != newState) {
            Log.d(TAG, "🔄 [WAKE STATE TRANSITION] ${previous.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _state.value = newState
            _debugInfo.value = _debugInfo.value.copy(state = newState)
        }
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping WakeWordManager")
        vadObservationJob?.cancel()
        vadObservationJob = null
        audioObservationJob?.cancel()
        audioObservationJob = null
        cooldownJob?.cancel()
        cooldownJob = null
        pcmBuffer = FloatArray(0)
        detector.reset()
        updateState(WakeWordState.Idle)
    }

    fun release() {
        stop()
        try {
            detector.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WakeWordDetector: ${e.message}", e)
        }
    }
}
