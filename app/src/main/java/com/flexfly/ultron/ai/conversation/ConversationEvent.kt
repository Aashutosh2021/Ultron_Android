package com.flexfly.ultron.ai.conversation

/**
 * Provider-independent streaming & connection events broadcast via SharedFlow.
 */
sealed class ConversationEvent {
    data class Connected(
        val providerName: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : ConversationEvent()

    data class Disconnected(val providerName: String) : ConversationEvent()

    data class ConnectionLost(val reason: String) : ConversationEvent()

    data class Reconnecting(val attempt: Int) : ConversationEvent()

    data class StreamingStarted(val direction: String) : ConversationEvent()

    data class StreamingStopped(val direction: String) : ConversationEvent()

    data class PartialResponse(
        val data: ByteArray = byteArrayOf(),
        val text: String = "",
    ) : ConversationEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PartialResponse) return false
            return data.contentEquals(other.data) && text == other.text
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + text.hashCode()
            return result
        }
    }

    data class FinalResponse(val text: String) : ConversationEvent()

    data class GenerationCancelled(
        val timestamp: Long = System.currentTimeMillis(),
    ) : ConversationEvent()

    data class ProviderError(
        val message: String,
        val cause: Throwable? = null,
    ) : ConversationEvent()
}
