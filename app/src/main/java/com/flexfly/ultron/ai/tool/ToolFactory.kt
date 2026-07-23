package com.flexfly.ultron.ai.tool

import android.content.Context

/**
 * Factory instantiating and populating core tools into the [ToolRegistry].
 */
object ToolFactory {

    /** Backward-compatible: registers only the framework's built-in tools (no device bridge). */
    fun populateDefaultTools(registry: ToolRegistry) {
        registry.register(MyPlayerTool())
        registry.register(ReminderManagerTool())
        registry.register(NavigationManagerTool())
    }

    /**
     * Production population: built-in tools PLUS the [LegacyToolBridge], which connects the
     * new framework to the app's existing ~60-tool device dispatcher. Requires a [Context].
     */
    fun populateDefaultTools(registry: ToolRegistry, context: Context) {
        populateDefaultTools(registry)
        registry.register(LegacyToolBridge(context.applicationContext))
    }
}
