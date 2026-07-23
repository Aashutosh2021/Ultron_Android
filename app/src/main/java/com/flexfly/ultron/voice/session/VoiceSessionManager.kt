package com.flexfly.ultron.voice.session

import android.content.Context
import android.util.Log
import com.flexfly.ultron.voice.speaker.SpeakerVerificationManager
import com.flexfly.ultron.voice.speaker.VerificationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Authoritative Orchestrator for Voice Session Lifecycles.
 *
 * Connects the Voice Stack (VAD, openWakeWord, ECAPA-TDNN) to future AI modules
 * (Gemini Live, Brain Engine, Tool Framework, Memory Engine, TTS).
 *
 * DEPENDENCY PRINCIPLE:
 *  - Subscribes ONLY to [SpeakerVerificationManager.verificationState] via [StateFlow].
 *  - Decoupled from hardware audio threads or raw VAD classes.
 */
class VoiceSessionManager(
    private val context: Context,
    val config: SessionConfig = SessionConfig(),
) {
    companion object {
        private const val TAG = "VoiceSessionManager"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var speakerObservationJob: Job? = null
    private val silenceDetector = SilenceDetector(scope)

    val _sessionState = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    val sessionState: StateFlow<VoiceSessionState> = _sessionState.asStateFlow()

    private val _events = MutableSharedFlow<VoiceSessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceSessionEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow(SessionMetrics())
    val metrics: StateFlow<SessionMetrics> = _metrics.asStateFlow()

    private val _stats = MutableStateFlow(SessionStatistics())
    val stats: StateFlow<SessionStatistics> = _stats.asStateFlow()

    private var currentSessionId: String? = null
    private var sessionStartTime: Long = 0L
    private var interruptCount = 0
    private var silenceTimeoutCount = 0

    private var speakerManagerRef: SpeakerVerificationManager? = null

    fun start(speakerManager: SpeakerVerificationManager? = null) {
        if (speakerObservationJob?.isActive == true) return
        speakerManagerRef = speakerManager
        
        if (speakerManager != null) {
            speakerObservationJob = scope.launch {
                speakerManager.verificationState.collect { result ->
                    handleVerificationResult(result)
                }
            }
        }
    }

    private suspend fun handleVerificationResult(result: VerificationResult) {
        when (result) {
            is VerificationResult.Verified -> {
                val current = _sessionState.value
                if (current !is VoiceSessionState.Idle && current !is VoiceSessionState.Ended) {
                    Log.w(TAG, "⚠️ Session already active (${current.javaClass.simpleName}). Rejecting duplicate startNewSession().")
                    return
                }
                Log.d(TAG, "⚡ Owner Verified! Creating new Voice Session...")
                startNewSession()
            }
            is VerificationResult.Rejected -> {
                if (_sessionState.value !is VoiceSessionState.Idle) {
                    endSession("AuthorizationFailed")
                } else {
                    emitEvent(VoiceSessionEvent.AuthorizationFailed())
                }
            }
            is VerificationResult.NotEnrolled -> {
                if (_sessionState.value !is VoiceSessionState.Idle) {
                    endSession("NotEnrolled")
                }
            }
            else -> {}
        }
    }

    private suspend fun startNewSession() {
        val newId = UUID.randomUUID().toString().take(8)
        currentSessionId = newId
        sessionStartTime = System.currentTimeMillis()
        interruptCount = 0

        Log.d(TAG, "🟢 [SESSION CREATED] SessionID: $newId")

        updateState(VoiceSessionState.Starting(newId))
        emitEvent(VoiceSessionEvent.SessionStarted(newId, sessionStartTime))

        _metrics.value = SessionMetrics(
            sessionId = newId,
            startTime = sessionStartTime,
            verificationCount = _metrics.value.verificationCount + 1,
        )

        updateState(VoiceSessionState.WaitingForUser(newId))
        emitEvent(VoiceSessionEvent.SessionReady(newId))

        // Start initial timeout waiting for user first utterance
        silenceDetector.startTimer(config.initialSessionTimeoutMs, "InitialUserUtterance") {
            handleSilenceTimeout()
        }
    }

    fun onUserSpeechStarted() {
        val id = currentSessionId ?: return
        val current = _sessionState.value

        when (current) {
            is VoiceSessionState.WaitingForUser -> {
                silenceDetector.stopTimer()
                updateState(VoiceSessionState.Listening(id))
                Log.d(TAG, "listening user")
                scope.launch { emitEvent(VoiceSessionEvent.UserSpeechStarted(id)) }
            }

            is VoiceSessionState.Responding -> {
                // Interruption handling!
                silenceDetector.stopTimer()
                interruptCount++
                Log.d(TAG, "⚡ [USER INTERRUPTION] User interrupted assistant response (Count: $interruptCount)")
                updateState(VoiceSessionState.Interrupted(id))
                _metrics.value = _metrics.value.copy(interruptCount = interruptCount)

                scope.launch {
                    emitEvent(VoiceSessionEvent.UserInterrupted(id))
                    // Transition back to Listening for user's new turn
                    updateState(VoiceSessionState.Listening(id))
                    Log.d(TAG, "listening user")
                    emitEvent(VoiceSessionEvent.UserSpeechStarted(id))
                }
            }

            else -> {}
        }
    }

    fun onUserSpeechEnded() {
        val id = currentSessionId ?: return
        if (_sessionState.value is VoiceSessionState.Listening) {
            updateState(VoiceSessionState.Processing(id))
            scope.launch { emitEvent(VoiceSessionEvent.UserSpeechEnded(id)) }
        }
    }

    fun onAssistantStartedSpeaking() {
        val id = currentSessionId ?: return
        if (_sessionState.value is VoiceSessionState.Processing) {
            updateState(VoiceSessionState.Responding(id))
            Log.d(TAG, "speaking")
            scope.launch { emitEvent(VoiceSessionEvent.AssistantStartedSpeaking(id)) }
        }
    }

    fun onAssistantFinishedSpeaking() {
        val id = currentSessionId ?: return
        if (_sessionState.value is VoiceSessionState.Responding) {
            updateState(VoiceSessionState.WaitingForUser(id))
            scope.launch {
                emitEvent(VoiceSessionEvent.AssistantFinishedSpeaking(id))
                // Start turn silence timeout
                silenceDetector.startTimer(config.silenceTimeoutMs, "TurnSilence") {
                    handleSilenceTimeout()
                }
            }
        }
    }

    private suspend fun handleSilenceTimeout() {
        val id = currentSessionId ?: return
        silenceTimeoutCount++
        Log.d(TAG, "⏰ Session Silence Timeout triggered for SessionID: $id")
        emitEvent(VoiceSessionEvent.SilenceTimeout(id))
        _metrics.value = _metrics.value.copy(silenceTimeoutCount = silenceTimeoutCount)
        endSession("SilenceTimeout")
    }

    fun endSession(reason: String) {
        val id = currentSessionId ?: return
        silenceDetector.stopTimer()

        val duration = System.currentTimeMillis() - sessionStartTime
        Log.d(TAG, "🛑 [SESSION ENDED] SessionID: $id (Duration: ${duration}ms, Reason: $reason)")

        scope.launch {
            updateState(VoiceSessionState.Ending(id, reason))
            emitEvent(VoiceSessionEvent.SessionEnded(id, reason))
            updateState(VoiceSessionState.Ended(id, reason))

            _metrics.value = _metrics.value.copy(
                durationMs = duration,
                endReason = reason,
            )

            // Accumulate statistics
            val oldStats = _stats.value
            _stats.value = SessionStatistics(
                totalSessions = oldStats.totalSessions + 1,
                totalInterrupts = oldStats.totalInterrupts + interruptCount,
                totalSilenceTimeouts = oldStats.totalSilenceTimeouts + silenceTimeoutCount,
                averageDurationMs = (oldStats.averageDurationMs * oldStats.totalSessions + duration) / (oldStats.totalSessions + 1),
            )

            currentSessionId = null
            speakerManagerRef?.resetVerificationState()
            updateState(VoiceSessionState.Idle)
        }
    }

    private fun updateState(newState: VoiceSessionState) {
        val previous = _sessionState.value
        if (previous != newState) {
            Log.d(TAG, "🔄 [SESSION TRANSITION] ${previous.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _sessionState.value = newState
        }
    }

    private suspend fun emitEvent(event: VoiceSessionEvent) {
        Log.d(TAG, "📢 [SESSION EVENT] ${event.javaClass.simpleName}")
        _events.emit(event)
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping VoiceSessionManager")
        speakerObservationJob = null
        silenceDetector.stopTimer()
        currentSessionId?.let { id ->
            scope.launch { updateState(VoiceSessionState.Ended(id, "ServiceStopped")) }
        }
        currentSessionId = null
        _sessionState.value = VoiceSessionState.Idle
    }

    fun release() {
        stop()
    }
}
