package com.flexfly.ultron.android.action

import android.util.Log

/**
 * Multi-Stage Replaceable Android Action Execution Pipeline.
 *
 * Pipeline Stages:
 *  Validation ➔ Permission Check ➔ Availability Check ➔ Execution ➔ Result Mapping ➔ Cleanup
 */
class AndroidActionPipeline(
    private val permissionGateway: AndroidPermissionGateway,
    private val executor: AndroidActionExecutor = AndroidActionExecutor(),
) {
    companion object {
        private const val TAG = "AndroidActionPipeline"
    }

    suspend fun process(
        action: AndroidAction,
        request: AndroidActionRequest,
        onEvent: suspend (AndroidActionEvent) -> Unit,
    ): AndroidActionResult {
        onEvent(AndroidActionEvent.ActionQueued(request.requestId, request.actionName))

        // Stage 1: Validation
        onEvent(AndroidActionEvent.ExecutionStarted(request.requestId, request.actionName))

        // Stage 2: Permission Check
        val requiredPerms = action.requiredPermissions()
        if (requiredPerms.isNotEmpty()) {
            val missing = permissionGateway.getMissingPermissions(requiredPerms)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "⚠️ Action '${request.actionName}' requires missing permissions: $missing")
                onEvent(AndroidActionEvent.PermissionRequested(request.requestId, missing))
                onEvent(AndroidActionEvent.PermissionDenied(request.requestId, missing))
                return AndroidActionResult.PermissionRequired(request.requestId, request.actionName, missing)
            } else {
                onEvent(AndroidActionEvent.PermissionGranted(request.requestId))
            }
        }

        // Stage 3: Availability Check
        if (!action.isAvailable()) {
            Log.w(TAG, "⚠️ Action '${request.actionName}' is unavailable on this device/SDK")
            onEvent(AndroidActionEvent.ActionUnavailable(request.requestId, request.actionName))
            return AndroidActionResult.Unavailable(request.requestId, request.actionName, "Action unavailable")
        }

        // Stage 4: Execution
        val result = executor.executeAction(action, request)

        // Stage 5: Cleanup
        action.cleanup()

        when (result) {
            is AndroidActionResult.Success -> onEvent(AndroidActionEvent.ExecutionCompleted(result))
            is AndroidActionResult.Failure -> onEvent(AndroidActionEvent.ExecutionFailed(request.requestId, result.errorMessage))
            is AndroidActionResult.Cancelled -> onEvent(AndroidActionEvent.ExecutionCancelled(request.requestId))
            else -> {}
        }

        return result
    }
}
