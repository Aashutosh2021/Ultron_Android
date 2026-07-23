package com.flexfly.ultron.ai.tool

/**
 * Provider-independent Tool Contract for ULTRON System Actions & Extensions.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 *  - Every tool implements this clean interface.
 *  - Contains NO Android-specific APIs inside the interface contract.
 */
interface Tool {
    fun metadata(): ToolMetadata
    fun category(): ToolCategory
    fun capabilities(): List<ToolCapability>
    fun requiredPermissions(): List<String>
    fun isAvailable(): Boolean
    fun canExecute(request: ToolRequest): Boolean
    suspend fun execute(request: ToolRequest): ToolResult
    fun cancel()
    fun cleanup()
}
