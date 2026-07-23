package com.flexfly.ultron.voice.session

/**
 * High-level lifecycle events broadcast over SharedFlow to future AI modules
 * (Gemini Live Connector, Brain Engine, Tool Framework, Memory Engine, TTS).
 */
sealed class VoiceSessionEvent {
    data class SessionStarted(
        val sessionId: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : VoiceSessionEvent()

    data class SessionReady(val sessionId: String) : VoiceSessionEvent()

    data class UserSpeechStarted(val sessionId: String) : VoiceSessionEvent()

    data class UserSpeechEnded(val sessionId: String) : VoiceSessionEvent()

    data class AssistantStartedSpeaking(val sessionId: String) : VoiceSessionEvent()

    data class AssistantFinishedSpeaking(val sessionId: String) : VoiceSessionEvent()

    data class UserInterrupted(
        val sessionId: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : VoiceSessionEvent()

    data class SilenceTimeout(val sessionId: String) : VoiceSessionEvent()

    data class SessionEnded(val sessionId: String, val reason: String) : VoiceSessionEvent()

    data class SessionCancelled(val sessionId: String) : VoiceSessionEvent()

    data class AuthorizationFailed(
        val timestamp: Long = System.currentTimeMillis(),
    ) : VoiceSessionEvent()
}
