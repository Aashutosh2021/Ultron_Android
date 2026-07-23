package com.flexfly.ultron.ai.tool

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-Safe Dynamic Tool Registry.
 *
 * Supports runtime registration, unregistration, category querying, capability querying,
 * and future dynamic Plugin SDK tool registration.
 */
class ToolRegistry {

    companion object {
        private const val TAG = "ToolRegistry"
    }

    private val toolsMap = ConcurrentHashMap<String, Tool>()

    fun register(tool: Tool) {
        val meta = tool.metadata()
        toolsMap[meta.name.lowercase()] = tool
        toolsMap[meta.toolId.lowercase()] = tool
        Log.d(TAG, "🛠️ Registered Tool: ${meta.name} (${meta.toolId}) [Category: ${tool.category().javaClass.simpleName}]")
    }

    fun unregister(identifier: String) {
        val removed = toolsMap.remove(identifier.lowercase())
        if (removed != null) {
            Log.d(TAG, "🧹 Unregistered Tool: $identifier")
        }
    }

    fun findByName(name: String): Tool? {
        return toolsMap[name.lowercase()]
    }

    fun findByCategory(category: ToolCategory): List<Tool> {
        return toolsMap.values.distinctBy { it.metadata().toolId }.filter { it.category() == category }
    }

    fun findByCapability(capabilityName: String): List<Tool> {
        return toolsMap.values.distinctBy { it.metadata().toolId }.filter { tool ->
            tool.capabilities().any { it.name.equals(capabilityName, ignoreCase = true) }
        }
    }

    fun getAllTools(): List<Tool> {
        return toolsMap.values.distinctBy { it.metadata().toolId }
    }

    fun clear() {
        toolsMap.clear()
        Log.d(TAG, "🧹 Cleared ToolRegistry")
    }
}
