package com.flexfly.ultron.plugin.sdk

import com.flexfly.ultron.ai.tool.Tool

/**
 * Provider-Independent Plugin Contract for ULTRON System Extensions.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 *  - Third-party and first-party modules implement this interface.
 *  - Contains NO Android-specific APIs in contract declarations.
 */
interface Plugin {
    fun metadata(): PluginMetadata
    suspend fun initialize(context: PluginContext): Boolean
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
    suspend fun destroy()
    fun supportedTools(): List<Tool>
    fun supportedCapabilities(): List<PluginCapability>
    fun configuration(): PluginConfiguration
    fun healthStatus(): Float
}
