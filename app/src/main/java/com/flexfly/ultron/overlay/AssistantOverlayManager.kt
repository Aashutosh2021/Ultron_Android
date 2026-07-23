package com.flexfly.ultron.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flexfly.ultron.ai.conversation.ConversationEngine
import com.flexfly.ultron.ai.conversation.ConversationState
import com.flexfly.ultron.ui.EdgeGlowOverlay
import com.flexfly.ultron.voice.VadManager
import com.flexfly.ultron.voice.VadState
import com.flexfly.ultron.voice.session.VoiceSessionManager
import com.flexfly.ultron.voice.session.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Manages system-wide Assistant Overlay UI & Edge Lighting during active voice sessions.
 * Displays [EdgeGlowOverlay] using WindowManager with TYPE_APPLICATION_OVERLAY.
 */
class AssistantOverlayManager(
    private val context: Context,
    private val sessionManager: VoiceSessionManager,
    private val vadManager: VadManager,
    private val conversationEngine: ConversationEngine,
) : LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "AssistantOverlayManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun start() {
        if (observeJob?.isActive == true) return
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        observeJob = scope.launch {
            combine(
                sessionManager.sessionState,
                vadManager.state,
                conversationEngine.state
            ) { sessionState, vadState, convState ->
                Triple(sessionState, vadState, convState)
            }.collect { (sessionState, vadState, convState) ->
                handleStateChange(sessionState, vadState, convState)
            }
        }
    }

    private fun handleStateChange(
        sessionState: VoiceSessionState,
        vadState: VadState,
        convState: ConversationState
    ) {
        val isSessionActive = sessionState !is VoiceSessionState.Idle && sessionState !is VoiceSessionState.Ended
        val isConnected = convState is ConversationState.Connected || convState is ConversationState.StreamingInput || convState is ConversationState.StreamingOutput || convState is ConversationState.Generating

        if (isSessionActive || isConnected) {
            showOverlay(
                isUserSpeaking = vadState is VadState.Speaking || sessionState is VoiceSessionState.Listening,
                isUltronSpeaking = convState is ConversationState.StreamingOutput || sessionState is VoiceSessionState.Responding,
                isConnected = true,
                shouldDismiss = false
            )
        } else {
            hideOverlay()
        }
    }

    private fun showOverlay(
        isUserSpeaking: Boolean,
        isUltronSpeaking: Boolean,
        isConnected: Boolean,
        shouldDismiss: Boolean
    ) {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "⚠️ Overlay permission missing — cannot show EdgeGlowOverlay")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                if (composeView == null) {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    windowManager = wm

                    val view = ComposeView(context).apply {
                        setViewTreeLifecycleOwner(this@AssistantOverlayManager)
                        setViewTreeSavedStateRegistryOwner(this@AssistantOverlayManager)
                    }

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )

                    view.setContent {
                        EdgeGlowOverlay(
                            isUserSpeaking = isUserSpeaking,
                            isUltronSpeaking = isUltronSpeaking,
                            isConnected = isConnected,
                            shouldDismiss = shouldDismiss
                        )
                    }

                    wm.addView(view, params)
                    composeView = view
                    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                    Log.d(TAG, "✨ [ASSISTANT UI ACTIVATED] Edge Lighting & Overlay shown")
                } else {
                    composeView?.setContent {
                        EdgeGlowOverlay(
                            isUserSpeaking = isUserSpeaking,
                            isUltronSpeaking = isUltronSpeaking,
                            isConnected = isConnected,
                            shouldDismiss = shouldDismiss
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing assistant overlay: ${e.message}", e)
            }
        }
    }

    private fun hideOverlay() {
        if (composeView == null) return

        Handler(Looper.getMainLooper()).post {
            try {
                val wm = windowManager
                val view = composeView
                if (wm != null && view != null) {
                    wm.removeView(view)
                    Log.d(TAG, "🧹 [ASSISTANT UI DISMISSED] Edge Lighting & Overlay removed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding assistant overlay: ${e.message}", e)
            }
            composeView = null
            windowManager = null
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        hideOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
