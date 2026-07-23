package com.flexfly.ultron.plugin.sdk

import android.content.Context
import android.util.Log
import com.flexfly.ultron.ai.tool.ToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * Top-Level Plugin Manager orchestrating discovery, lifecycle, tool registration, and crash recovery.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 *  - Plugins NEVER communicate directly with Brain Engine or Core internals.
 *  - Discovered tools are dynamically registered into [ToolDispatcher.registry].
 */
class PluginManager(
    private val context: Context,
    val registry: PluginRegistry = PluginRegistry(),
    private val toolDispatcher: ToolDispatcher,
    private val loader: PluginLoader = PluginLoader(),
) {
    companion object {
        private const val TAG = "PluginManager"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Per-plugin fault tracking — a plugin that throws is quarantined and never retried
    // within the session, so a single bad plugin cannot repeatedly disrupt the core.
    private val faultedPlugins = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private val _state = MutableStateFlow<PluginState>(PluginState.Idle)
    val state: StateFlow<PluginState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PluginEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PluginEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow(PluginMetrics())
    val metrics: StateFlow<PluginMetrics> = _metrics.asStateFlow()

    /**
     * Runs a plugin operation inside a hard exception boundary. Any Throwable (including
     * Errors from misbehaving plugin code) is caught, the plugin is quarantined, and a
     * fault event is emitted — the exception NEVER propagates into the manager's scope.
     * This is the guarantee that a plugin crash cannot crash the Core.
     */
    private inline fun <T> safePluginCall(pluginId: String, op: String, block: () -> T): T? {
        if (faultedPlugins.contains(pluginId)) return null
        return try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "🧨 Plugin '$pluginId' threw during '$op' — quarantining: ${t.message}", t)
            faultedPlugins.add(pluginId)
            scope.launch { _events.emit(PluginEvent.PluginCrashed(pluginId, "Crash in $op: ${t.message}")) }
            null
        }
    }

    fun start() {
        scope.launch {
            Log.d(TAG, "🚀 Starting PluginManager & Discovering Plugins...")
            updateState(PluginState.Discovering)

            val defaultPlugins = listOf(
                SampleMediaPlugin(),
                SampleSmartHomePlugin(),
            )

            var totalTools = 0
            val duration = measureTimeMillis {
                for (plugin in defaultPlugins) {
                    val meta = safePluginCall("unknown", "metadata") { plugin.metadata() } ?: continue
                    _events.emit(PluginEvent.PluginDiscovered(meta.pluginId))
                    updateState(PluginState.Loading(meta.pluginId))

                    val loaded = loader.loadPlugin(plugin)
                    if (loaded) {
                        registry.register(plugin)
                        _events.emit(PluginEvent.PluginLoaded(meta.pluginId))
                        _events.emit(PluginEvent.PluginStarted(meta.pluginId))

                        // Register plugin tools into ToolRegistry — inside the exception boundary,
                        // so a plugin that throws while enumerating tools is quarantined, not fatal.
                        val tools = safePluginCall(meta.pluginId, "supportedTools") { plugin.supportedTools() }
                            ?: emptyList()
                        for (t in tools) {
                            toolDispatcher.registry.register(t)
                            totalTools++
                        }
                    } else {
                        updateState(PluginState.Failed(meta.pluginId, "Failed loading plugin"))
                    }
                }
            }

            val activeCount = registry.getAllPlugins().size
            _metrics.value = _metrics.value.copy(
                totalPluginsCount = activeCount,
                activePluginsCount = activeCount,
                totalExposedToolsCount = totalTools,
                loadTimeMs = duration,
                averageHealthScore = computeAverageHealth(),
            )

            updateState(PluginState.Running(activeCount))
            Log.d(TAG, "⚡ PluginManager active with $activeCount plugins exposing $totalTools tools (Load time: ${duration}ms)")
        }
    }

    /** Averages each plugin's self-reported health, treating faulted/throwing plugins as 0. */
    private fun computeAverageHealth(): Float {
        val plugins = registry.getAllPlugins()
        if (plugins.isEmpty()) return 1.0f
        val sum = plugins.sumOf { p ->
            val id = safePluginCall("unknown", "metadata") { p.metadata().pluginId } ?: return@sumOf 0.0
            if (faultedPlugins.contains(id)) 0.0
            else (safePluginCall(id, "healthStatus") { p.healthStatus() } ?: 0.0f).toDouble()
        }
        return (sum / plugins.size).toFloat()
    }

    private fun updateState(newState: PluginState) {
        if (_state.value != newState) {
            Log.d(TAG, "🔄 [PLUGIN TRANSITION] ${_state.value.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _state.value = newState
        }
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping PluginManager")
        scope.launch {
            for (plugin in registry.getAllPlugins()) {
                val id = safePluginCall("unknown", "metadata") { plugin.metadata().pluginId } ?: continue
                // Each stop() is inside the exception boundary — a plugin that throws on
                // shutdown is quarantined, never crashing the manager's teardown.
                safePluginCall(id, "stop") { plugin.stop() }
                _events.emit(PluginEvent.PluginStopped(id))
            }
            updateState(PluginState.Idle)
        }
    }

    fun release() {
        stop()
    }
}
