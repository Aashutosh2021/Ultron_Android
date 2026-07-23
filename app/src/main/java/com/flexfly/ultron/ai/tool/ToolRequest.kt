package com.flexfly.ultron.ai.tool

import java.util.UUID

/**
 * Execution request model containing target tool name and parameters.
 */
data class ToolRequest(
    val requestId: String = UUID.randomUUID().toString().take(8),
    val toolName: String,
    val parameters: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 10000L,
)

/**
 * Sealed result model returned by Tool Execution Pipeline.
 */
sealed class ToolResult {
    data class Success(
        val requestId: String,
        val toolName: String,
        val outputData: Map<String, String> = emptyMap(),
    ) : ToolResult()

    data class Failure(
        val requestId: String,
        val toolName: String,
        val errorMessage: String,
        val cause: Throwable? = null,
    ) : ToolResult()

    data class Cancelled(
        val requestId: String,
        val toolName: String,
    ) : ToolResult()

    data class PermissionRequired(
        val requestId: String,
        val toolName: String,
        val missingPermissions: List<String>,
    ) : ToolResult()

    data class Unavailable(
        val requestId: String,
        val toolName: String,
        val reason: String,
    ) : ToolResult()

    data class Timeout(
        val requestId: String,
        val toolName: String,
        val durationMs: Long,
    ) : ToolResult()
}
