package com.flexfly.ultron.voice.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flexfly.ultron.voice.VadDebugInfo
import com.flexfly.ultron.voice.VadState
import com.flexfly.ultron.service.WakeWordService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.flexfly.ultron.ai.brain.BrainMetrics
import com.flexfly.ultron.ai.brain.BrainState
import com.flexfly.ultron.ai.conversation.ConversationMetrics
import com.flexfly.ultron.ai.conversation.ConversationState
import com.flexfly.ultron.ai.memory.MemoryMetrics
import com.flexfly.ultron.ai.memory.MemoryState
import com.flexfly.ultron.ai.tool.ToolExecutionState
import com.flexfly.ultron.ai.tool.ToolMetrics
import com.flexfly.ultron.android.action.AndroidActionMetrics
import com.flexfly.ultron.android.action.AndroidActionState
import com.flexfly.ultron.plugin.sdk.PluginMetrics
import com.flexfly.ultron.plugin.sdk.PluginState
import com.flexfly.ultron.voice.session.SessionMetrics
import com.flexfly.ultron.voice.session.VoiceSessionState
import com.flexfly.ultron.voice.speaker.SpeakerDebugInfo
import com.flexfly.ultron.voice.speaker.VerificationResult
import com.flexfly.ultron.voice.wakeword.WakeWordDebugInfo
import com.flexfly.ultron.voice.wakeword.WakeWordState

/**
 * ViewModel serving the VAD, openWakeWord, Speaker Verification, Voice Session, Conversation Engine, Brain Engine, Tool Framework, Memory Engine, Android Action Layer & Plugin SDK Debug Screen.
 *
 * Subscribes to [VoiceService] StateFlows to provide real-time monitoring
 * without coupling the UI to audio thread operations.
 */
class VadDebugViewModel(application: Application) : AndroidViewModel(application) {

    private val _debugInfo = MutableStateFlow(VadDebugInfo())
    val debugInfo: StateFlow<VadDebugInfo> = _debugInfo.asStateFlow()

    private val _wakeDebugInfo = MutableStateFlow(WakeWordDebugInfo())
    val wakeDebugInfo: StateFlow<WakeWordDebugInfo> = _wakeDebugInfo.asStateFlow()

    private val _speakerDebugInfo = MutableStateFlow(SpeakerDebugInfo())
    val speakerDebugInfo: StateFlow<SpeakerDebugInfo> = _speakerDebugInfo.asStateFlow()

    private val _sessionState = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    val sessionState: StateFlow<VoiceSessionState> = _sessionState.asStateFlow()

    private val _sessionMetrics = MutableStateFlow(SessionMetrics())
    val sessionMetrics: StateFlow<SessionMetrics> = _sessionMetrics.asStateFlow()

    private val _conversationState = MutableStateFlow<ConversationState>(ConversationState.Disconnected)
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    private val _conversationMetrics = MutableStateFlow(ConversationMetrics())
    val conversationMetrics: StateFlow<ConversationMetrics> = _conversationMetrics.asStateFlow()

    private val _brainState = MutableStateFlow<BrainState>(BrainState.Idle)
    val brainState: StateFlow<BrainState> = _brainState.asStateFlow()

    private val _brainMetrics = MutableStateFlow(BrainMetrics())
    val brainMetrics: StateFlow<BrainMetrics> = _brainMetrics.asStateFlow()

    private val _toolState = MutableStateFlow<ToolExecutionState>(ToolExecutionState.Idle)
    val toolState: StateFlow<ToolExecutionState> = _toolState.asStateFlow()

    private val _toolMetrics = MutableStateFlow(ToolMetrics())
    val toolMetrics: StateFlow<ToolMetrics> = _toolMetrics.asStateFlow()

    private val _memoryState = MutableStateFlow<MemoryState>(MemoryState.Idle)
    val memoryState: StateFlow<MemoryState> = _memoryState.asStateFlow()

    private val _memoryMetrics = MutableStateFlow(MemoryMetrics())
    val memoryMetrics: StateFlow<MemoryMetrics> = _memoryMetrics.asStateFlow()

    private val _actionState = MutableStateFlow<AndroidActionState>(AndroidActionState.Idle)
    val actionState: StateFlow<AndroidActionState> = _actionState.asStateFlow()

    private val _actionMetrics = MutableStateFlow(AndroidActionMetrics())
    val actionMetrics: StateFlow<AndroidActionMetrics> = _actionMetrics.asStateFlow()

    private val _pluginState = MutableStateFlow<PluginState>(PluginState.Idle)
    val pluginState: StateFlow<PluginState> = _pluginState.asStateFlow()

    private val _pluginMetrics = MutableStateFlow(PluginMetrics())
    val pluginMetrics: StateFlow<PluginMetrics> = _pluginMetrics.asStateFlow()

    init {
        observeServiceState()
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            while (true) {
                val service = WakeWordService.getActiveInstance()
                if (service != null) {
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    launch {
                        {}
                    }
                    break
                } else {
                    _debugInfo.value = _debugInfo.value.copy(
                        state = VadState.Idle,
                        micStatus = "SERVICE_STOPPED",
                    )
                    _wakeDebugInfo.value = _wakeDebugInfo.value.copy(
                        state = WakeWordState.Idle,
                    )
                    _speakerDebugInfo.value = _speakerDebugInfo.value.copy(
                        status = VerificationResult.NotEnrolled,
                    )
                    _sessionState.value = VoiceSessionState.Idle
                    _conversationState.value = ConversationState.Disconnected
                    _brainState.value = BrainState.Idle
                    _toolState.value = ToolExecutionState.Idle
                    _memoryState.value = MemoryState.Idle
                    _actionState.value = AndroidActionState.Idle
                    _pluginState.value = PluginState.Idle
                }
                delay(500L)
            }
        }
    }

    fun startService(context: Context) {
        WakeWordService.startService(context)
    }

    fun stopService(context: Context) {
        WakeWordService.stopService(context)
    }

    fun clearSpeakerProfile(context: Context) {
        val service = WakeWordService.getActiveInstance()
        // Access SpeakerVerificationManager via service if available
        val method = service?.javaClass?.getDeclaredField("speakerManager")?.apply { isAccessible = true }
        val manager = method?.get(service) as? com.flexfly.ultron.voice.speaker.SpeakerVerificationManager
        manager?.clearEnrollment()
    }
}
