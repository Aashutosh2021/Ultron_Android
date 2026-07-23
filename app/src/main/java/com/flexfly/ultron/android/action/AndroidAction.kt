package com.flexfly.ultron.android.action

/**
 * Interface contract for concrete Android OS Framework Actions.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 *  - This is the ONLY interface allowed to perform Android Framework calls in concrete implementations.
 *  - Version-independent and lifecycle-safe.
 */
interface AndroidAction {
    fun metadata(): AndroidActionMetadata
    fun category(): AndroidActionCategory
    fun requiredPermissions(): List<String>
    fun supportedCapabilities(): List<AndroidActionCapability>
    fun isAvailable(): Boolean
    suspend fun execute(request: AndroidActionRequest): AndroidActionResult
    fun cancel()
    fun cleanup()
}
