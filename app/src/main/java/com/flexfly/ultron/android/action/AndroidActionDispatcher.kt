package com.flexfly.ultron.android.action

import android.content.Context
import android.util.Log
import com.flexfly.ultron.ai.tool.ToolDispatcher
import com.flexfly.ultron.ai.tool.ToolExecutionEvent
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
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * Top-Level Dispatcher mapping Tool Execution events to concrete Android OS Actions.
 *
 * CRITICAL ARCHITECTURAL PRINCIPLE:
 *  - This layer executes validated OS operations requested by the Tool Framework.
 *  - Contains ZERO decision-making or brain thinking logic.
 */
class AndroidActionDispatcher(
    private val context: Context,
    val registry: AndroidActionRegistry = AndroidActionRegistry(),
    private val toolDispatcher: ToolDispatcher,
    val permissionGateway: AndroidPermissionGateway = AndroidPermissionGateway(context),
) {
    companion object {
        private const val TAG = "AndroidActionDispatcher"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pipeline = AndroidActionPipeline(permissionGateway)
    private var toolObservationJob: Job? = null

    private val _state = MutableStateFlow<AndroidActionState>(AndroidActionState.Idle)
    val state: StateFlow<AndroidActionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AndroidActionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AndroidActionEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow(AndroidActionMetrics())
    val metrics: StateFlow<AndroidActionMetrics> = _metrics.asStateFlow()

    init {
        populateDefaultActions()
    }

    private fun populateDefaultActions() {
        registry.register(MediaPlaybackAction(context))
        registry.register(NotificationScheduleAction(context))
        registry.register(LocationNavigationAction(context))
    }

    fun start() {
        if (toolObservationJob?.isActive == true) return

        toolObservationJob = scope.launch {
            Log.d(TAG, "🚀 AndroidActionDispatcher subscribing to ToolDispatcher.events")
            toolDispatcher.events.collect { toolEvent ->
                if (toolEvent is ToolExecutionEvent.ExecutionStarted) {
                    Log.d(TAG, "⚡ Tool ExecutionStarted event received for '${toolEvent.toolName}'")
                    dispatchActionRequest(AndroidActionRequest(actionName = mapToolToActionName(toolEvent.toolName)))
                }
            }
        }
    }

    private fun mapToolToActionName(toolName: String): String {
        return when (toolName.lowercase()) {
            "myplayer" -> "MediaPlaybackAction"
            "remindermanager" -> "NotificationScheduleAction"
            "navigationmanager" -> "LocationNavigationAction"
            else -> toolName
        }
    }

    fun dispatchActionRequest(request: AndroidActionRequest) {
        scope.launch {
            updateState(AndroidActionState.Queued(request.requestId, request.actionName))
            _events.emit(AndroidActionEvent.ActionQueued(request.requestId, request.actionName))

            val action = registry.findByName(request.actionName)
            if (action == null) {
                Log.w(TAG, "⚠️ Action '${request.actionName}' not found in AndroidActionRegistry")
                val result = AndroidActionResult.Unavailable(request.requestId, request.actionName, "Action not registered")
                updateState(AndroidActionState.Completed(result))
                _events.emit(AndroidActionEvent.ActionUnavailable(request.requestId, request.actionName))
                return@launch
            }

            updateState(AndroidActionState.Validating(request.requestId, request.actionName))

            var result: AndroidActionResult
            val duration = measureTimeMillis {
                result = pipeline.process(action, request) { event ->
                    handlePipelineEvent(event)
                }
            }

            when (result) {
                is AndroidActionResult.Success -> {
                    updateState(AndroidActionState.Completed(result))
                    _metrics.value = _metrics.value.copy(
                        lastActionName = request.actionName,
                        executionTimeMs = duration,
                        successCount = _metrics.value.successCount + 1,
                    )
                }
                is AndroidActionResult.Failure -> {
                    updateState(AndroidActionState.Failed(request.requestId, result.errorMessage))
                    _metrics.value = _metrics.value.copy(
                        lastActionName = request.actionName,
                        failureCount = _metrics.value.failureCount + 1,
                    )
                }
                is AndroidActionResult.PermissionRequired -> {
                    updateState(AndroidActionState.WaitingPermission(request.requestId, result.missingPermissions))
                    _metrics.value = _metrics.value.copy(
                        permissionRequestsCount = _metrics.value.permissionRequestsCount + 1,
                    )
                }
                is AndroidActionResult.Timeout -> {
                    updateState(AndroidActionState.Timeout(request.requestId))
                    _metrics.value = _metrics.value.copy(
                        timeoutCount = _metrics.value.timeoutCount + 1,
                    )
                }
                else -> {
                    updateState(AndroidActionState.Idle)
                }
            }
        }
    }

    private suspend fun handlePipelineEvent(event: AndroidActionEvent) {
        _events.emit(event)
        when (event) {
            is AndroidActionEvent.ExecutionStarted -> {
                updateState(AndroidActionState.Executing(event.requestId, event.actionName))
            }
            else -> {}
        }
    }

    private fun updateState(newState: AndroidActionState) {
        if (_state.value != newState) {
            Log.d(TAG, "🔄 [ACTION TRANSITION] ${_state.value.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _state.value = newState
        }
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping AndroidActionDispatcher")
        toolObservationJob?.cancel()
        toolObservationJob = null
        updateState(AndroidActionState.Idle)
    }

    fun release() {
        stop()
    }
}
