package com.flexfly.ultron.ai.conversation

enum class ProviderType {
    GEMINI_LIVE,
    OPENAI_REALTIME,
    QWEN_LOCAL,
    GEMMA_LOCAL,
    MOCK,
}

/**
 * Immutable configuration for AI Conversation Providers.
 *
 * All parameters are configurable to prevent hardcoded magic numbers.
 */
data class ConversationConfig(
    val providerType: ProviderType = ProviderType.MOCK,
    val connectionTimeoutMs: Long = 10000L,
    val reconnectDelayMs: Long = 2000L,
    val retryCount: Int = 3,
    val heartbeatIntervalMs: Long = 15000L,
    val streamingChunkSizeBytes: Int = 4096,
    val apiKey: String = "",
    val endpointUrl: String = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent",
)
