package com.flexfly.ultron.ai.tool

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Permission Manager for Tool Execution.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE (unchanged):
 *  - Does NOT request Android permissions directly (a Service cannot). It CHECKS the
 *    real OS grant state and publishes a request event for a UI layer to satisfy.
 *
 * PRODUCTION HARDENING:
 *  - Grant state is read from the Android permission system via
 *    [ContextCompat.checkSelfPermission] when a [Context] is available — NOT from an
 *    in-process auto-grant set. Missing permissions are reported honestly (deny),
 *    never silently auto-granted. A UI layer observes [permissionRequests] and drives
 *    the real runtime-permission dialog; once the OS grants it, the next check passes.
 *  - The no-arg constructor is retained for backward compatibility (e.g. unit tests and
 *    the default in [ToolDispatcher]); without a Context it treats only explicitly
 *    grantable, non-Android capability strings as satisfiable and denies real
 *    `android.permission.*` requests rather than faking them.
 */
class PermissionManager(
    private val context: Context? = null,
) {

    companion object {
        private const val TAG = "PermissionManager"
    }

    private val _permissionRequests = MutableSharedFlow<List<String>>(extraBufferCapacity = 16)
    val permissionRequests: SharedFlow<List<String>> = _permissionRequests.asSharedFlow()

    // Non-Android capability grants (e.g. plugin capability flags). Real
    // android.permission.* strings are NEVER stored here — they are resolved live.
    private val grantedCapabilities = mutableSetOf<String>()

    fun grantPermission(permission: String) {
        grantedCapabilities.add(permission)
        Log.d(TAG, "🔑 Granted capability: $permission")
    }

    /** True only if EVERY required permission is actually held. */
    fun hasPermissions(required: List<String>): Boolean {
        if (required.isEmpty()) return true
        return required.all { isGranted(it) }
    }

    fun getMissingPermissions(required: List<String>): List<String> {
        return required.filter { !isGranted(it) }
    }

    /**
     * Resolves the real grant state of a single permission:
     *  - `android.permission.*` → live [ContextCompat.checkSelfPermission] (requires Context).
     *  - anything else → an explicitly granted non-Android capability flag.
     */
    private fun isGranted(permission: String): Boolean {
        if (permission.startsWith("android.permission.")) {
            val ctx = context ?: return false // No context → cannot verify → deny (fail closed).
            return ContextCompat.checkSelfPermission(ctx, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        return grantedCapabilities.contains(permission)
    }

    /**
     * Publishes a request for the still-missing permissions and returns whether they are
     * NOW satisfied. It does NOT grant them itself — a UI layer must drive the OS dialog.
     * Returns true only if nothing is missing (fail-closed), never an optimistic auto-grant.
     */
    suspend fun requestPermissions(requestId: String, required: List<String>): Boolean {
        val missing = getMissingPermissions(required)
        if (missing.isEmpty()) return true

        Log.w(TAG, "⚠️ ToolRequest $requestId requires missing permissions: $missing. Publishing request event for UI to satisfy.")
        _permissionRequests.emit(missing)
        // Do NOT auto-grant. Report the honest current state (still missing → denied).
        return false
    }
}
