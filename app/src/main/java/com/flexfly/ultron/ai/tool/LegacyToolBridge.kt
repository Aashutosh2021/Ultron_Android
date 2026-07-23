package com.flexfly.ultron.ai.tool

import android.content.Context
import com.flexfly.ultron.ai.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges the new Tool Framework to the app's existing production device-tool dispatcher
 * ([com.flexfly.ultron.ai.ToolHandler], ~60 real tools: flashlight, SMS, calls, WhatsApp,
 * maps, media, etc.).
 *
 * This is a DELEGATION bridge, not a rewrite — it satisfies the sprint directive "bridge
 * existing production tools instead of rewriting them." The new framework owns dispatch,
 * permissions, timeout and metrics; actual device work is performed by the proven legacy
 * handler. A single catch-all bridge means every legacy tool name is reachable from the
 * Brain's [ToolRequest] without duplicating 60 implementations.
 *
 * The legacy handler expects a bare tool string (optionally `name:arg`); this bridge
 * reconstructs that from the request name + a `"arg"` parameter when present.
 */
class LegacyToolBridge(
    private val appContext: Context,
) : Tool {

    private val meta = ToolMetadata(
        toolId = "legacy_bridge",
        name = "legacy_bridge",
        description = "Delegates to the production ToolHandler device-tool catalog",
    )

    override fun metadata(): ToolMetadata = meta
    override fun category(): ToolCategory = ToolCategory.System
    override fun capabilities(): List<ToolCapability> = listOf(ToolCapability("device_control"))

    // Permissions are enforced per-tool inside the legacy handler and by the OS; the bridge
    // itself declares none so the new PermissionManager doesn't double-gate. (Real device
    // permissions are still required and checked by the underlying Android APIs.)
    override fun requiredPermissions(): List<String> = emptyList()

    override fun isAvailable(): Boolean = true

    // Catch-all: the bridge can attempt any non-blank tool name. Specific new-framework
    // tools (MyPlayer, etc.) still match first in the registry; this is the fallback path.
    override fun canExecute(request: ToolRequest): Boolean = request.toolName.isNotBlank()

    override suspend fun execute(request: ToolRequest): ToolResult = withContext(Dispatchers.Default) {
        val arg = request.parameters["arg"] ?: request.parameters["target"] ?: request.parameters["query"]
        val toolString = if (arg.isNullOrBlank()) request.toolName else "${request.toolName}:$arg"
        try {
            val result = ToolHandler.handle(toolString, appContext)
            ToolResult.Success(
                requestId = request.requestId,
                toolName = request.toolName,
                outputData = mapOf("result" to result),
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                requestId = request.requestId,
                toolName = request.toolName,
                errorMessage = e.message ?: "Legacy tool execution failed",
                cause = e,
            )
        }
    }

    override fun cancel() {}
    override fun cleanup() {}
}
