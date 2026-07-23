package com.flexfly.ultron.ai.conversation

/**
 * Data class representing an active AI conversation session instance.
 */
data class ConversationSession(
    val sessionId: String,
    val providerName: String,
    val startTime: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
)
