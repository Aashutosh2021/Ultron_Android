package com.flexfly.ultron.ai.conversation

/**
 * Factory creating target [ConversationProvider] instances.
 *
 * Encapsulates provider instantiation so the engine and rest of ULTRON
 * remain completely independent of concrete provider classes.
 */
object ProviderFactory {

    fun createProvider(config: ConversationConfig): ConversationProvider {
        return when (config.providerType) {
            ProviderType.GEMINI_LIVE -> GeminiLiveProvider(config)
            ProviderType.MOCK -> MockConversationProvider(config)
            else -> GeminiLiveProvider(config) // Fallback for future providers
        }
    }
}
