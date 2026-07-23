package com.flexfly.ultron.ai.tool

import android.content.Context
import android.util.Log
import com.flexfly.ultron.ai.brain.BrainEngine
import com.flexfly.ultron.ai.brain.BrainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * Top-Level Tool Dispatcher subscribing to Brain Engine tool requests.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 *  - Contains NO business logic and NO intent classification logic.
 *  - Receives declarative [ToolRequest] models from [BrainEngine] and routes them through [ExecutionPipeline].
 */
class ToolDispatcher(
    private val context: Context,
    val registry: ToolRegistry = ToolRegistry(),
    private val brainEngine: BrainEngine,
    val permissionManager: PermissionManager = PermissionManager(context),
) {
    companion object {
        private const val TAG = "ToolDispatcher"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pipeline = ExecutionPipeline(permissionManager)
    private var brainObservationJob: Job? = null

    private val _state = MutableStateFlow<ToolExecutionState>(ToolExecutionState.Idle)
    val state: StateFlow<ToolExecutionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ToolExecutionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ToolExecutionEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow(ToolMetrics())
    val metrics: StateFlow<ToolMetrics> = _metrics.asStateFlow()

    init {
        // Populate built-in tools AND the legacy device-tool bridge, connecting the new
        // framework to the app's real ~60-tool ToolHandler.
        ToolFactory.populateDefaultTools(registry, context)
    }

    fun start() {
        if (brainObservationJob?.isActive == true) return

        brainObservationJob = scope.launch {
            Log.d(TAG, "🚀 ToolDispatcher subscribing to BrainEngine.events")
            brainEngine.events.collect { brainEvent ->
                if (brainEvent is BrainEvent.ToolRequested) {
                    Log.d(TAG, "⚡ ToolRequested event received for '${brainEvent.toolName}'")
                    dispatchToolRequest(ToolRequest(toolName = brainEvent.toolName, parameters = brainEvent.arguments))
                }
            }
        }
    }

    fun dispatchToolRequest(request: ToolRequest) {
        scope.launch {
            updateState(ToolExecutionState.Queued(request.requestId, request.toolName))
            _events.emit(ToolExecutionEvent.ToolQueued(request.requestId, request.toolName))

            val tool = registry.findByName(request.toolName)
                ?: registry.findByName("legacy_bridge") // Fallback: route unknown names to the device bridge.
            if (tool == null) {
                Log.w(TAG, "⚠️ Tool '${request.toolName}' not found in ToolRegistry")
                val result = ToolResult.Unavailable(request.requestId, request.toolName, "Tool not registered")
                updateState(ToolExecutionState.Completed(result))
                _events.emit(ToolExecutionEvent.ToolUnavailable(request.requestId, request.toolName))
                return@launch
            }

            updateState(ToolExecutionState.Validating(request.requestId, request.toolName))

            var result: ToolResult
            val duration = measureTimeMillis {
                result = pipeline.process(tool, request) { event ->
                    handlePipelineEvent(event)
                }
            }

            when (result) {
                is ToolResult.Success -> {
                    updateState(ToolExecutionState.Completed(result))
                    _metrics.update {
                        it.copy(
                            lastToolName = request.toolName,
                            executionTimeMs = duration,
                            successCount = it.successCount + 1,
                        )
                    }
                }
                is ToolResult.Failure -> {
                    updateState(ToolExecutionState.Failed(request.requestId, result.errorMessage))
                    _metrics.update {
                        it.copy(
                            lastToolName = request.toolName,
                            failureCount = it.failureCount + 1,
                        )
                    }
                }
                is ToolResult.PermissionRequired -> {
                    updateState(ToolExecutionState.WaitingPermission(request.requestId, result.missingPermissions))
                    _metrics.update { it.copy(permissionRequestsCount = it.permissionRequestsCount + 1) }
                }
                is ToolResult.Timeout -> {
                    updateState(ToolExecutionState.Timeout(request.requestId))
                    _metrics.update { it.copy(timeoutCount = it.timeoutCount + 1) }
                }
                else -> {
                    updateState(ToolExecutionState.Idle)
                }
            }
        }
    }

    private suspend fun handlePipelineEvent(event: ToolExecutionEvent) {
        _events.emit(event)
        when (event) {
            is ToolExecutionEvent.ExecutionStarted -> {
                updateState(ToolExecutionState.Executing(event.requestId, event.toolName))
            }
            else -> {}
        }
    }

    private fun updateState(newState: ToolExecutionState) {
        if (_state.value != newState) {
            Log.d(TAG, "🔄 [TOOL TRANSITION] ${_state.value.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _state.value = newState
        }
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping ToolDispatcher")
        brainObservationJob?.cancel()
        brainObservationJob = null
        updateState(ToolExecutionState.Idle)
    }

    fun release() {
        stop()
    }
}
