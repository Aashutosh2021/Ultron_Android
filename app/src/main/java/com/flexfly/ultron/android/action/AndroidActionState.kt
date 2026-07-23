package com.flexfly.ultron.android.action

/**
 * Deterministic 11-state machine representing Android OS action execution progress.
 */
sealed class AndroidActionState {
    data object Idle : AndroidActionState()
    data class Queued(val requestId: String, val actionName: String) : AndroidActionState()
    data class Validating(val requestId: String, val actionName: String) : AndroidActionState()
    data class WaitingPermission(val requestId: String, val permissions: List<String>) : AndroidActionState()
    data class Executing(val requestId: String, val actionName: String) : AndroidActionState()
    data class WaitingResult(val requestId: String, val actionName: String) : AndroidActionState()
    data class Completed(val result: AndroidActionResult) : AndroidActionState()
    data class Cancelled(val requestId: String) : AndroidActionState()
    data class Failed(val requestId: String, val message: String) : AndroidActionState()
    data class Timeout(val requestId: String) : AndroidActionState()
    data class Error(val message: String, val cause: Throwable? = null) : AndroidActionState()
}
