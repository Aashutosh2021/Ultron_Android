package com.flexfly.ultron.voice.session

/**
 * Metric snapshot tracking an individual active or historical voice session.
 */
data class SessionMetrics(
    val sessionId: String = "",
    val startTime: Long = 0L,
    val durationMs: Long = 0L,
    val interruptCount: Int = 0,
    val wakeCount: Int = 0,
    val verificationCount: Int = 0,
    val silenceTimeoutCount: Int = 0,
    val endReason: String = "",
)

/**
 * Accumulated statistics across multiple voice sessions.
 */
data class SessionStatistics(
    val totalSessions: Int = 0,
    val totalInterrupts: Int = 0,
    val totalSilenceTimeouts: Int = 0,
    val averageDurationMs: Long = 0L,
)
