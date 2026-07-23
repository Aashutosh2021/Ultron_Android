package com.flexfly.ultron.ai.conversation

import android.content.Context
import android.util.Log
import com.flexfly.ultron.voice.session.VoiceSessionEvent
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

/**
 * Top-Level Provider-Independent Conversation Routing Engine for ULTRON.
 *
 * ZERO BUSINESS LOGIC:
 *  - Serves exclusively as a transport router between [VoiceSessionManager] and active [ConversationProvider].
 *  - Subscribes to [VoiceSessionManager.events] and forwards streaming frames & cancellation signals.
 */
class ConversationEngine(
    private val context: Context,
    val config: ConversationConfig = ConversationConfig(),
    
    private val customProvider: ConversationProvider? = null,
) {
    companion object {
        private const val TAG = "ConversationEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var sessionObservationJob: Job? = null
    private var providerEventsJob: Job? = null

    val provider: ConversationProvider = customProvider ?: ProviderFactory.createProvider(config)
    val activeProviderName: String get() = provider.providerName

    val state: StateFlow<ConversationState> get() = provider.state

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    val metrics: StateFlow<ConversationMetrics> get() = provider.metrics

    fun start() {
        if (sessionObservationJob?.isActive == true) return

        scope.launch {
            Log.d(TAG, "🟢 Initializing ConversationEngine with provider '$activeProviderName'")
            provider.initialize()
        }

        providerEventsJob = scope.launch {
            provider.events.collect { providerEvent ->
                _events.emit(providerEvent)
            }
        }

        sessionObservationJob = scope.launch {
        }
    }

    private suspend fun handleSessionEvent(event: VoiceSessionEvent) {
        when (event) {
            is VoiceSessionEvent.SessionStarted -> {
                Log.d(TAG, "⚡ SessionStarted event received (SessionID: ${event.sessionId}). Connecting provider...")
                provider.connect(event.sessionId)
            }

            is VoiceSessionEvent.UserInterrupted -> {
                Log.d(TAG, "⚡ UserInterrupted event received. Invoking provider.cancelGeneration()...")
                provider.cancelGeneration()
            }

            is VoiceSessionEvent.SessionEnded, is VoiceSessionEvent.SilenceTimeout -> {
                Log.d(TAG, "🛑 Session ended event received. Disconnecting provider...")
                provider.disconnect()
            }

            is VoiceSessionEvent.AuthorizationFailed -> {
                Log.w(TAG, "⚠️ Authorization failed event. Provider ignoring.")
            }

            else -> {}
        }
    }

    suspend fun sendAudioChunk(pcmChunk: FloatArray) {
        provider.sendAudio(pcmChunk)
    }

    suspend fun injectMessage(text: String) {
        provider.injectMessage(text)
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping ConversationEngine")
        sessionObservationJob?.cancel()
        sessionObservationJob = null
        providerEventsJob?.cancel()
        providerEventsJob = null
        scope.launch { provider.disconnect() }
    }

    fun release() {
        stop()
        try {
            provider.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ConversationEngine provider: ${e.message}", e)
        }
    }
}
