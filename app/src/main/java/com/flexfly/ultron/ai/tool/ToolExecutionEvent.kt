package com.flexfly.ultron.ai.tool

/**
 * High-level tool lifecycle events broadcast via SharedFlow.
 */
sealed class ToolExecutionEvent {
    data class ToolQueued(val requestId: String, val toolName: String) : ToolExecutionEvent()

    data class ValidationPassed(val requestId: String) : ToolExecutionEvent()

    data class ValidationFailed(val requestId: String, val reason: String) : ToolExecutionEvent()

    data class PermissionRequired(val requestId: String, val permissions: List<String>) : ToolExecutionEvent()

    data class PermissionGranted(val requestId: String) : ToolExecutionEvent()

    data class PermissionDenied(val requestId: String, val permissions: List<String>) : ToolExecutionEvent()

    data class ExecutionStarted(val requestId: String, val toolName: String) : ToolExecutionEvent()

    data class ExecutionCompleted(val result: ToolResult) : ToolExecutionEvent()

    data class ExecutionFailed(val requestId: String, val reason: String) : ToolExecutionEvent()

    data class ExecutionCancelled(val requestId: String) : ToolExecutionEvent()

    data class ToolUnavailable(val requestId: String, val toolName: String) : ToolExecutionEvent()
}
