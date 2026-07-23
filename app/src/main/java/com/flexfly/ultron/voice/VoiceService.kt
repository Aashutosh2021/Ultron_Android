package com.flexfly.ultron.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.flexfly.ultron.MainActivity
import com.flexfly.ultron.ai.brain.BrainEngine
import com.flexfly.ultron.ai.brain.BrainMetrics
import com.flexfly.ultron.ai.brain.BrainState
import com.flexfly.ultron.ai.conversation.ConversationEngine
import com.flexfly.ultron.ai.conversation.ConversationMetrics
import com.flexfly.ultron.ai.conversation.ConversationState
import com.flexfly.ultron.ai.memory.MemoryEngine
import com.flexfly.ultron.ai.memory.MemoryMetrics
import com.flexfly.ultron.ai.memory.MemoryState
import com.flexfly.ultron.ai.tool.ToolDispatcher
import com.flexfly.ultron.ai.tool.ToolExecutionState
import com.flexfly.ultron.ai.tool.ToolMetrics
import com.flexfly.ultron.android.action.AndroidActionDispatcher
import com.flexfly.ultron.android.action.AndroidActionMetrics
import com.flexfly.ultron.android.action.AndroidActionState
import com.flexfly.ultron.plugin.sdk.PluginManager
import com.flexfly.ultron.plugin.sdk.PluginMetrics
import com.flexfly.ultron.plugin.sdk.PluginState
import com.flexfly.ultron.voice.session.SessionMetrics
import com.flexfly.ultron.voice.session.VoiceSessionEvent
import com.flexfly.ultron.voice.session.VoiceSessionManager
import com.flexfly.ultron.voice.session.VoiceSessionState
import com.flexfly.ultron.voice.speaker.SpeakerDebugInfo
import com.flexfly.ultron.voice.speaker.SpeakerVerificationManager
import com.flexfly.ultron.voice.speaker.VerificationResult
import com.flexfly.ultron.voice.wakeword.WakeWordDebugInfo
import com.flexfly.ultron.voice.wakeword.WakeWordManager
import com.flexfly.ultron.voice.wakeword.WakeWordState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Android Foreground Service hosting the continuous VAD, Wake Word, Speaker Verification, Voice Session Orchestration, Conversation Engine, Brain Engine, Tool Framework, Memory Engine, Android Action Layer & Plugin SDK pipeline.
 *
 * Runs low-power AudioRecord streaming with [VadManager], [WakeWordManager], [SpeakerVerificationManager], [VoiceSessionManager], [ConversationEngine], [BrainEngine], [ToolDispatcher], [MemoryEngine], [AndroidActionDispatcher], and [PluginManager],
 * publishing [VadState], [WakeWordState], [VerificationResult], [VoiceSessionState], [ConversationState], [BrainState], [ToolExecutionState], [MemoryState], [AndroidActionState], and [PluginState] updates via decoupled StateFlows.
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "ultron_vad_channel"

        const val ACTION_START_VAD = "com.flexfly.ultron.voice.ACTION_START_VAD"
        const val ACTION_STOP_VAD = "com.flexfly.ultron.voice.ACTION_STOP_VAD"

        @Volatile
        private var activeInstance: VoiceService? = null

        val ghostPointerCoordinates: androidx.compose.runtime.MutableState<android.graphics.PointF?> =
            androidx.compose.runtime.mutableStateOf(null)

        fun getActiveInstance(): VoiceService? = activeInstance

        fun startService(context: Context) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_START_VAD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_STOP_VAD
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        val service: VoiceService get() = this@VoiceService
    }

    private val binder = LocalBinder()
    private var vadManager: VadManager? = null
    private var wakeWordManager: com.flexfly.ultron.voice.wakeword.WakeWordManager? = null
    private var speakerManager: com.flexfly.ultron.voice.speaker.SpeakerVerificationManager? = null
    private var sessionManager: VoiceSessionManager? = null
    private var conversationEngine: ConversationEngine? = null
    private var brainEngine: BrainEngine? = null
    private var toolDispatcher: ToolDispatcher? = null
    private var memoryEngine: MemoryEngine? = null
    private var actionDispatcher: AndroidActionDispatcher? = null
    private var pluginManager: PluginManager? = null
    private var overlayManager: com.flexfly.ultron.overlay.AssistantOverlayManager? = null

    // Composition-root scope for cross-manager event wiring (turn-taking drivers).
    // Lives for the service lifetime; cancelled in onDestroy.
    private val wiringScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
    )
    private var turnWiringJobs = mutableListOf<kotlinx.coroutines.Job>()

    val vadState: StateFlow<VadState>? get() = vadManager?.state
    val debugInfo: StateFlow<VadDebugInfo>? get() = vadManager?.debugInfo

    val wakeWordState: StateFlow<com.flexfly.ultron.voice.wakeword.WakeWordState>? get() = wakeWordManager?.state
    val wakeWordDebugInfo: StateFlow<com.flexfly.ultron.voice.wakeword.WakeWordDebugInfo>? get() = wakeWordManager?.debugInfo

    val speakerState: StateFlow<com.flexfly.ultron.voice.speaker.VerificationResult>? get() = speakerManager?.verificationState
    val speakerDebugInfo: StateFlow<com.flexfly.ultron.voice.speaker.SpeakerDebugInfo>? get() = speakerManager?.debugInfo

    val sessionState: StateFlow<VoiceSessionState>? get() = sessionManager?.sessionState
    val sessionEvents: SharedFlow<VoiceSessionEvent>? get() = sessionManager?.events
    val sessionMetrics: StateFlow<SessionMetrics>? get() = sessionManager?.metrics

    val conversationState: StateFlow<ConversationState>? get() = conversationEngine?.state
    val conversationMetrics: StateFlow<ConversationMetrics>? get() = conversationEngine?.metrics

    val brainState: StateFlow<BrainState>? get() = brainEngine?.state
    val brainMetrics: StateFlow<BrainMetrics>? get() = brainEngine?.metrics

    val toolState: StateFlow<ToolExecutionState>? get() = toolDispatcher?.state
    val toolMetrics: StateFlow<ToolMetrics>? get() = toolDispatcher?.metrics

    val memoryState: StateFlow<MemoryState>? get() = memoryEngine?.state
    val memoryMetrics: StateFlow<MemoryMetrics>? get() = memoryEngine?.metrics

    val actionState: StateFlow<AndroidActionState>? get() = actionDispatcher?.state
    val actionMetrics: StateFlow<AndroidActionMetrics>? get() = actionDispatcher?.metrics

    val pluginState: StateFlow<PluginState>? get() = pluginManager?.state
    val pluginMetrics: StateFlow<PluginMetrics>? get() = pluginManager?.metrics

    // Legacy injection properties
    private var pendingCallNumber: String? = null
    private var pendingCallerName: String? = null
    private val pendingNotifications: MutableList<Triple<String, String, String>> = ArrayList()

    fun isGeminiActive(): Boolean {
        return conversationState?.value is ConversationState.Connected || conversationState?.value is ConversationState.StreamingInput
    }

    fun injectReminderMessage(message: String) {
        wiringScope.launch {
            conversationEngine?.injectMessage("[SYSTEM] $message. Boss ko clearly ek baar batao.")
        }
    }

    fun triggerManualSession() {
        wiringScope.launch {
            // speakerManager?.forceVerify()
        }
    }

    fun startReminderSession(message: String) {
        wiringScope.launch {
            // Ideally we'd wake up the pipeline here if asleep, but for now just inject
            conversationEngine?.injectMessage("[SYSTEM] $message. Boss ko clearly ek baar batao.")
        }
    }

    fun injectCallIntercept(callerName: String, number: String, message: String) {
        pendingCallerName = callerName
        pendingCallNumber = number
        wiringScope.launch {
            conversationEngine?.injectMessage("[CALL_INCOMING] $message Boss ka jawab suno — 'haan', 'uthao', 'receive' bole toh [TOOL:receive_call] use karo. 'nahi', 'mat uthao', 'reject' bole toh [TOOL:reject_call] use karo. Sirf ek baar poochho.")
        }
    }

    fun startCallInterceptSession(callerName: String, number: String, message: String) {
        pendingCallerName = callerName
        pendingCallNumber = number
        wiringScope.launch {
            conversationEngine?.injectMessage("[CALL_INCOMING] $message Boss ka jawab suno — 'haan' ya 'uthao' bole toh [TOOL:receive_call] use karo. 'nahi' ya 'reject' bole toh [TOOL:reject_call] use karo.")
        }
    }

    fun getPendingCallNumber(): String? = pendingCallNumber

    fun clearPendingCall() {
        pendingCallNumber = null
        pendingCallerName = null
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.d(TAG, "🟢 VoiceService created")
        createNotificationChannel()
        // Android 12+ requires startForeground() within 5 seconds of service start.
        // Call it here in onCreate (before onStartCommand) to guarantee we are always
        // within the limit even if the system delays onStartCommand delivery.
        startForegroundWithNotification()
        val session = VoiceSessionManager(applicationContext)
        sessionManager = session
        // Build the conversation engine with a REAL provider config: pull the Gemini key from
        // preferences and select the live provider when a key is present (mock otherwise, so
        // debug/dev without a key still runs). Keeps provider abstraction intact.
        val prefs = com.flexfly.ultron.data.AppPreferences(applicationContext)
        val geminiKey = prefs.googleApiKey
        val convConfig = com.flexfly.ultron.ai.conversation.ConversationConfig(
            providerType = if (geminiKey.isNotBlank())
                com.flexfly.ultron.ai.conversation.ProviderType.GEMINI_LIVE
            else
                com.flexfly.ultron.ai.conversation.ProviderType.MOCK,
            apiKey = geminiKey,
        )
        val conv = ConversationEngine(applicationContext, config = convConfig, sessionManager = session)
        conversationEngine = conv
        val brain = BrainEngine(applicationContext, sessionManager = session, conversationEngine = conv)
        brainEngine = brain
        val toolDisp = ToolDispatcher(applicationContext, brainEngine = brain)
        toolDispatcher = toolDisp
        memoryEngine = MemoryEngine(applicationContext, brainEngine = brain)
        actionDispatcher = AndroidActionDispatcher(applicationContext, toolDispatcher = toolDisp)
        pluginManager = PluginManager(applicationContext, toolDispatcher = toolDisp)

        vadManager = VadManager(applicationContext)
        wakeWordManager = WakeWordManager(applicationContext, vadManager = vadManager!!, sessionManager = session)
        speakerManager = SpeakerVerificationManager(applicationContext, wakeWordManager = wakeWordManager!!)
        overlayManager = com.flexfly.ultron.overlay.AssistantOverlayManager(
            context = applicationContext,
            sessionManager = session,
            vadManager = vadManager!!,
            conversationEngine = conv
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VAD -> {
                Log.d(TAG, "🛑 ACTION_STOP_VAD received")
                stopVadPipeline()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.d(TAG, "▶️ Starting VoiceService foreground service")
                startForegroundWithNotification()
                startVadPipeline()
            }
        }
        return START_STICKY
    }

    private fun startVadPipeline() {
        vadManager?.start()
        wakeWordManager?.start()
        speakerManager?.start()
        sessionManager?.start(speakerManager)
        conversationEngine?.start()
        brainEngine?.start()
        toolDispatcher?.start()
        memoryEngine?.start()
        actionDispatcher?.start()
        pluginManager?.start()
        overlayManager?.start()
        wireTurnTaking()
    }

    private fun wireTurnTaking() {
        val conv = conversationEngine ?: return

        turnWiringJobs += wiringScope.launch {
            conv.events.collect { event ->
                when (event) {
                    is com.flexfly.ultron.ai.conversation.ConversationEvent.PartialResponse -> {
                        if (event.data.isNotEmpty()) {
                            ensureAudioPlayer().playAudioChunk(
                                android.util.Base64.encodeToString(event.data, android.util.Base64.NO_WRAP)
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private var assistantAudioPlayer: com.flexfly.ultron.audio.AudioPlayerManager? = null
    private fun ensureAudioPlayer(): com.flexfly.ultron.audio.AudioPlayerManager {
        val existing = assistantAudioPlayer
        if (existing != null) return existing
        val p = com.flexfly.ultron.audio.AudioPlayerManager()
        p.initialize()
        assistantAudioPlayer = p
        return p
    }

    private fun stopVadPipeline() {
        turnWiringJobs.forEach { it.cancel() }
        turnWiringJobs.clear()
        overlayManager?.stop()
        assistantAudioPlayer?.release()
        assistantAudioPlayer = null
        pluginManager?.stop()
        actionDispatcher?.stop()
        memoryEngine?.stop()
        toolDispatcher?.stop()
        brainEngine?.stop()
        sessionManager?.stop()
        vadManager?.stop()
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service: ${e.message}", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ULTRON Voice & Wake Word Engine")
            .setContentText("VAD + openWakeWord active (Low Power)...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ULTRON VAD Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Runs background continuous voice activity detection and wake word engine"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔴 VoiceService destroyed")
        turnWiringJobs.forEach { it.cancel() }
        turnWiringJobs.clear()
        wiringScope.cancel()
        overlayManager?.stop()
        overlayManager = null
        pluginManager?.release()
        pluginManager = null
        actionDispatcher?.release()
        actionDispatcher = null
        memoryEngine?.release()
        memoryEngine = null
        toolDispatcher?.release()
        toolDispatcher = null
        brainEngine?.release()
        brainEngine = null
        conversationEngine?.release()
        conversationEngine = null
        sessionManager?.release()
        sessionManager = null
        vadManager?.release()
        vadManager = null
        wakeWordManager?.release()
        wakeWordManager = null
        speakerManager?.release()
        speakerManager = null
        if (activeInstance == this) {
            activeInstance = null
        }
    }
}
