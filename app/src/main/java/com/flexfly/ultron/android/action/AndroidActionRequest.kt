package com.flexfly.ultron.android.action

import java.util.UUID

/**
 * Execution request container for Android OS operations.
 */
data class AndroidActionRequest(
    val requestId: String = UUID.randomUUID().toString().take(8),
    val actionName: String,
    val parameters: Map<String, Any> = emptyMap(),
    val timeoutMs: Long = 10000L,
)

/**
 * Sealed result model returned by Android Action Execution Pipeline.
 */
sealed class AndroidActionResult {
    data class Success(
        val requestId: String,
        val actionName: String,
        val resultData: Map<String, Any> = emptyMap(),
    ) : AndroidActionResult()

    data class Failure(
        val requestId: String,
        val actionName: String,
        val errorMessage: String,
        val cause: Throwable? = null,
    ) : AndroidActionResult()

    data class Cancelled(
        val requestId: String,
        val actionName: String,
    ) : AndroidActionResult()

    data class PermissionRequired(
        val requestId: String,
        val actionName: String,
        val missingPermissions: List<String>,
    ) : AndroidActionResult()

    data class Unavailable(
        val requestId: String,
        val actionName: String,
        val reason: String,
    ) : AndroidActionResult()

    data class Timeout(
        val requestId: String,
        val actionName: String,
        val durationMs: Long,
    ) : AndroidActionResult()
}
