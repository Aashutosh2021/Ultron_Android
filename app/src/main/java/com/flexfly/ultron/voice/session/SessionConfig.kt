package com.flexfly.ultron.voice.session

/**
 * Immutable configuration for Voice Session Orchestration.
 *
 * All parameters are configurable to prevent hardcoded magic numbers.
 */
data class SessionConfig(
    val initialSessionTimeoutMs: Long = 10000L, // Timeout waiting for first user utterance after verification
    val silenceTimeoutMs: Long = 5000L, // Silence timeout between conversation turns
    val maxSessionDurationMs: Long = 300000L, // 5 minutes max continuous session duration
    val interruptionWindowMs: Long = 300L, // Minimum speech onset duration to trigger interruption
    val retryCount: Int = 3,
)
