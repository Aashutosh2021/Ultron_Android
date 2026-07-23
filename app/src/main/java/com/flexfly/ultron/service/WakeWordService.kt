package com.flexfly.ultron.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flexfly.ultron.ui.EdgeGlowOverlay
import com.flexfly.ultron.ui.GhostPointerRipple
import com.flexfly.ultron.ai.GeminiLiveSession
import com.flexfly.ultron.ai.TranslationManager
import com.flexfly.ultron.audio.AudioPlayerManager
import com.flexfly.ultron.audio.AudioRecorderManager
import com.flexfly.ultron.comms.CallAudioManager
import com.flexfly.ultron.comms.CallReceiver
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.data.DatabaseHelper
import com.flexfly.ultron.device.BatteryMonitor
import com.flexfly.ultron.receiver.ChargingReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import com.flexfly.ultron.voice.AudioFrame
import com.flexfly.ultron.voice.VadManager
import com.flexfly.ultron.voice.VadState
import com.flexfly.ultron.voice.wakeword.WakeWordManager
import com.flexfly.ultron.voice.wakeword.WakeWordState
import com.flexfly.ultron.voice.speaker.SpeakerVerificationManager
import com.flexfly.ultron.voice.speaker.VerificationResult
import java.util.Locale

class WakeWordService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private var audioPlayer: AudioPlayerManager? = null
    private var batteryMonitor: BatteryMonitor? = null
    private var callAudioManager: CallAudioManager? = null
    private lateinit var db: DatabaseHelper
    private var geminiAudioRecorder: AudioRecorderManager? = null
    private var geminiSession: GeminiLiveSession? = null
    private var isUltronCurrentlySpeaking = false
    private var isToolExecuting = false
    private var vadManager: VadManager? = null
    private var wakeWordManager: WakeWordManager? = null
    private var speakerManager: SpeakerVerificationManager? = null
    private enum class Stage { IDLE, VOICE_DETECTED, WAKE_WORD_DETECTED, GEMINI_ACTIVE }
    @Volatile private var stage = Stage.IDLE
    private val speechFrames = mutableListOf<AudioFrame>()
    private val recentFrames = java.util.LinkedList<AudioFrame>()
    private var lastVadState: VadState = VadState.Idle
    private var geminiCooldownFrames = 0
    private var isPipelineListening = false
    private var pipelineJob: Job? = null
    private var lastActivityTime = 0L
    private var notifAudioPlayer: AudioPlayerManager? = null
    private var notifGeminiSession: GeminiLiveSession? = null
    private var notifSessionActive = false
    private var overlayView: View? = null
    private var pendingCallNumber: String? = null
    private var pendingCallerName: String? = null
    private lateinit var prefs: AppPreferences
    private var silenceJob: Job? = null
    // Guarantees the ring volume is restored after a call announcement even if the
    // announce session never fires its "done speaking" event (bad key, no network,
    // dropped session) ΓÇö otherwise the phone stays silenced/vibrate for the whole call.
    private var ringRestoreJob: Job? = null
    private var wakeWordJustDetected = false
    private var windowManager: WindowManager? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var micUnmuteJob: Job? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val isUserSpeakingState: MutableState<Boolean> = mutableStateOf(false)
    private val isUltronSpeakingState: MutableState<Boolean> = mutableStateOf(false)
    private val isConnectedState: MutableState<Boolean> = mutableStateOf(false)
    private val shouldDismissState: MutableState<Boolean> = mutableStateOf(false)
    private val pendingNotifications: MutableList<Triple<String, String, String>> = ArrayList()
    private val chargingReceiver = ChargingReceiver()
    private val callReceiver = CallReceiver()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val SILENCE_TIMEOUT_MS: Long
        get() = prefs.maxActiveTimeout * 1000L

    fun isGeminiActive(): Boolean = geminiSession != null

    fun isVoiceEnrolled(): Boolean {
        return speakerManager?.store?.hasOwnerProfile() ?: false
    }

    fun startVoiceEnrollment() {
        speakerManager?.startEnrollment()
    }

    fun clearVoiceEnrollment() {
        speakerManager?.clearEnrollment()
    }

    fun injectReminderMessage(message: String) {
        mainScope.launch {
            var text = message
            val language = prefs.language.ifBlank { "Hinglish" }
            val lower = language.lowercase(Locale.ROOT)
            if (lower != "hinglish" && lower != "hindi") {
                text = try {
                    runBlocking { TranslationManager.translateToUserLanguage(message, language) }
                } catch (e: Exception) {
                    Log.e(TAG, "Γ¥î Reminder translation failed: ${e.message}")
                    message
                }
            }
            val session = geminiSession
            if (session != null) {
                if (!isUltronCurrentlySpeaking && !isToolExecuting) {
                    Log.d(TAG, "ΓÅ░ Injecting reminder to active session")
                    session.injectMessage("[SYSTEM] $text. Boss ko clearly ek baar batao.")
                } else {
                    Log.d(TAG, "ΓÅ░ Ultron busy ΓÇö queuing reminder")
                    pendingNotifications.add(Triple("__REMINDER__", text, ""))
                }
            } else {
                Log.d(TAG, "ΓÅ░ Session inactive ΓÇö starting one-shot session")
                val isSecurityAlert = text.contains("Mera phone chhua") ||
                    text.contains("photo click ki hai") ||
                    text.contains("Touch guard") ||
                    text.contains("unlock ho gaya")
                if (notifSessionActive) {
                    pendingNotifications.add(Triple("__REMINDER__", text, ""))
                } else {
                    startOneShot(text, !isSecurityAlert)
                }
            }
        }
    }

    fun startReminderSession(message: String) {
        mainScope.launch {
            if (notifSessionActive) {
                pendingNotifications.add(Triple("__REMINDER__", message, ""))
            } else {
                startOneShot(message, true)
            }
        }
    }

    fun injectCallIntercept(callerName: String, number: String, message: String) {
        mainScope.launch {
            pendingCallerName = callerName
            pendingCallNumber = number
            val text = "[CALL_INCOMING] $message Boss ka jawab suno ΓÇö 'haan', 'uthao', 'receive' bole toh [TOOL:receive_call] use karo. 'nahi', 'mat uthao', 'reject' bole toh [TOOL:reject_call] use karo. Sirf ek baar poochho."
            if (!isUltronCurrentlySpeaking && !isToolExecuting && geminiSession != null) {
                geminiSession?.injectMessage(text)
            } else {
                pendingNotifications.add(Triple("__CALL__", text, number))
            }
        }
    }

    fun startCallInterceptSession(callerName: String, number: String, message: String) {
        mainScope.launch {
            pendingCallerName = callerName
            pendingCallNumber = number
            val text = "[CALL_INCOMING] $message Boss ka jawab suno ΓÇö 'haan' ya 'uthao' bole toh [TOOL:receive_call] use karo. 'nahi' ya 'reject' bole toh [TOOL:reject_call] use karo."
            if (notifSessionActive) {
                pendingNotifications.add(Triple("__CALL__", text, number))
            } else {
                startOneShot(text, false)
            }
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
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        db = DatabaseHelper(this)
        prefs = AppPreferences(this)
        val monitor = BatteryMonitor.getInstance(this)
        batteryMonitor = monitor
        monitor?.startMonitoring()
        Log.d(TAG, "Γ£à Battery monitor started")
        createNotificationChannel()
        startForegroundNotification()
        setupNotificationListener()
        setupChargingReceiver()
        setupCallReceiver()
        vadManager = VadManager(context = this)
        wakeWordManager = WakeWordManager(context = this, vadManager = vadManager!!)
        speakerManager = SpeakerVerificationManager(context = this, wakeWordManager = wakeWordManager!!)
        scope.launch {
            vadManager?.start()
            startPipelineListening()
        }
    }

    private fun acquireWakeLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (cpuWakeLock == null) {
                cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ultron:CpuWakeLock").apply {
                    acquire(120000L) // 2 minute timeout maximum
                }
                Log.d(TAG, "≡ƒöæ CPU WakeLock acquired")
            }

            @Suppress("DEPRECATION")
            val screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Ultron:ScreenWakeLock"
            )
            screenWakeLock.acquire(10000L) // Wake the screen for 10 seconds
            Log.d(TAG, "≡ƒÆí Screen WakeLock acquired (waking screen)")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake locks: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
                Log.d(TAG, "≡ƒöÆ CPU WakeLock released")
            }
            cpuWakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake locks: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        UltronNotificationListener.onNotificationReceived = null
        ChargingReceiver.onChargerConnected = null
        ChargingReceiver.onChargerDisconnected = null
        CallReceiver.onIncomingCall = null
        CallReceiver.onCallEnded = null
        batteryMonitor?.stopMonitoring()
        batteryMonitor = null
        Log.d(TAG, "Γ£à Battery monitor stopped")
        try {
            unregisterReceiver(chargingReceiver)
        } catch (unused: Exception) {
        }
        try {
            unregisterReceiver(callReceiver)
        } catch (unused: Exception) {
        }
        stopPipelineListening()
        callAudioManager?.destroy()
        callAudioManager = null
        dismissOverlay()
        cleanupNotifSession()
        pendingNotifications.clear()
        
        
        scope.cancel()
        mainScope.cancel()
    }

    private fun setupCallReceiver() {
        CallReceiver.onIncomingCall = { name, number ->
            mainScope.launch {
                delay(800L)
                val resolvedName = CallReceiver.pendingCallName ?: name
                val resolvedNumber = CallReceiver.pendingCallNumber ?: number
                handleIncomingCall(resolvedName, resolvedNumber)
            }
        }
        CallReceiver.onCallEnded = {
            mainScope.launch {
                Log.d(TAG, "≡ƒô₧ Call ended ΓÇö full cleanup")
                restoreRingVolume()
                callAudioManager?.destroy()
                callAudioManager = null
                if (notifSessionActive) {
                    notifGeminiSession?.disconnect()
                    notifGeminiSession = null
                    notifAudioPlayer?.release()
                    notifAudioPlayer = null
                    notifSessionActive = false
                }
                delay(800L)
                if (geminiSession == null && !isPipelineListening) {
                    Log.d(TAG, "≡ƒöä Restarting Vosk after call end")
                    startPipelineListening()
                }
            }
        }
        registerReceiver(callReceiver, IntentFilter("android.intent.action.PHONE_STATE"))
        Log.d(TAG, "≡ƒô₧ CallReceiver registered")
    }

    private fun silenceRingVolume() {
        try {
            val audioManager = getSystemService("audio") as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            Log.d(TAG, "≡ƒöç Ring volume ΓåÆ 0")
        } catch (e: Exception) {
            Log.e(TAG, "Γ¥î silenceRingVolume: ${e.message}")
        }
    }

    private fun restoreRingVolume() {
        ringRestoreJob?.cancel()
        ringRestoreJob = null
        try {
            val audioManager = getSystemService("audio") as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            Log.d(TAG, "≡ƒöö Ring volume ΓåÆ max ($maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "Γ¥î restoreRingVolume: ${e.message}")
        }
    }

    /**
     * Safety net: unconditionally restore the ring after [delayMs] so the phone
     * rings normally for the rest of the call, regardless of what happens to the
     * announcement session. The normal announce paths call restoreRingVolume()
     * earlier (which cancels this), so this only fires when they didn't.
     */
    private fun scheduleRingRestore(delayMs: Long) {
        ringRestoreJob?.cancel()
        ringRestoreJob = mainScope.launch {
            delay(delayMs)
            Log.d(TAG, "≡ƒöö Guaranteed ring restore (safety net)")
            try {
                val audioManager = getSystemService("audio") as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Γ¥î scheduleRingRestore: ${e.message}")
            }
        }
    }

    private fun handleIncomingCall(name: String, number: String) {
        if (!prefs.toolCallAnnounce) {
            Log.d(TAG, "≡ƒô₧ Call announce disabled ΓÇö ignoring")
            return
        }
        val callControlEnabled = prefs.callControlEnabled
        Log.d(TAG, "≡ƒô₧ Incoming: '$name' | control=$callControlEnabled")
        silenceRingVolume()
        // Guarantee the ring comes back even if the announcement session fails.
        // Announce-only: ring back shortly after the ~2-3s announcement.
        // Call-control: keep silence longer so Boss can hear+answer (matches the
        // 30s hard timeout below), then force the ring back regardless.
        scheduleRingRestore(if (callControlEnabled) 32000L else 7000L)
        stopPipelineListening()
        val language = prefs.language.ifBlank { "Hinglish" }
        var announcement = "$name ka call aa raha hai"
        val lower = language.lowercase(Locale.ROOT)
        if (lower != "hinglish" && lower != "hindi") {
            try {
                announcement = runBlocking {
                    TranslationManager.translateToUserLanguage(announcement, language)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Γ¥î Call announcement translation failed: ${e.message}")
            }
        }
        if (geminiSession == null) {
            mainScope.launch {
                if (notifSessionActive) {
                    cleanupNotifSession()
                    delay(400L)
                }
                delay(300L)
                startCallGemini(name, callControlEnabled)
            }
            return
        }
        val message = if (callControlEnabled) {
            "[SYSTEM] $announcement. Boss se poochho: 'Boss, $name ka call hai ΓÇö uthana hai?' Haan ΓåÆ [TOOL:call_accept]. Nahi ΓåÆ [TOOL:call_reject]."
        } else {
            "[SYSTEM] Sirf ek baar bolo: '$announcement Boss!' phir chup raho."
        }
        geminiSession?.injectMessage(message)
        if (callControlEnabled) {
            return
        }
        mainScope.launch {
            delay(4000L)
            restoreRingVolume()
        }
    }

    private fun startCallGemini(callerName: String, callControl: Boolean) {
        // A live/arming main session owns the assistant audio channel ΓÇö don't
        // stack a call announcement session on top of it.
        if (notifSessionActive || geminiSession != null || wakeWordJustDetected) {
            Log.d(TAG, "ΓÜá∩╕Å startCallGemini skipped ΓÇö session already active")
            return
        }
        notifSessionActive = true
        Log.d(TAG, "≡ƒô₧ Starting call session: $callerName | control=$callControl")
        val player = AudioPlayerManager()
        player.initialize()
        notifAudioPlayer = player
        var ultronAnnouncementDone = false
        var recordingStarted = false
        var actionCompleted = false
        callAudioManager = CallAudioManager(
            { chunk ->
                notifGeminiSession?.sendAudioChunk(chunk)
            },
            {
                mainScope.launch {
                    Log.w(TAG, "ΓÅ▒ User didn't respond ΓÇö restoring ring & cleaning up")
                    if (!actionCompleted) {
                        actionCompleted = true
                        restoreRingVolume()
                        callAudioManager?.destroy()
                        callAudioManager = null
                        cleanupNotifSession()
                    }
                }
            },
            4000L
        )
        val callMessage = if (callControl) {
            """
        $callerName ka call aa raha hai.

        STEP 1: Clearly bolo ΓÇö "Boss, $callerName ka call hai ΓÇö uthana hai?"
        STEP 2: Phir chup ho jao aur user ka jawab suno.
        STEP 3: User jawab dega:
           - Agar user bole 'haan', 'ha', 'uthao', 'receive', 'utha le', 'pick up', 'yes', 'le lo', 'haan haan' ΓåÆ
             Sirf ek short word bolo "Theek hai" aur turant [TOOL:call_accept] use karo.
           - Agar user bole 'nahi', 'na', 'mat uthao', 'reject', 'cut', 'kaat de', 'no', 'chhod', 'reject kar de' ΓåÆ
             Sirf ek short word bolo "Ok" aur turant [TOOL:call_reject] use karo.

        VERY IMPORTANT: Tool tag ZAROORI hai ΓÇö [TOOL:call_accept] ya [TOOL:call_reject] ΓÇö bina iske action nahi hoga.
        Action ke baad bilkul chup ho jao.
        """.trimIndent()
        } else {
            "Sirf ek baar bolo: '$callerName ka call aa raha hai Boss!' phir bilkul chup."
        }
        notifGeminiSession = GeminiLiveSession(
            apiKey = prefs.googleApiKey,
            db = db,
            context = this,
            onAudioReceived = { audio ->
                callAudioManager?.isMuted = true
                notifAudioPlayer?.playAudioChunk(audio)
            },
            onTranscriptReceived = { },
            onUserTranscriptReceived = { chunk ->
                Log.d(TAG, "≡ƒæé User said: $chunk")
            },
            onStatusChanged = { },
            onVadStateChanged = { speaking ->
                mainScope.launch {
                    onCallVadStateChanged(
                        speaking, callControl,
                        { ultronAnnouncementDone }, { ultronAnnouncementDone = it },
                        { recordingStarted }, { recordingStarted = it },
                        { actionCompleted }, { actionCompleted = it }
                    )
                }
            },
            onByeDetected = null,
            skipGreeting = false,
            callMessage = callMessage,
            onToolStarted = { toolName ->
                Log.d(TAG, "≡ƒöº Tool started in call session: $toolName")
                callAudioManager?.isMuted = true
            },
            onToolFinished = null,
        )
        scope.launch {
            val connectionState = notifGeminiSession?.connectionState ?: return@launch
            connectionState.collect { state ->
                when (state) {
                    GeminiLiveSession.ConnectionState.CONNECTED -> {
                        Log.d(TAG, "Γ£à Call session connected")
                        if (callControl) {
                            mainScope.launch {
                                delay(30000L)
                                if (notifSessionActive && !actionCompleted) {
                                    Log.d(TAG, "ΓÅ▒ Hard call timeout ΓÇö cleanup")
                                    actionCompleted = true
                                    callAudioManager?.destroy()
                                    callAudioManager = null
                                    restoreRingVolume()
                                    cleanupNotifSession()
                                }
                            }
                        }
                    }
                    GeminiLiveSession.ConnectionState.ERROR -> {
                        mainScope.launch {
                            callAudioManager?.destroy()
                            callAudioManager = null
                            restoreRingVolume()
                            cleanupNotifSession()
                        }
                    }
                    else -> {}
                }
            }
        }
        mainScope.launch {
            delay(200L)
            notifGeminiSession?.connect()
        }
    }

    private suspend fun onCallVadStateChanged(
        speaking: Boolean,
        callControl: Boolean,
        getAnnouncementDone: () -> Boolean, setAnnouncementDone: (Boolean) -> Unit,
        getRecordingStarted: () -> Boolean, setRecordingStarted: (Boolean) -> Unit,
        getActionCompleted: () -> Boolean, setActionCompleted: (Boolean) -> Unit,
    ) {
        if (speaking) {
            callAudioManager?.isMuted = false
            return
        }
        if (getAnnouncementDone()) {
            callAudioManager?.isMuted = false
            if (callControl && getRecordingStarted() && !getActionCompleted()) {
                setActionCompleted(true)
                // Wait for the queued "theek hai" / "ok" tail to finish playing before cleanup.
                val tailMs = notifAudioPlayer?.remainingPlaybackMs() ?: 0L
                val waitMs = tailMs + 500L
                Log.d(TAG, "≡ƒöç Call action VAD off ΓÇö waiting ${waitMs}ms for tail")
                delay(waitMs)
                Log.d(TAG, "Γ£à Call action done ΓÇö cleanup & restart Vosk")
                restoreRingVolume()
                callAudioManager?.destroy()
                callAudioManager = null
                cleanupNotifSession()
            }
            return
        }
        setAnnouncementDone(true)
        // Wait for the announcement audio to finish before acting.
        val tailMs = notifAudioPlayer?.remainingPlaybackMs() ?: 0L
        val waitMs = tailMs + 500L
        Log.d(TAG, "Γ£à Ultron announcement done ΓÇö waiting ${waitMs}ms for tail")
        delay(waitMs)
        if (!callControl) {
            restoreRingVolume()
            callAudioManager?.destroy()
            callAudioManager = null
            cleanupNotifSession()
        } else if (!getRecordingStarted()) {
            setRecordingStarted(true)
            delay(200L)
            val started = callAudioManager?.start() ?: false
            if (started) {
                callAudioManager?.isMuted = false
                Log.d(TAG, "≡ƒÄñ Call mic active ΓÇö waiting for user response")
            } else {
                Log.e(TAG, "Γ¥î Call recording start failed")
                restoreRingVolume()
                callAudioManager?.destroy()
                callAudioManager = null
                cleanupNotifSession()
            }
        }
    }

    private fun setupChargingReceiver() {
        ChargingReceiver.onChargerConnected = {
            mainScope.launch {
                val message = translateEventMessage("Charger connect ho gaya Boss!")
                handleEventMessage("Charger", message)
            }
        }
        ChargingReceiver.onChargerDisconnected = {
            mainScope.launch {
                val message = translateEventMessage("Charger disconnect ho gaya Boss!")
                handleEventMessage("Charger", message)
            }
        }
        val intentFilter = IntentFilter()
        intentFilter.addAction("android.intent.action.ACTION_POWER_CONNECTED")
        intentFilter.addAction("android.intent.action.ACTION_POWER_DISCONNECTED")
        registerReceiver(chargingReceiver, intentFilter)
        Log.d(TAG, "≡ƒöî ChargingReceiver registered")
    }

    private suspend fun translateEventMessage(message: String): String {
        return try {
            val language = prefs.language.ifBlank { "Hinglish" }
            val lower = language.lowercase(Locale.ROOT)
            if (lower != "hinglish" && lower != "hindi") {
                TranslationManager.translateToUserLanguage(message, language)
            } else {
                message
            }
        } catch (e: Exception) {
            Log.e(TAG, "Γ¥î Event translation failed: ${e.message}")
            message
        }
    }

    private fun handleEventMessage(tag: String, message: String) {
        Log.d(TAG, "≡ƒôú Event [$tag]: $message")
        val session = geminiSession
        if (session != null) {
            if (isUltronCurrentlySpeaking || isToolExecuting) {
                pendingNotifications.add(Triple(tag, message, ""))
            } else {
                session.injectMessage("[SYSTEM] $message. Boss ko sirf ek baar batao.")
            }
            return
        }
        if (notifSessionActive) {
            pendingNotifications.add(Triple(tag, message, ""))
        } else {
            startOneShot(message, tag == "__REMINDER__")
        }
    }

    private fun setupNotificationListener() {
        UltronNotificationListener.onNotificationReceived = { appName, title, body ->
            mainScope.launch {
                if (!prefs.toolNotifications) {
                    Log.d(TAG, "ΓÜá∩╕Å Notifications disabled in settings ΓÇö ignoring")
                    return@launch
                }
                val message = buildNotifMessage(appName, title, body)
                val session = geminiSession
                if (session != null) {
                    if (!isUltronCurrentlySpeaking && !isToolExecuting) {
                        session.injectMessage(message)
                    } else {
                        pendingNotifications.add(Triple(appName, title, body))
                    }
                } else {
                    if (!notifSessionActive) {
                        startOneShot(message, false)
                    } else {
                        pendingNotifications.add(Triple(appName, title, body))
                    }
                }
            }
        }
    }

    private fun buildNotifMessage(appName: String, title: String, body: String): String {
        val message = "Boss, $appName pe notification aaya hai."
        val language = prefs.language.ifBlank { "Hinglish" }
        val lower = language.lowercase(Locale.ROOT)
        if (lower == "hinglish" || lower == "hindi") {
            return message
        }
        return try {
            runBlocking { TranslationManager.translateToUserLanguage(message, language) }
        } catch (e: Exception) {
            Log.e(TAG, "Γ¥î Notification translation failed: ${e.message}")
            message
        }
    }

    private fun startOneShot(message: String, isReminder: Boolean) {
        // Don't announce over an active/arming main conversation ΓÇö that's the
        // dual-voice case. wakeWordJustDetected covers the 300ms gap between the
        // wake word firing and geminiSession being created.
        if (notifSessionActive || geminiSession != null || wakeWordJustDetected) {
            Log.d(TAG, "ΓÜá∩╕Å startOneShot skipped ΓÇö main/one-shot session active")
            return
        }
        notifSessionActive = true
        Log.d(TAG, "≡ƒÜÇ One-shot session: $message")
        val player = AudioPlayerManager()
        player.initialize()
        notifAudioPlayer = player
        var speechStarted = false
        notifGeminiSession = GeminiLiveSession(
            apiKey = prefs.googleApiKey,
            db = db,
            context = this,
            onAudioReceived = { audio ->
                speechStarted = true
                notifAudioPlayer?.playAudioChunk(audio)
            },
            onTranscriptReceived = { },
            onUserTranscriptReceived = { },
            onStatusChanged = { },
            onVadStateChanged = { speaking ->
                // When VAD says the turn is over, wait for queued audio to drain before cleanup.
                if (!speaking && speechStarted) {
                    mainScope.launch {
                        val tailMs = notifAudioPlayer?.remainingPlaybackMs() ?: 0L
                        val waitMs = tailMs + 500L
                        Log.d(TAG, "≡ƒöç One-shot VAD off ΓÇö waiting ${waitMs}ms for tail")
                        delay(waitMs)
                        cleanupNotifSession()
                    }
                }
            },
            onByeDetected = null,
            skipGreeting = true,
            callMessage = null,
            onToolStarted = null,
            onToolFinished = null,
        )
        scope.launch {
            val connectionState = notifGeminiSession?.connectionState ?: return@launch
            connectionState.collect { state ->
                when (state) {
                    GeminiLiveSession.ConnectionState.CONNECTED -> {
                        delay(1000L)
                        var text = message
                        if (isReminder) {
                            text = "[REMINDER] $text ΓÇö Boss ko clearly aur warmly ek baar bolna, jaise 'Boss, yaad hai na, $text'"
                        }
                        notifGeminiSession?.injectMessage(text)
                    }
                    GeminiLiveSession.ConnectionState.ERROR -> {
                        mainScope.launch { cleanupNotifSession() }
                    }
                    else -> {}
                }
            }
        }
        mainScope.launch {
            delay(200L)
            notifGeminiSession?.connect()
        }
    }

    private fun teardownNotifSession() {
        // Synchronous, no relaunch ΓÇö used when the main session is taking over.
        // (cleanupNotifSession() runs async and can re-spawn a queued one-shot,
        // which is exactly what caused two sessions to play at once.)
        callAudioManager?.destroy()
        callAudioManager = null
        notifGeminiSession?.disconnect()
        notifGeminiSession = null
        notifAudioPlayer?.release()
        notifAudioPlayer = null
        notifSessionActive = false
    }

    private fun cleanupNotifSession() {
        mainScope.launch {
            Log.d(TAG, "≡ƒº╣ Cleaning up one-shot session")
            callAudioManager?.destroy()
            callAudioManager = null
            notifGeminiSession?.disconnect()
            notifGeminiSession = null
            notifAudioPlayer?.release()
            notifAudioPlayer = null
            notifSessionActive = false
            delay(300L)
            if (pendingNotifications.isNotEmpty() && geminiSession == null) {
                val triple = pendingNotifications.removeAt(0)
                delay(500L)
                val kind = triple.first
                when (kind) {
                    "__REMINDER__" -> startOneShot(triple.second, true)
                    "__CALL__" -> handleIncomingCall(triple.second, triple.third)
                    else -> {
                        val message = buildNotifMessage(triple.first, triple.second, triple.third)
                        startOneShot(message, false)
                    }
                }
                return@launch
            }
            if (geminiSession == null) {
                Log.d(TAG, "≡ƒöä Restarting Vosk after session cleanup")
                startPipelineListening()
            }
        }
    }

    private fun startPipelineListening() {
        if (isPipelineListening) return
        isPipelineListening = true
        stage = Stage.IDLE
        Log.d(TAG, "▶ Pipeline listening started")
        
        pipelineJob?.cancel()
        pipelineJob = scope.launch {
            vadManager?.audioFrames?.collect { frame ->
                processPipelineFrame(frame)
            }
        }
    }
    
    private fun stopPipelineListening() {
        isPipelineListening = false
        pipelineJob?.cancel()
        Log.d(TAG, "⏸ Pipeline listening stopped")
    }

    private fun processPipelineFrame(frame: AudioFrame) {
        if (!isPipelineListening) return
        
        synchronized(recentFrames) {
            recentFrames.addLast(frame)
            if (recentFrames.size > 60) recentFrames.removeFirst()
        }
        
        if (geminiCooldownFrames > 0) geminiCooldownFrames--

        if (stage == Stage.GEMINI_ACTIVE) {
            val vadStateObj = vadManager?.state?.value ?: VadState.Idle
            if (geminiSession != null) {
                val byteBuffer = java.nio.ByteBuffer.allocate(frame.pcmData.size * 2)
                byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                byteBuffer.asShortBuffer().put(frame.pcmData)
                val bytes = byteBuffer.array()
                geminiSession?.sendAudioChunk(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
            if (vadStateObj is VadState.Idle && lastVadState !is VadState.Idle) {
                Log.i(TAG, "[Pipeline] Speech ended in GEMINI_ACTIVE. Returning to IDLE after cooldown.")
                stage = Stage.IDLE
                geminiCooldownFrames = 50
                synchronized(speechFrames) { speechFrames.clear() }
            }
            lastVadState = vadStateObj
            return
        }

        val vadStateObj = vadManager?.state?.value ?: VadState.Idle
        wakeWordManager?.processAudioFrame(frame)
        val wkwStateObj = wakeWordManager?.state?.value ?: WakeWordState.Idle

        if (vadStateObj !is VadState.Idle) {
            geminiCooldownFrames = 0
            if (lastVadState is VadState.Idle) Log.i(TAG, "[Pipeline] SpeechStarted - Stage: $stage")
            lastVadState = vadStateObj
        } else if (vadStateObj is VadState.Idle && lastVadState !is VadState.Idle) {
            if (geminiCooldownFrames == 0) geminiCooldownFrames = 15
            if (geminiCooldownFrames == 1) {
                Log.i(TAG, "[Pipeline] SpeechEnded - Stage: $stage")
                if (stage == Stage.VOICE_DETECTED) {
                    stage = Stage.IDLE
                    synchronized(speechFrames) { speechFrames.clear() }
                }
                lastVadState = vadStateObj
            }
        }

        when (stage) {
            Stage.IDLE -> {
                if (wkwStateObj is WakeWordState.WakeDetected) {
                    Log.i(TAG, "[Pipeline] STAGE 0 -> 2: Wake word detected directly!")
                    stage = Stage.WAKE_WORD_DETECTED
                    runEcapaVerification()
                } else if (vadStateObj !is VadState.Idle) {
                    Log.i(TAG, "[Pipeline] STAGE 0 -> 1: Voice detected")
                    stage = Stage.VOICE_DETECTED
                    synchronized(speechFrames) { speechFrames.clear() }
                }
            }
            Stage.VOICE_DETECTED -> {
                synchronized(speechFrames) {
                    if (speechFrames.size < 150) speechFrames.add(frame)
                }
                if (wkwStateObj is WakeWordState.WakeDetected) {
                    Log.i(TAG, "[Pipeline] STAGE 1 -> 2: Wake word detected!")
                    stage = Stage.WAKE_WORD_DETECTED
                    runEcapaVerification()
                }
            }
            Stage.WAKE_WORD_DETECTED -> {
                synchronized(speechFrames) {
                    if (speechFrames.size < 150) speechFrames.add(frame)
                }
            }
            else -> {}
        }
    }

    private fun runEcapaVerification() {
        scope.launch {
            val framesCopy = synchronized(speechFrames) { ArrayList(speechFrames) }
            val finalFrames = if (framesCopy.size < 20) {
                synchronized(recentFrames) { ArrayList(recentFrames) }
            } else framesCopy
            
            val result = speakerManager?.verifyCurrentSpeaker(finalFrames)
            if (result is VerificationResult.Verified) {
                Log.i(TAG, "[Pipeline] STAGE 2 -> 3: Speaker VERIFIED. Proceeding to GEMINI_ACTIVE.")
                stage = Stage.GEMINI_ACTIVE
                onWakeWordDetected()
            } else {
                Log.w(TAG, "[Pipeline] Speaker verification FAILED/UNRECOGNIZED. Returning to IDLE.")
                stage = Stage.IDLE
                synchronized(speechFrames) { speechFrames.clear() }
            }
        }
    }
fun triggerManualSession() { onWakeWordDetected() }

    private fun onWakeWordDetected() {
        if (wakeWordJustDetected) {
            return
        }
        wakeWordJustDetected = true
        Log.d(TAG, "≡ƒöÑ WAKE WORD DETECTED")
        acquireWakeLocks()
        // Main session is taking over. Drop any queued one-shots and tear down the
        // active one SYNCHRONOUSLY here ΓÇö using async cleanupNotifSession() would
        // let a queued notification re-spawn a one-shot on top of startGemini(),
        // producing two voices at once.
        pendingNotifications.clear()
        teardownNotifSession()
        stopPipelineListening()
        mainScope.launch {
            delay(300L)
            showOverlay()
            startGemini()
        }
    }

    fun onByeWordDetected() {
        Log.d(TAG, "≡ƒæï BYE DETECTED")
        mainScope.launch {
            shouldDismissState.value = true
            delay(700L)
            dismissOverlay()
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "No overlay permission")
            resumeWakeWord()
            return
        }
        val wm = getSystemService("window") as WindowManager
        windowManager = wm
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(displayMetrics)
        // RECOVERY NOTE: 2038 = TYPE_APPLICATION_OVERLAY, 1816 = FLAG_NOT_FOCUSABLE(8) |
        // FLAG_NOT_TOUCHABLE(16) | FLAG_LAYOUT_IN_SCREEN(256) | FLAG_LAYOUT_NO_LIMITS(512) |
        // FLAG_HARDWARE_ACCELERATED(0x1000... via 1024 here), -3 = TRANSLUCENT.
        val layoutParams = WindowManager.LayoutParams(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 0
        layoutParams.y = 0
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                EdgeGlowOverlay(
                    isUserSpeakingState.value,
                    isUltronSpeakingState.value,
                    isConnectedState.value,
                    shouldDismissState.value,
                )
                val coords = ghostPointerCoordinates.value
                if (coords != null) {
                    GhostPointerRipple(coords) {
                        ghostPointerCoordinates.value = null
                    }
                }
            }
        }
        overlayView = composeView
        try {
            windowManager?.addView(composeView, layoutParams)
            Log.d(TAG, "Γ£à Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "showOverlay failed: ${e.message}")
            resumeWakeWord()
        }
    }

    private fun dismissOverlay() {
        shouldDismissState.value = false
        pendingNotifications.clear()
        isUltronCurrentlySpeaking = false
        isToolExecuting = false
        micUnmuteJob?.cancel()
        micUnmuteJob = null
        try {
            val view = overlayView
            if (view != null) {
                windowManager?.removeView(view)
            }
            overlayView = null
        } catch (unused: Exception) {
        }
        silenceJob?.cancel()
        geminiAudioRecorder?.release()
        geminiAudioRecorder = null
        audioPlayer?.release()
        audioPlayer = null
        geminiSession?.disconnect()
        geminiSession = null
        isUserSpeakingState.value = false
        isUltronSpeakingState.value = false
        isConnectedState.value = false
        releaseWakeLocks()
        resumeWakeWord()
    }

    private fun resumeWakeWord() {
        mainScope.launch {
            delay(1000L)
            wakeWordJustDetected = false
            
            // Only restart Vosk if no sessions are active ΓÇö prevents two AudioRecords
            // contending for the mic (Bug 7).
            if (geminiSession == null && !notifSessionActive) {
                startPipelineListening()
            } else {
                Log.d(TAG, "ΓÜá∩╕Å resumeWakeWord deferred ΓÇö session still active")
            }
        }
    }

    private fun startGemini() {
        // Never run two main sessions, and never run alongside a one-shot ΓÇö either
        // would put two AudioTracks on the assistant channel simultaneously.
        if (geminiSession != null) {
            Log.w(TAG, "startGemini ignored ΓÇö main session already active")
            return
        }
        teardownNotifSession()
        val player = AudioPlayerManager()
        player.initialize()
        audioPlayer = player
        geminiAudioRecorder = AudioRecorderManager()
        geminiSession = GeminiLiveSession(
            apiKey = prefs.googleApiKey,
            db = db,
            context = this,
            onAudioReceived = { audio ->
                mainScope.launch {
                    // Play immediately ΓÇö no buffering, no artificial 1.5s lag.
                    // The mic stays muted until the queued audio tail drains (onGeminiVadStateChanged).
                    playAudioDirectly(audio)
                }
            },
            onTranscriptReceived = { },
            onUserTranscriptReceived = {
                isUserSpeakingState.value = true
                resetSilenceTimer()
                lastActivityTime = System.currentTimeMillis()
            },
            onStatusChanged = { },
            onVadStateChanged = { speaking ->
                mainScope.launch { onGeminiVadStateChanged(speaking) }
            },
            onByeDetected = {
                onByeWordDetected()
            },
            skipGreeting = false,
            callMessage = null,
            onToolStarted = { toolName ->
                mainScope.launch {
                    Log.d(TAG, "≡ƒöº Tool starting: $toolName")
                    isToolExecuting = true
                    resetSilenceTimer()
                    lastActivityTime = System.currentTimeMillis()
                }
            },
            onToolFinished = { toolName ->
                mainScope.launch { onToolFinished(toolName) }
            },
        )
        scope.launch {
            val audioChunks = geminiAudioRecorder?.audioChunks ?: return@launch
            audioChunks.collect { chunk ->
                geminiSession?.sendAudioChunk(chunk)
            }
        }
        scope.launch {
            val connectionState = geminiSession?.connectionState ?: return@launch
            connectionState.collect { state ->
                isConnectedState.value = state == GeminiLiveSession.ConnectionState.CONNECTED
                if (state == GeminiLiveSession.ConnectionState.CONNECTED) {
                    geminiAudioRecorder?.startContinuousRecording()
                    lastActivityTime = System.currentTimeMillis()
                    startSilenceTimer()
                }
            }
        }
        mainScope.launch {
            delay(300L)
            geminiSession?.connect()
        }
    }

    private fun playAudioDirectly(audio: String) {
        geminiAudioRecorder?.isMuted = true
        isUltronSpeakingState.value = true
        isUltronCurrentlySpeaking = true
        audioPlayer?.playAudioChunk(audio)
        resetSilenceTimer()
        lastActivityTime = System.currentTimeMillis()
    }

    private suspend fun onGeminiVadStateChanged(speaking: Boolean) {
        if (speaking) {
            isUserSpeakingState.value = true
            // User interrupted ΓÇö stop Ultron's playback immediately, cancel mic-unmute timer.
            audioPlayer?.stopAndClear()
            micUnmuteJob?.cancel()
            micUnmuteJob = null
            resetSilenceTimer()
            lastActivityTime = System.currentTimeMillis()
        } else {
            // Server-side VAD says the turn is over, but queued audio is still playing.
            // Keep the mic muted until the tail drains + 250ms margin so Ultron doesn't
            // hear its own voice from the speaker (Bug 1). Only then inject pending notifications.
            isUserSpeakingState.value = false
            val tailMs = audioPlayer?.remainingPlaybackMs() ?: 0L
            val waitMs = tailMs + 250L
            Log.d(TAG, "≡ƒöç VAD off ΓÇö mic stays muted for ${waitMs}ms (tail=$tailMs)")
            micUnmuteJob?.cancel()
            micUnmuteJob = mainScope.launch {
                delay(waitMs)
                isUltronCurrentlySpeaking = false
                isUltronSpeakingState.value = false
                geminiAudioRecorder?.isMuted = false
                Log.d(TAG, "≡ƒÄñ Mic unmuted ΓÇö tail drained")
                if (!isToolExecuting) {
                    startSilenceTimer()
                } else {
                    Log.d(TAG, "ΓÅ╕∩╕Å Timer paused - tool still executing")
                }
                if (pendingNotifications.isNotEmpty()) {
                    val triple = pendingNotifications.removeAt(0)
                    delay(800L)
                    val message = if (triple.first != "__REMINDER__") {
                        buildNotifMessage(triple.first, triple.second, triple.third)
                    } else {
                        "[SYSTEM] ${triple.second}. Boss ko clearly batao."
                    }
                    geminiSession?.injectMessage(message)
                }
            }
        }
    }

    private suspend fun onToolFinished(toolName: String) {
        Log.d(TAG, "Γ£à Tool completed: $toolName")
        isToolExecuting = false
        lastActivityTime = System.currentTimeMillis()
        if (!isUltronCurrentlySpeaking && !isUltronSpeakingState.value) {
            delay(500L)
            startSilenceTimer()
            Log.d(TAG, "Γû╢∩╕Å Timer resumed after tool completion")
        } else {
            Log.d(TAG, "ΓÅ╕∩╕Å Timer stays paused - Ultron still speaking")
        }
    }

    private fun startSilenceTimer() {
        silenceJob?.cancel()
        if (isToolExecuting || isUltronCurrentlySpeaking || isUltronSpeakingState.value) {
            Log.d(TAG, "ΓÅ╕∩╕Å Timer not started - activity ongoing")
            return
        }
        // Reset lastActivityTime here so the timer always measures from *this* start call,
        // preventing double-restart storms where idle < SILENCE_TIMEOUT_MS forever (Bug 8).
        lastActivityTime = System.currentTimeMillis()
        Log.d(TAG, "ΓÅ▒∩╕Å Silence timer started (${SILENCE_TIMEOUT_MS / 1000}s)")
        silenceJob = mainScope.launch {
            delay(SILENCE_TIMEOUT_MS)
            if (!isToolExecuting && !isUltronCurrentlySpeaking && !isUltronSpeakingState.value) {
                Log.d(TAG, "ΓÅ░ Silence timeout ΓÇö dismissing")
                onByeWordDetected()
            } else {
                Log.d(TAG, "ΓÅ╕∩╕Å Timer expired but activity detected ΓÇö restarting")
                startSilenceTimer()
            }
        }
    }

    private fun resetSilenceTimer() {
        silenceJob?.cancel()
        lastActivityTime = System.currentTimeMillis()
    }

    private fun createNotificationChannel() {
        val notificationChannel =
            NotificationChannel(CHANNEL_ID, "Ultron Wake Word", NotificationManager.IMPORTANCE_LOW)
        notificationChannel.setShowBadge(false)
        notificationChannel.setSound(null, null)
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(notificationChannel)
    }

    private fun startForegroundNotification() {
        val build = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ultron AI Assistant")
            .setContentText("Say 'Ultron' to activate")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            // RECOVERY NOTE: 128 = FOREGROUND_SERVICE_TYPE_MICROPHONE.
            startForeground(NOTIFICATION_ID, build, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, build)
        }
    }

    companion object {
        const val ASSETS_MODEL_DIR = "vosk-model-small-en-us-0.15"
        const val CHANNEL_ID = "wakeword_channel"
        const val NOTIFICATION_ID = 10
        const val SAMPLE_RATE = 16000
        private const val TAG = "WakeWordService"

        private var activeInstance: WakeWordService? = null
        var ghostPointerCoordinates: MutableState<PointF?> = mutableStateOf(null)

        fun getActiveInstance(): WakeWordService? = activeInstance

        fun startService(context: android.content.Context) {
            val intent = android.content.Intent(context, WakeWordService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: android.content.Context) {
            val intent = android.content.Intent(context, WakeWordService::class.java)
            context.stopService(intent)
        }

    }
}
