package com.flexfly.ultron.ai.tool

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asynchronous Coroutine Tool Executor managing cancellation & timeouts off the main thread.
 */
class ToolExecutor {

    companion object {
        private const val TAG = "ToolExecutor"
    }

    suspend fun executeTool(tool: Tool, request: ToolRequest): ToolResult {
        return withContext(Dispatchers.Default) {
            Log.d(TAG, "⚡ Executing tool '${tool.metadata().name}' for RequestID ${request.requestId} (Timeout: ${request.timeoutMs}ms)...")

            val result = withTimeoutOrNull(request.timeoutMs) {
                try {
                    tool.execute(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing tool '${tool.metadata().name}': ${e.message}", e)
                    ToolResult.Failure(
                        requestId = request.requestId,
                        toolName = tool.metadata().name,
                        errorMessage = e.message ?: "Unknown execution failure",
                        cause = e,
                    )
                }
            }

            result ?: ToolResult.Timeout(
                requestId = request.requestId,
                toolName = tool.metadata().name,
                durationMs = request.timeoutMs,
            )
        }
    }
}
