package com.flexfly.ultron.android.action

/**
 * Lifecycle events broadcast via SharedFlow by Android Action Layer.
 */
sealed class AndroidActionEvent {
    data class ActionQueued(val requestId: String, val actionName: String) : AndroidActionEvent()
    data class PermissionRequested(val requestId: String, val permissions: List<String>) : AndroidActionEvent()
    data class PermissionGranted(val requestId: String) : AndroidActionEvent()
    data class PermissionDenied(val requestId: String, val permissions: List<String>) : AndroidActionEvent()
    data class ExecutionStarted(val requestId: String, val actionName: String) : AndroidActionEvent()
    data class ExecutionCompleted(val result: AndroidActionResult) : AndroidActionEvent()
    data class ExecutionFailed(val requestId: String, val reason: String) : AndroidActionEvent()
    data class ExecutionCancelled(val requestId: String) : AndroidActionEvent()
    data class ActionUnavailable(val requestId: String, val actionName: String) : AndroidActionEvent()
}
