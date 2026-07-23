package com.flexfly.ultron.voice.session

/**
 * Deterministic 10-state machine representing the voice session lifecycle.
 */
sealed class VoiceSessionState {
    /** No active voice session. System in low-power idle listening mode. */
    data object Idle : VoiceSessionState()

    /** Speaker authorized; session created and initializing. */
    data class Starting(val sessionId: String) : VoiceSessionState()

    /** Session ready and waiting for user speech input. */
    data class WaitingForUser(val sessionId: String) : VoiceSessionState()

    /** User is actively speaking. */
    data class Listening(val sessionId: String) : VoiceSessionState()

    /** User speech completed; processing turn for AI execution. */
    data class Processing(val sessionId: String) : VoiceSessionState()

    /** Assistant response stream or TTS playback is active. */
    data class Responding(val sessionId: String) : VoiceSessionState()

    /** User interrupted assistant response during speech. */
    data class Interrupted(val sessionId: String) : VoiceSessionState()

    /** Teardown sequence initiated for current session. */
    data class Ending(val sessionId: String, val reason: String) : VoiceSessionState()

    /** Session closed cleanly. */
    data class Ended(val sessionId: String, val reason: String) : VoiceSessionState()

    /** Error state for unexpected failures or state violations. */
    data class Error(val message: String, val cause: Throwable? = null) : VoiceSessionState()
}
