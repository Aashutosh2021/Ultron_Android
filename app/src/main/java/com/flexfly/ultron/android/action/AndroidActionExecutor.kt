package com.flexfly.ultron.android.action

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asynchronous Coroutine OS Action Executor managing cancellation & timeouts safely off the main thread.
 */
class AndroidActionExecutor {

    companion object {
        private const val TAG = "AndroidActionExecutor"
    }

    suspend fun executeAction(action: AndroidAction, request: AndroidActionRequest): AndroidActionResult {
        return withContext(Dispatchers.Default) {
            Log.d(TAG, "⚡ Executing OS Action '${action.metadata().name}' for RequestID ${request.requestId}...")

            val result = withTimeoutOrNull(request.timeoutMs) {
                try {
                    action.execute(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing OS Action '${action.metadata().name}': ${e.message}", e)
                    AndroidActionResult.Failure(
                        requestId = request.requestId,
                        actionName = action.metadata().name,
                        errorMessage = e.message ?: "Unknown OS execution failure",
                        cause = e,
                    )
                }
            }

            result ?: AndroidActionResult.Timeout(
                requestId = request.requestId,
                actionName = action.metadata().name,
                durationMs = request.timeoutMs,
            )
        }
    }
}
