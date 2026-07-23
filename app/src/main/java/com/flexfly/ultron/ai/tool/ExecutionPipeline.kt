package com.flexfly.ultron.ai.tool

import android.util.Log

/**
 * Multi-Stage Replaceable Tool Execution Pipeline.
 *
 * Pipeline Stages:
 *  Request ➔ Validation ➔ Permission Check ➔ Availability Check ➔ Execution ➔ Result & Cleanup
 */
class ExecutionPipeline(
    private val permissionManager: PermissionManager,
    private val executor: ToolExecutor = ToolExecutor(),
) {
    companion object {
        private const val TAG = "ExecutionPipeline"
    }

    suspend fun process(
        tool: Tool,
        request: ToolRequest,
        onEvent: suspend (ToolExecutionEvent) -> Unit,
    ): ToolResult {
        onEvent(ToolExecutionEvent.ToolQueued(request.requestId, request.toolName))

        // Stage 1: Validation
        if (!tool.canExecute(request)) {
            Log.w(TAG, "❌ [STAGE 1] Validation failed for tool '${request.toolName}'")
            onEvent(ToolExecutionEvent.ValidationFailed(request.requestId, "Validation failed"))
            return ToolResult.Failure(request.requestId, request.toolName, "Validation failed for request")
        }
        onEvent(ToolExecutionEvent.ValidationPassed(request.requestId))

        // Stage 2: Permission Check
        val requiredPerms = tool.requiredPermissions()
        if (requiredPerms.isNotEmpty()) {
            val missing = permissionManager.getMissingPermissions(requiredPerms)
            if (missing.isNotEmpty()) {
                onEvent(ToolExecutionEvent.PermissionRequired(request.requestId, missing))
                val granted = permissionManager.requestPermissions(request.requestId, requiredPerms)
                if (!granted) {
                    onEvent(ToolExecutionEvent.PermissionDenied(request.requestId, missing))
                    return ToolResult.PermissionRequired(request.requestId, request.toolName, missing)
                }
                onEvent(ToolExecutionEvent.PermissionGranted(request.requestId))
            }
        }

        // Stage 3: Availability Check
        if (!tool.isAvailable()) {
            Log.w(TAG, "⚠️ [STAGE 3] Tool '${request.toolName}' is unavailable")
            onEvent(ToolExecutionEvent.ToolUnavailable(request.requestId, request.toolName))
            return ToolResult.Unavailable(request.requestId, request.toolName, "Tool unavailable")
        }

        // Stage 4: Execution
        onEvent(ToolExecutionEvent.ExecutionStarted(request.requestId, request.toolName))
        val result = executor.executeTool(tool, request)

        // Stage 5: Cleanup
        tool.cleanup()

        when (result) {
            is ToolResult.Success -> onEvent(ToolExecutionEvent.ExecutionCompleted(result))
            is ToolResult.Failure -> onEvent(ToolExecutionEvent.ExecutionFailed(request.requestId, result.errorMessage))
            is ToolResult.Cancelled -> onEvent(ToolExecutionEvent.ExecutionCancelled(request.requestId))
            else -> {}
        }

        return result
    }
}
