package com.flexfly.ultron.ai.conversation

/**
 * Metric snapshot tracking real-time provider latency, bytes streamed, and request counts.
 */
data class ConversationMetrics(
    val sessionId: String = "",
    val providerName: String = "MockProvider",
    val connectionTimeMs: Long = 0L,
    val reconnectCount: Int = 0,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val averageLatencyMs: Long = 0L,
    val streamingDurationMs: Long = 0L,
    val generationDurationMs: Long = 0L,
    val totalRequests: Int = 0,
)
