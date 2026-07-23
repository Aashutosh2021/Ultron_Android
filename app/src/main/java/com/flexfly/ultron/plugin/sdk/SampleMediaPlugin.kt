package com.flexfly.ultron.plugin.sdk

import com.flexfly.ultron.ai.tool.MyPlayerTool
import com.flexfly.ultron.ai.tool.Tool

/**
 * Reference First-Party Media Extension Plugin.
 */
class SampleMediaPlugin : Plugin {

    private val meta = PluginMetadata(
        pluginId = "plugin_media_player",
        name = "SampleMediaPlugin",
        version = "1.0.0",
        author = "ULTRON Ecosystem",
        description = "Provides Media Playback & Playlist Extensions",
    )

    private var isRunning = false

    override fun metadata(): PluginMetadata = meta

    override suspend fun initialize(context: PluginContext): Boolean {
        context.logInfo("SampleMediaPlugin", "Initializing Media Plugin...")
        return true
    }

    override suspend fun start(): Boolean {
        isRunning = true
        return true
    }

    override suspend fun stop(): Boolean {
        isRunning = false
        return true
    }

    override suspend fun destroy() {
        stop()
    }

    override fun supportedTools(): List<Tool> = listOf(MyPlayerTool())

    override fun supportedCapabilities(): List<PluginCapability> = listOf(PluginCapability.MusicPlayback)

    override fun configuration(): PluginConfiguration = PluginConfiguration()

    override fun healthStatus(): Float = if (isRunning) 1.0f else 0.0f
}

/**
 * Reference First-Party Smart Home Extension Plugin.
 */
class SampleSmartHomePlugin : Plugin {

    private val meta = PluginMetadata(
        pluginId = "plugin_smart_home",
        name = "SampleSmartHomePlugin",
        version = "1.0.0",
        author = "ULTRON Ecosystem",
        description = "Provides Smart Home Control Extensions",
    )

    private var isRunning = false

    override fun metadata(): PluginMetadata = meta

    override suspend fun initialize(context: PluginContext): Boolean {
        context.logInfo("SampleSmartHomePlugin", "Initializing Smart Home Plugin...")
        return true
    }

    override suspend fun start(): Boolean {
        isRunning = true
        return true
    }

    override suspend fun stop(): Boolean {
        isRunning = false
        return true
    }

    override suspend fun destroy() {
        stop()
    }

    override fun supportedTools(): List<Tool> = emptyList()

    override fun supportedCapabilities(): List<PluginCapability> = listOf(PluginCapability.SmartHome)

    override fun configuration(): PluginConfiguration = PluginConfiguration()

    override fun healthStatus(): Float = if (isRunning) 1.0f else 0.0f
}
