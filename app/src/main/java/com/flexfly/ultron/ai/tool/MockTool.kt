package com.flexfly.ultron.ai.tool

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Mock Media Player Tool for testing framework execution flow.
 */
class MyPlayerTool : Tool {
    private val meta = ToolMetadata("my_player", "MyPlayer", "Plays media playlists and tracks")

    override fun metadata(): ToolMetadata = meta
    override fun category(): ToolCategory = ToolCategory.Media
    override fun capabilities(): List<ToolCapability> = listOf(ToolCapability("audio_playback"))
    override fun requiredPermissions(): List<String> = emptyList()
    override fun isAvailable(): Boolean = true
    override fun canExecute(request: ToolRequest): Boolean = request.toolName.equals("MyPlayer", ignoreCase = true)

    override suspend fun execute(request: ToolRequest): ToolResult {
        Log.d("MyPlayerTool", "▶️ Playing media request: ${request.parameters}")
        delay(200L)
        return ToolResult.Success(
            requestId = request.requestId,
            toolName = meta.name,
            outputData = mapOf("status" to "playing", "media" to (request.parameters["target"] ?: "workout playlist")),
        )
    }

    override fun cancel() {}
    override fun cleanup() {}
}

/**
 * Mock Reminder Manager Tool for testing framework execution flow.
 */
class ReminderManagerTool : Tool {
    private val meta = ToolMetadata("reminder_manager", "ReminderManager", "Schedules reminders and timers")

    override fun metadata(): ToolMetadata = meta
    override fun category(): ToolCategory = ToolCategory.Automation
    override fun capabilities(): List<ToolCapability> = listOf(ToolCapability("scheduling"))
    override fun requiredPermissions(): List<String> = listOf("android.permission.POST_NOTIFICATIONS")
    override fun isAvailable(): Boolean = true
    override fun canExecute(request: ToolRequest): Boolean = request.toolName.equals("ReminderManager", ignoreCase = true)

    override suspend fun execute(request: ToolRequest): ToolResult {
        Log.d("ReminderManagerTool", "⏰ Setting reminder: ${request.parameters}")
        delay(150L)
        return ToolResult.Success(
            requestId = request.requestId,
            toolName = meta.name,
            outputData = mapOf("status" to "scheduled", "task" to (request.parameters["task"] ?: "reminder")),
        )
    }

    override fun cancel() {}
    override fun cleanup() {}
}

/**
 * Mock Navigation Manager Tool for testing framework execution flow.
 */
class NavigationManagerTool : Tool {
    private val meta = ToolMetadata("navigation_manager", "NavigationManager", "Provides navigation directions")

    override fun metadata(): ToolMetadata = meta
    override fun category(): ToolCategory = ToolCategory.Navigation
    override fun capabilities(): List<ToolCapability> = listOf(ToolCapability("location_directions"))
    override fun requiredPermissions(): List<String> = listOf("android.permission.ACCESS_FINE_LOCATION")
    override fun isAvailable(): Boolean = true
    override fun canExecute(request: ToolRequest): Boolean = request.toolName.equals("NavigationManager", ignoreCase = true)

    override suspend fun execute(request: ToolRequest): ToolResult {
        Log.d("NavigationManagerTool", "🗺️ Navigating to: ${request.parameters}")
        delay(300L)
        return ToolResult.Success(
            requestId = request.requestId,
            toolName = meta.name,
            outputData = mapOf("status" to "navigating", "destination" to (request.parameters["destination"] ?: "home")),
        )
    }

    override fun cancel() {}
    override fun cleanup() {}
}
