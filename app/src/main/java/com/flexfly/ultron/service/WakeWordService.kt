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
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
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
    private var isVoskListening = false
    private var lastActivityTime = 0L
    private var model: Model? = null
    private var notifAudioPlayer: AudioPlayerManager? = null
    private var notifGeminiSession: GeminiLiveSession? = null
    private var notifSessionActive = false
    private var overlayView: View? = null
    private var pendingCallNumber: String? = null
    private var pendingCallerName: String? = null
    private lateinit var prefs: AppPreferences
    private var recognizer: Recognizer? = null
    private var silenceJob: Job? = null
    // Guarantees the ring volume is restored after a call announcement even if the
    // announce session never fires its "done speaking" event (bad key, no network,
    // dropped session) — otherwise the phone stays silenced/vibrate for the whole call.
    private var ringRestoreJob: Job? = null
    private var voskAudioRecord: AudioRecord? = null
    private var wakeWordJustDetected = false
    private var windowManager: WindowManager? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

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

    fun injectReminderMessage(message: String) {
        mainScope.launch {
            var text = message
            val language = prefs.language.ifBlank { "Hinglish" }
            val lower = language.lowercase(Locale.ROOT)
            if (lower != "hinglish" && lower != "hindi") {
                text = try {
                    runBlocking { TranslationManager.translateToUserLanguage(message, language) }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Reminder translation failed: ${e.message}")
                    message
                }
            }
            val session = geminiSession
            if (session != null) {
                if (!isUltronCurrentlySpeaking && !isToolExecuting) {
                    Log.d(TAG, "⏰ Injecting reminder to active session")
                    session.injectMessage("[SYSTEM] $text. Boss ko clearly ek baar batao.")
                } else {
                    Log.d(TAG, "⏰ Ultron busy — queuing reminder")
                    pendingNotifications.add(Triple("__REMINDER__", text, ""))
                }
            } else {
                Log.d(TAG, "⏰ Session inactive — starting one-shot session")
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
            val text = "[CALL_INCOMING] $message Boss ka jawab suno — 'haan', 'uthao', 'receive' bole toh [TOOL:receive_call] use karo. 'nahi', 'mat uthao', 'reject' bole toh [TOOL:reject_call] use karo. Sirf ek baar poochho."
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
            val text = "[CALL_INCOMING] $message Boss ka jawab suno — 'haan' ya 'uthao' bole toh [TOOL:receive_call] use karo. 'nahi' ya 'reject' bole toh [TOOL:reject_call] use karo."
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
        Log.d(TAG, "✅ Battery monitor started")
        createNotificationChannel()
        startForegroundNotification()
        setupNotificationListener()
        setupChargingReceiver()
        setupCallReceiver()
        scope.launch {
            loadVoskModel()
        }
    }

    private fun acquireWakeLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (cpuWakeLock == null) {
                cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ultron:CpuWakeLock").apply {
                    acquire(120000L) // 2 minute timeout maximum
                }
                Log.d(TAG, "🔑 CPU WakeLock acquired")
            }

            @Suppress("DEPRECATION")
            val screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Ultron:ScreenWakeLock"
            )
            screenWakeLock.acquire(10000L) // Wake the screen for 10 seconds
            Log.d(TAG, "💡 Screen WakeLock acquired (waking screen)")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake locks: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
                Log.d(TAG, "🔒 CPU WakeLock released")
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
        Log.d(TAG, "✅ Battery monitor stopped")
        try {
            unregisterReceiver(chargingReceiver)
        } catch (unused: Exception) {
        }
        try {
            unregisterReceiver(callReceiver)
        } catch (unused: Exception) {
        }
        stopVoskListening()
        callAudioManager?.destroy()
        callAudioManager = null
        dismissOverlay()
        cleanupNotifSession()
        pendingNotifications.clear()
        recognizer?.close()
        model?.close()
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
                Log.d(TAG, "📞 Call ended — full cleanup")
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
                if (geminiSession == null && !isVoskListening) {
                    Log.d(TAG, "🔄 Restarting Vosk after call end")
                    startVoskListening()
                }
            }
        }
        registerReceiver(callReceiver, IntentFilter("android.intent.action.PHONE_STATE"))
        Log.d(TAG, "📞 CallReceiver registered")
    }

    private fun silenceRingVolume() {
        try {
            val audioManager = getSystemService("audio") as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            Log.d(TAG, "🔇 Ring volume → 0")
        } catch (e: Exception) {
            Log.e(TAG, "❌ silenceRingVolume: ${e.message}")
        }
    }

    private fun restoreRingVolume() {
        ringRestoreJob?.cancel()
        ringRestoreJob = null
        try {
            val audioManager = getSystemService("audio") as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            Log.d(TAG, "🔔 Ring volume → max ($maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ restoreRingVolume: ${e.message}")
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
            Log.d(TAG, "🔔 Guaranteed ring restore (safety net)")
            try {
                val audioManager = getSystemService("audio") as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            } catch (e: Exception) {
                Log.e(TAG, "❌ scheduleRingRestore: ${e.message}")
            }
        }
    }

    private fun handleIncomingCall(name: String, number: String) {
        if (!prefs.toolCallAnnounce) {
            Log.d(TAG, "📞 Call announce disabled — ignoring")
            return
        }
        val callControlEnabled = prefs.callControlEnabled
        Log.d(TAG, "📞 Incoming: '$name' | control=$callControlEnabled")
        silenceRingVolume()
        // Guarantee the ring comes back even if the announcement session fails.
        // Announce-only: ring back shortly after the ~2-3s announcement.
        // Call-control: keep silence longer so Boss can hear+answer (matches the
        // 30s hard timeout below), then force the ring back regardless.
        scheduleRingRestore(if (callControlEnabled) 32000L else 7000L)
        stopVoskListening()
        val language = prefs.language.ifBlank { "Hinglish" }
        var announcement = "$name ka call aa raha hai"
        val lower = language.lowercase(Locale.ROOT)
        if (lower != "hinglish" && lower != "hindi") {
            try {
                announcement = runBlocking {
                    TranslationManager.translateToUserLanguage(announcement, language)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Call announcement translation failed: ${e.message}")
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
            "[SYSTEM] $announcement. Boss se poochho: 'Boss, $name ka call hai — uthana hai?' Haan → [TOOL:call_accept]. Nahi → [TOOL:call_reject]."
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
        // A live/arming main session owns the assistant audio channel — don't
        // stack a call announcement session on top of it.
        if (notifSessionActive || geminiSession != null || wakeWordJustDetected) {
            Log.d(TAG, "⚠️ startCallGemini skipped — session already active")
            return
        }
        notifSessionActive = true
        Log.d(TAG, "📞 Starting call session: $callerName | control=$callControl")
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
                    Log.w(TAG, "⏱ User didn't respond — restoring ring & cleaning up")
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

        STEP 1: Clearly bolo — "Boss, $callerName ka call hai — uthana hai?"
        STEP 2: Phir chup ho jao aur user ka jawab suno.
        STEP 3: User jawab dega:
           - Agar user bole 'haan', 'ha', 'uthao', 'receive', 'utha le', 'pick up', 'yes', 'le lo', 'haan haan' →
             Sirf ek short word bolo "Theek hai" aur turant [TOOL:call_accept] use karo.
           - Agar user bole 'nahi', 'na', 'mat uthao', 'reject', 'cut', 'kaat de', 'no', 'chhod', 'reject kar de' →
             Sirf ek short word bolo "Ok" aur turant [TOOL:call_reject] use karo.

        VERY IMPORTANT: Tool tag ZAROORI hai — [TOOL:call_accept] ya [TOOL:call_reject] — bina iske action nahi hoga.
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
                Log.d(TAG, "👂 User said: $chunk")
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
                Log.d(TAG, "🔧 Tool started in call session: $toolName")
                callAudioManager?.isMuted = true
            },
            onToolFinished = null,
        )
        scope.launch {
            val connectionState = notifGeminiSession?.connectionState ?: return@launch
            connectionState.collect { state ->
                when (state) {
                    GeminiLiveSession.ConnectionState.CONNECTED -> {
                        Log.d(TAG, "✅ Call session connected")
                        if (callControl) {
                            mainScope.launch {
                                delay(30000L)
                                if (notifSessionActive && !actionCompleted) {
                                    Log.d(TAG, "⏱ Hard call timeout — cleanup")
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
                delay(1500L)
                Log.d(TAG, "✅ Call action done — cleanup & restart Vosk")
                restoreRingVolume()
                callAudioManager?.destroy()
                callAudioManager = null
                cleanupNotifSession()
            }
            return
        }
        setAnnouncementDone(true)
        Log.d(TAG, "✅ Ultron announcement done")
        if (!callControl) {
            delay(500L)
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
                Log.d(TAG, "🎤 Call mic active — waiting for user response")
            } else {
                Log.e(TAG, "❌ Call recording start failed")
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
        Log.d(TAG, "🔌 ChargingReceiver registered")
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
            Log.e(TAG, "❌ Event translation failed: ${e.message}")
            message
        }
    }

    private fun handleEventMessage(tag: String, message: String) {
        Log.d(TAG, "📣 Event [$tag]: $message")
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
                    Log.d(TAG, "⚠️ Notifications disabled in settings — ignoring")
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
            Log.e(TAG, "❌ Notification translation failed: ${e.message}")
            message
        }
    }

    private fun startOneShot(message: String, isReminder: Boolean) {
        // Don't announce over an active/arming main conversation — that's the
        // dual-voice case. wakeWordJustDetected covers the 300ms gap between the
        // wake word firing and geminiSession being created.
        if (notifSessionActive || geminiSession != null || wakeWordJustDetected) {
            Log.d(TAG, "⚠️ startOneShot skipped — main/one-shot session active")
            return
        }
        notifSessionActive = true
        Log.d(TAG, "🚀 One-shot session: $message")
        val player = AudioPlayerManager()
        player.initialize()
        notifAudioPlayer = player
        notifGeminiSession = GeminiLiveSession(
            apiKey = prefs.googleApiKey,
            db = db,
            context = this,
            onAudioReceived = { audio ->
                notifAudioPlayer?.playAudioChunk(audio)
            },
            onTranscriptReceived = { },
            onUserTranscriptReceived = { },
            onStatusChanged = { },
            onVadStateChanged = { },
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
                            text = "[REMINDER] $text — Boss ko clearly aur warmly ek baar bolna, jaise 'Boss, yaad hai na, $text'"
                        }
                        notifGeminiSession?.injectMessage(text)
                        delay(500L)
                        mainScope.launch { cleanupNotifSession() }
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
        // Synchronous, no relaunch — used when the main session is taking over.
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
            Log.d(TAG, "🧹 Cleaning up one-shot session")
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
                Log.d(TAG, "🔄 Restarting Vosk after session cleanup")
                startVoskListening()
            }
        }
    }

    private fun loadVoskModel() {
        try {
            val dir = File(filesDir, ASSETS_MODEL_DIR)
            val existing = dir.listFiles()
            if (!dir.exists() || existing == null || existing.isEmpty()) {
                copyAssetFolder(ASSETS_MODEL_DIR, dir)
            }
            model = Model(dir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            Log.d(TAG, "✅ Vosk model loaded")
            mainScope.launch {
                startVoskListening()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model load failed: ${e.message}")
        }
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val list = assets.list(assetPath) ?: return
        for (name in list) {
            val childAsset = "$assetPath/$name"
            val childFile = File(destDir, name)
            val subList = assets.list(childAsset)
            if (subList == null || subList.isEmpty()) {
                copyAssetFile(childAsset, childFile)
            } else {
                copyAssetFolder(childAsset, childFile)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        if (destFile.exists()) {
            return
        }
        try {
            assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (unused: Exception) {
        }
    }

    private fun startVoskListening() {
        if (isVoskListening || wakeWordJustDetected || model == null || recognizer == null) {
            return
        }
        if (notifSessionActive) {
            Log.d(TAG, "⚠️ Notif session active — skipping Vosk start")
            return
        }
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            ),
            4096
        )
        var attempt = 0
        while (attempt < 3) {
            try {
                // RECOVERY NOTE: AudioSource 6 = VOICE_RECOGNITION.
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 4
                )
                voskAudioRecord = record
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vosk init error (attempt ${attempt + 1}): ${e.message}")
            }
            voskAudioRecord?.release()
            voskAudioRecord = null
            attempt++
            Thread.sleep(150L)
        }
        val record = voskAudioRecord
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ Vosk AudioRecord init failed after retries")
            return
        }
        try {
            voskAudioRecord?.startRecording()
            isVoskListening = true
            Log.d(TAG, "✅ Vosk listening started")
            scope.launch(Dispatchers.IO) {
                val samples = ShortArray(bufferSize)
                while (isVoskListening && isActive) {
                    val audioRecord = voskAudioRecord ?: break
                    val read = audioRecord.read(samples, 0, bufferSize)
                    if (read <= 0 || wakeWordJustDetected) {
                        continue
                    }
                    val byteCount = read * 2
                    val bytes = ByteArray(byteCount)
                    for (i in 0 until read) {
                        val s = samples[i].toInt()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    val rec = recognizer ?: continue
                    if (rec.acceptWaveForm(bytes, byteCount)) {
                        val text = extractText(rec.result)
                        if (text.isNotBlank()) {
                            Log.d(TAG, "📝 Vosk: $text")
                            if (isWakeWord(text)) {
                                mainScope.launch { onWakeWordDetected() }
                            }
                        }
                    } else {
                        val partial = extractPartialText(rec.partialResult)
                        if (partial.isNotBlank() && !wakeWordJustDetected && isWakeWord(partial)) {
                            mainScope.launch { onWakeWordDetected() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk startRecording failed: ${e.message}")
            voskAudioRecord?.release()
            voskAudioRecord = null
        }
    }

    private fun stopVoskListening() {
        isVoskListening = false
        try {
            voskAudioRecord?.stop()
            voskAudioRecord?.release()
            voskAudioRecord = null
        } catch (unused: Exception) {
        }
    }

    private fun extractText(json: String): String {
        return try {
            val keyIndex = json.indexOf("\"text\"")
            if (keyIndex == -1) {
                return ""
            }
            val colon = json.indexOf(":", keyIndex)
            val start = json.indexOf("\"", colon + 1)
            val valueStart = start + 1
            val end = json.indexOf("\"", valueStart)
            if (start != -1 && end != -1) {
                json.substring(valueStart, end).trim()
            } else {
                ""
            }
        } catch (unused: Exception) {
            ""
        }
    }

    private fun extractPartialText(json: String): String {
        return try {
            val keyIndex = json.indexOf("\"partial\"")
            if (keyIndex == -1) {
                return ""
            }
            val colon = json.indexOf(":", keyIndex)
            val start = json.indexOf("\"", colon + 1)
            val valueStart = start + 1
            val end = json.indexOf("\"", valueStart)
            if (start != -1 && end != -1) {
                json.substring(valueStart, end).trim()
            } else {
                ""
            }
        } catch (unused: Exception) {
            ""
        }
    }

    private fun isWakeWord(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val lower = text.lowercase(Locale.ROOT).trim()
        if (WAKE_PHRASES.any { lower.contains(it) }) {
            return true
        }
        return (lower.contains("hello") || lower.contains("hey") || lower.contains("hi ")) &&
            (lower.contains("ultron") || lower.contains("altron") || lower.contains("eltron") ||
                lower.contains("all tron") || lower.contains("i will done") || lower.contains("old tron") ||
                lower.contains("drawn") || lower.contains("drown"))
    }

    private fun onWakeWordDetected() {
        if (wakeWordJustDetected) {
            return
        }
        wakeWordJustDetected = true
        Log.d(TAG, "🔥 WAKE WORD DETECTED")
        acquireWakeLocks()
        // Main session is taking over. Drop any queued one-shots and tear down the
        // active one SYNCHRONOUSLY here — using async cleanupNotifSession() would
        // let a queued notification re-spawn a one-shot on top of startGemini(),
        // producing two voices at once.
        pendingNotifications.clear()
        teardownNotifSession()
        stopVoskListening()
        mainScope.launch {
            delay(300L)
            showOverlay()
            startGemini()
        }
    }

    fun onByeWordDetected() {
        Log.d(TAG, "👋 BYE DETECTED")
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
            Log.d(TAG, "✅ Overlay shown")
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
            recognizer?.reset()
            startVoskListening()
        }
    }

    private fun startGemini() {
        // Never run two main sessions, and never run alongside a one-shot — either
        // would put two AudioTracks on the assistant channel simultaneously.
        if (geminiSession != null) {
            Log.w(TAG, "startGemini ignored — main session already active")
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
                geminiAudioRecorder?.isMuted = true
                isUltronSpeakingState.value = true
                isUltronCurrentlySpeaking = true
                audioPlayer?.playAudioChunk(audio)
                resetSilenceTimer()
                lastActivityTime = System.currentTimeMillis()
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
                    Log.d(TAG, "🔧 Tool starting: $toolName")
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

    private suspend fun onGeminiVadStateChanged(speaking: Boolean) {
        isUserSpeakingState.value = speaking
        if (!speaking) {
            isUltronCurrentlySpeaking = false
            isUltronSpeakingState.value = false
            geminiAudioRecorder?.isMuted = false
            if (!isToolExecuting) {
                startSilenceTimer()
            } else {
                Log.d(TAG, "⏸️ Timer paused - tool still executing")
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
        } else {
            resetSilenceTimer()
            lastActivityTime = System.currentTimeMillis()
        }
    }

    private suspend fun onToolFinished(toolName: String) {
        Log.d(TAG, "✅ Tool completed: $toolName")
        isToolExecuting = false
        lastActivityTime = System.currentTimeMillis()
        if (!isUltronCurrentlySpeaking && !isUltronSpeakingState.value) {
            delay(500L)
            startSilenceTimer()
            Log.d(TAG, "▶️ Timer resumed after tool completion")
        } else {
            Log.d(TAG, "⏸️ Timer stays paused - Ultron still speaking")
        }
    }

    private fun startSilenceTimer() {
        silenceJob?.cancel()
        if (isToolExecuting || isUltronCurrentlySpeaking || isUltronSpeakingState.value) {
            Log.d(TAG, "⏸️ Timer not started - activity ongoing")
            return
        }
        Log.d(TAG, "⏱️ Silence timer started (${SILENCE_TIMEOUT_MS / 1000}s)")
        silenceJob = mainScope.launch {
            delay(SILENCE_TIMEOUT_MS)
            val idle = System.currentTimeMillis() - lastActivityTime
            if (!isToolExecuting && !isUltronCurrentlySpeaking && !isUltronSpeakingState.value) {
                if (idle < SILENCE_TIMEOUT_MS) {
                    Log.d(TAG, "⏸️ Recent activity detected - restarting timer")
                    startSilenceTimer()
                } else {
                    Log.d(TAG, "⏰ Silence timeout - dismissing")
                    onByeWordDetected()
                }
            } else {
                Log.d(TAG, "⏸️ Timer expired but activity detected - restarting")
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
        val WAKE_PHRASES: List<String> = listOf("ultron", "hello ultron", "hey ultron", "i will done", "all tron", "altron", "eltron", "drawn", "drown", "i was drawn", "i'll bet on", "i'll on")
        var ghostPointerCoordinates: MutableState<PointF?> = mutableStateOf(null)

        fun getActiveInstance(): WakeWordService? = activeInstance
    }
}
