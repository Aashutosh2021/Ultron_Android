package com.flexfly.ultron.ai.conversation

/**
 * Robust 11-state machine representing AI Provider connection and streaming status.
 */
sealed class ConversationState {
    data object Disconnected : ConversationState()
    data object Initializing : ConversationState()
    data object Connecting : ConversationState()
    data object Connected : ConversationState()
    data object StreamingInput : ConversationState()
    data object Generating : ConversationState()
    data object StreamingOutput : ConversationState()
    data object Cancelling : ConversationState()
    data object Reconnecting : ConversationState()
    data object Closed : ConversationState()
    data class Error(val message: String, val cause: Throwable? = null) : ConversationState()
}
