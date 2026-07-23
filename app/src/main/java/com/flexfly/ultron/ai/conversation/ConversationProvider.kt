package com.flexfly.ultron.ai.conversation

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Provider Abstraction Contract for Real-Time AI Conversation Engines.
 *
 * All AI provider integrations (Gemini Live, OpenAI Realtime, Qwen Local, Gemma Local, Mock)
 * MUST implement this interface cleanly. No provider-specific code leaks outside this boundary.
 */
interface ConversationProvider : AutoCloseable {
    val providerName: String
    val state: StateFlow<ConversationState>
    val events: SharedFlow<ConversationEvent>
    val metrics: StateFlow<ConversationMetrics>

    suspend fun initialize()
    suspend fun connect(sessionId: String)
    suspend fun disconnect()
    suspend fun sendAudio(pcmChunk: FloatArray)
    suspend fun injectMessage(text: String)
    suspend fun cancelGeneration()
    override fun close()
    fun release()
}
