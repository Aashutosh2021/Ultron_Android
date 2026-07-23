package com.flexfly.ultron.ai.tool

/**
 * Deterministic 11-state machine representing tool execution progress.
 */
sealed class ToolExecutionState {
    data object Idle : ToolExecutionState()
    data class Queued(val requestId: String, val toolName: String) : ToolExecutionState()
    data class Validating(val requestId: String, val toolName: String) : ToolExecutionState()
    data class WaitingPermission(val requestId: String, val permissions: List<String>) : ToolExecutionState()
    data class Ready(val requestId: String, val toolName: String) : ToolExecutionState()
    data class Executing(val requestId: String, val toolName: String) : ToolExecutionState()
    data class Completed(val result: ToolResult) : ToolExecutionState()
    data class Cancelled(val requestId: String) : ToolExecutionState()
    data class Failed(val requestId: String, val message: String) : ToolExecutionState()
    data class Timeout(val requestId: String) : ToolExecutionState()
    data class Error(val message: String, val cause: Throwable? = null) : ToolExecutionState()
}
