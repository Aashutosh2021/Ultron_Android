package com.flexfly.ultron.ai

import android.app.KeyguardManager
import android.content.Context
import android.util.Base64
import android.util.Log
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.data.DatabaseHelper
import com.flexfly.ultron.i18n.INDIAN_LANGUAGES
import com.flexfly.ultron.model.LanguageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale

/**
 * WebSocket client for Google's Gemini Live `BidiGenerateContent` bidirectional
 * streaming API. Handles the full session: setup (system prompt + audio config),
 * streaming mic audio up, receiving audio + transcripts down, detecting `[TOOL:...]`
 * tags in the model's speech, dispatching them through [ToolHandler], and injecting
 * `[SYSTEM]` results back into the conversation.
 *
 * Recovered from `GeminiLiveSession.kt`. Callback fields ([onAudioReceived] etc.)
 * were function-type properties in the original. The tool-execution routine waits
 * for the phone to unlock (up to ~5s) before running lock-guarded tools.
 *
 * Cross-file refs — verify: [AppPreferences], [DatabaseHelper], [LanguageInfo],
 * [INDIAN_LANGUAGES] (from Languages.kt), [PromptBuilder], [ToolHandler],
 * [TranslationManager].
 */
class GeminiLiveSession(
    private val apiKey: String,
    private val db: DatabaseHelper,
    private val context: Context,
    private val onAudioReceived: (String) -> Unit,
    private val onTranscriptReceived: (String) -> Unit,
    private val onUserTranscriptReceived: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onVadStateChanged: (Boolean) -> Unit,
    private val onByeDetected: (() -> Unit)? = null,
    private val skipGreeting: Boolean = false,
    private val callMessage: String? = null,
    private val onToolStarted: ((String) -> Unit)? = null,
    private val onToolFinished: ((String) -> Unit)? = null,
) {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var webSocketClient: WebSocketClient? = null
    private var setupSent = false
    private var isGreetingTurn = true
    private var byeTriggered = false
    private var toolExecutedThisTurn = false

    private val currentAssistantResponse = StringBuilder()
    private val currentUserSpeech = StringBuilder()
    private val currentSessionTurns = ArrayList<Pair<String, String>>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect() {
        val uri = URI("$BASE_URL?key=$apiKey")
        _connectionState.value = ConnectionState.CONNECTING
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "✅ WebSocket opened")
                sendSetupMessage()
            }

            override fun onMessage(message: String?) {
                if (message != null) {
                    handleTextMessage(message)
                }
            }

            override fun onMessage(bytes: ByteBuffer?) {
                if (bytes != null) {
                    val bArr = ByteArray(bytes.remaining())
                    bytes.get(bArr)
                    val str = String(bArr, Charsets.UTF_8)
                    if (str.trimStart().startsWith("{")) {
                        handleTextMessage(str)
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                _connectionState.value = ConnectionState.DISCONNECTED
                onStatusChanged("❌ Disconnect ho gayi")
                setupSent = false
            }

            override fun onError(ex: Exception?) {
                _connectionState.value = ConnectionState.ERROR
                onStatusChanged("⚠️ Error: ${ex?.message}")
            }
        }
        webSocketClient = client
        client.connect()
    }

    private fun sendSetupMessage() {
        val appPreferences = AppPreferences(context)
        var language = appPreferences.language
        if (language.isBlank()) {
            language = "Hinglish"
        }
        Log.d(TAG, "📝 User language: $language")

        val systemPrompt: String = when {
            callMessage != null ->
                "You are Ultron, Boss's personal AI. An incoming call just arrived. Announce it straight, then act on Boss's answer.\nNo greeting, no intro. Just the call.\nTo pick up: [TOOL:call_accept]. To reject: [TOOL:call_reject].\nSay it once, listen for Boss's answer, take the action, then go quiet.\nDefault to Hinglish; match Boss if he speaks pure English or pure Hindi."
            skipGreeting ->
                "You are Ultron, Boss's personal AI. A notification just arrived on the phone. Announce it in ONE short, natural line — blunt and direct, no filler.\nNo greeting. No questions. Just say it.\nExample: \"Boss, WhatsApp pe Ankit ka message aaya hai.\"\nDefault to Hinglish; match Boss if he speaks pure English or pure Hindi."
            else -> PromptBuilder.buildSystemPrompt(
                db.getRecentMessages(40),
                currentSessionTurns,
                appPreferences.userName,
                appPreferences.language,
                appPreferences.lastIntruderDescription,
                appPreferences.getMemories(),
            )
        }

        val langLower = language.lowercase(Locale.ROOT).trim()
        val languageInfo = INDIAN_LANGUAGES.firstOrNull { it.name.lowercase(Locale.ROOT) == langLower }
        val motherTongueNote: String = when {
            langLower == "hinglish" -> ""
            langLower == "hindi" -> "\n\n🌐 YOUR MOTHER TONGUE: Pure Hindi (शुद्ध हिंदी). You prefer to speak in Hindi naturally, not Hinglish."
            languageInfo != null -> "\n\n🌐 YOUR MOTHER TONGUE: ${languageInfo.name} (${languageInfo.nativeName}). You prefer to speak in ${languageInfo.name} naturally."
            else -> "\n\n🌐 YOUR MOTHER TONGUE: $language. You prefer to speak in $language naturally."
        }
        val finalPrompt = systemPrompt + motherTongueNote
        Log.d(TAG, "✅ Prompt ready with mother tongue: $language")

        val setup = JSONObject().apply {
            put("model", "models/$MODEL")
            put("generation_config", JSONObject().apply {
                put("response_modalities", JSONArray().put("AUDIO"))
                put("thinking_config", JSONObject().put("thinking_budget", 0))
                put("speech_config", JSONObject().apply {
                    put("voice_config", JSONObject().apply {
                        put("prebuilt_voice_config", JSONObject().put("voice_name", "Algieba"))
                    })
                })
            })
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("system_instruction", JSONObject().apply {
                // RECOVERY NOTE: TypingAccessibilityService.EXTRA_TEXT == "text" (decompiler
                // substituted the constant for the literal; restored here and below).
                put("parts", JSONArray().put(JSONObject().put("text", finalPrompt)))
            })
        }
        val message = JSONObject().put("setup", setup)
        webSocketClient?.send(message.toString())
        setupSent = true
        Log.d(TAG, "✅ Setup message sent to Gemini")
    }

    fun sendAudioChunk(base64Audio: String) {
        val client = webSocketClient
        if (setupSent && client != null && client.isOpen) {
            val message = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    })
                })
            }
            try {
                webSocketClient?.send(message.toString())
            } catch (_: Exception) {
            }
        }
    }

    fun injectMessage(text: String) {
        val client = webSocketClient
        if (!setupSent || client == null || !client.isOpen) {
            return
        }
        var language = AppPreferences(context).language
        if (language.isBlank()) {
            language = "Hinglish"
        }
        try {
            val isSystemMessage = text.startsWith("[SYSTEM]") ||
                text.startsWith("[CALL_INCOMING]") ||
                text.startsWith("[REMINDER]")
            val langLower = language.lowercase(Locale.ROOT)
            val payload: String = if (!isSystemMessage && langLower != "hinglish" && langLower != "hindi") {
                runBlocking {
                    try {
                        TranslationManager.translateToUserLanguage(text, language)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Inject translation failed: ${e.message}")
                        text
                    }
                }
            } else {
                text
            }

            val message = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", payload)))
                    }))
                    put("turnComplete", true)
                })
            }
            webSocketClient?.send(message.toString())
            Log.d(TAG, "💉 Injected (original): $text")
            if (text != payload) {
                Log.d(TAG, "💉 Injected (translated): $payload")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inject error: ${e.message}")
        }
    }

    private fun handleTextMessage(message: String) {
        try {
            val json = JSONObject(message)
            if (json.has("error")) {
                Log.e(TAG, "❌ API Error: ${json.getJSONObject("error").optString("message", "Unknown")}")
                return
            }
            if (json.has("setupComplete")) {
                _connectionState.value = ConnectionState.CONNECTED
                onStatusChanged("✅ Ultron sun raha hai...")
                val callMsg = callMessage
                when {
                    callMsg != null -> sendCallTrigger(callMsg)
                    skipGreeting -> return
                    else -> sendGreetingTrigger()
                }
                return
            }

            val serverContent = json.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                onVadStateChanged(false)
                toolExecutedThisTurn = false
                currentAssistantResponse.clear()
                currentUserSpeech.clear()
                return
            }

            serverContent.optJSONObject("inputTranscription")?.let { inputTranscription ->
                val partial = inputTranscription.optString("text", "")
                if (partial.isNotEmpty()) {
                    currentUserSpeech.append(partial)
                    onUserTranscriptReceived(partial)
                    onVadStateChanged(true)
                    if (!isGreetingTurn && !byeTriggered) {
                        val spoken = currentUserSpeech.toString().lowercase(Locale.ROOT).trim()
                        val hitPhrase = BYE_PHRASES.any { spoken.contains(it) }
                        val hitExact = EXACT_BYE.any { spoken == it || spoken.endsWith(" $it") }
                        if (hitPhrase || hitExact) {
                            Log.d(TAG, "👋 Bye detected: '$spoken'")
                            byeTriggered = true
                            scope.launch(Dispatchers.Main) {
                                delay(2500L)
                                onByeDetected?.invoke()
                            }
                        }
                    }
                }
            }

            serverContent.optJSONObject("outputTranscription")?.let { outputTranscription ->
                val partial = outputTranscription.optString("text", "")
                if (partial.isNotEmpty()) {
                    currentAssistantResponse.append(partial)
                    onTranscriptReceived(partial)
                    if (!toolExecutedThisTurn) {
                        val tool = extractTool(currentAssistantResponse.toString())
                        if (tool != null) {
                            Log.d(TAG, "🔧 Tool found: $tool")
                            toolExecutedThisTurn = true
                            onToolStarted?.invoke(tool)
                            scope.launch(Dispatchers.Main) {
                                executeTool(tool)
                            }
                        }
                    }
                }
            }

            serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                    if (inlineData != null) {
                        val data = inlineData.optString("data")
                        if (data.isNotEmpty() && Base64.decode(data, 2).size >= 1000) {
                            onAudioReceived(data)
                        }
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                onVadStateChanged(false)
                val userText = currentUserSpeech.toString().trim()
                val assistantText = currentAssistantResponse.toString().trim()
                if (isGreetingTurn) {
                    isGreetingTurn = false
                } else if (!skipGreeting && userText.isNotEmpty() && assistantText.isNotEmpty()) {
                    val cleaned = cleanToolTag(assistantText)
                    db.saveMessage("user", userText)
                    db.saveMessage("assistant", cleaned)
                    currentSessionTurns.add("user" to userText)
                    currentSessionTurns.add("assistant" to cleaned)
                    while (currentSessionTurns.size > 20) {
                        currentSessionTurns.removeAt(0)
                    }
                }
                currentUserSpeech.clear()
                currentAssistantResponse.clear()
                toolExecutedThisTurn = false
                onStatusChanged("✅ Ultron sun raha hai...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    /**
     * Runs [toolName] through [ToolHandler] and injects the result. Tools not in
     * [BYPASS_LOCK_TOOLS] wait for the phone to unlock first (polling ~50×100ms).
     * Data tools (time/date/battery/...) inject their result directly; async tools
     * (weather/location/camera) inject nothing here — their result arrives separately.
     * Restored from the `handleTextMessage$2` suspend state machine.
     */
    private suspend fun executeTool(toolName: String) {
        try {
            val toolLower = toolName.trim().lowercase(Locale.ROOT)
            val bypass = BYPASS_LOCK_TOOLS.contains(toolLower) ||
                BYPASS_LOCK_TOOLS.any { toolLower.startsWith("$it:") }

            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked && !bypass) {
                Log.d(TAG, "🔒 Phone is locked. Waiting for unlock to execute tool: $toolName")
                injectMessage("[SYSTEM] Phone locked hai. Boss ko bolo: 'Boss, pehle phone unlock karo'.")
                var timedOut = false
                var i = 1
                // Poll for unlock up to 50 times (100ms each ≈ 5s), then time out.
                while (i < 51) {
                    delay(100L)
                    if (!keyguardManager.isKeyguardLocked) {
                        break
                    }
                    i++
                    if (i >= 51) {
                        timedOut = true
                    }
                }
                if (timedOut || keyguardManager.isKeyguardLocked) {
                    Log.w(TAG, "🔒 Timeout: Phone remained locked. Aborting tool: $toolName")
                    injectMessage("[SYSTEM] Timeout. Boss ne phone unlock nahi kiya, isliye task cancel kar diya.")
                    return
                }
                Log.d(TAG, "🔓 Phone unlocked! Proceeding with tool execution.")
                delay(500L)
            }

            val result = ToolHandler.handle(toolName, context)
            Log.d(TAG, "🔧 Tool result: $result")
            delay(1000L)

            val silent = SILENT_TOOLS.contains(toolLower) ||
                SILENT_PREFIXES.any { toolLower.startsWith(it) }

            when {
                // FIXED: was inverted — "nahi mila" results were entering the success tree.
                // Now negated: success results route to the main handling tree, failures to else.
                !(result.contains("contacts mein nahi mila") || result.contains("nahi mila")) -> {
                    when {
                        result.startsWith("[MULTI_WHATSAPP_CONTACT]") ->
                            injectMessage("[SYSTEM] Multiple contacts mile: ${result.removePrefix("[MULTI_WHATSAPP_CONTACT]").trim()} — boss se poochho konse ko WhatsApp message bheju?")
                        result.startsWith("[MULTI_WHATSAPP_CALL]") ->
                            injectMessage("[SYSTEM] Multiple contacts mile: ${result.removePrefix("[MULTI_WHATSAPP_CALL]").trim()} — boss se poochho konse ko WhatsApp voice call karun?")
                        result.startsWith("[MULTI_WHATSAPP_VIDEO]") ->
                            injectMessage("[SYSTEM] Multiple contacts mile: ${result.removePrefix("[MULTI_WHATSAPP_VIDEO]").trim()} — boss se poochho konse ko WhatsApp video call karun?")
                        result.startsWith("[MULTI_SMS_CONTACT]") ->
                            injectMessage("[SYSTEM] Multiple contacts mile: ${result.removePrefix("[MULTI_SMS_CONTACT]").trim()} — boss se poochho konse ko SMS bheju?")
                        result.startsWith("[MULTI_CONTACT]") ->
                            injectMessage("[SYSTEM] Multiple contacts mile: ${result.removePrefix("[MULTI_CONTACT]").trim()} — boss se poochho konse ko call karun?")
                        toolLower == "get_time" || toolLower == "time" || toolLower == "time_batao" ->
                            injectMessage("[SYSTEM] $result")
                        toolLower == "get_date" || toolLower == "date" || toolLower == "date_batao" ->
                            injectMessage("[SYSTEM] $result")
                        toolLower == "get_day" || toolLower == "day" ->
                            injectMessage("[SYSTEM] $result")
                        toolLower == "get_battery" || toolLower == "battery" || toolLower == "kitni_battery" ->
                            injectMessage("[SYSTEM] $result")
                        toolLower == "get_datetime" || toolLower == "datetime" ->
                            injectMessage("[SYSTEM] $result")
                        toolLower == "get_volume" || toolLower == "volume_status" ->
                            injectMessage("[SYSTEM] $result")
                        toolLower == "get_brightness" || toolLower == "brightness_kitni" ->
                            injectMessage("[SYSTEM] $result")
                        toolLower == "get_location" || toolLower == "location" || toolLower == "meri_location" || toolLower == "location_batao" || toolLower == "main_kahan_hun" ->
                            Log.d(TAG, "📍 Location async — result will be injected separately")
                        toolLower == "get_weather" || toolLower == "weather" || toolLower == "mausam" ->
                            Log.d(TAG, "🌤 Weather async — result will be injected separately")
                        isCameraVisionTool(toolLower) ->
                            Log.d(TAG, "📷 Camera Vision async — result will be injected separately")
                        else -> {
                            if (isSuccessResult(result)) {
                                if (silent) {
                                    Log.d(TAG, "🔇 Silent tool done: $result")
                                } else {
                                    injectMessage("[SYSTEM] Kaam ho gaya. Boss ko ek chhoti natural line mein confirm karo: '$result'")
                                }
                            } else if (silent) {
                                injectMessage("[SYSTEM] Kaam nahi hua: $result")
                            } else {
                                injectMessage("[SYSTEM] Kaam nahi ho paya. Boss ko exactly batao: '$result'")
                            }
                        }
                    }
                }
                else ->
                    injectMessage("[SYSTEM] Contact nahi mila. boss ko batao: '$result'")
            }
        } finally {
            onToolFinished?.invoke(toolName)
        }
    }

    private fun isCameraVisionTool(toolLower: String): Boolean =
        toolLower == "analyze_scene" || toolLower.startsWith("analyze_scene:") ||
            toolLower == "selfie_analyze_scene" || toolLower.startsWith("selfie_analyze_scene:") ||
            toolLower == "describe_scene" || toolLower.startsWith("describe_scene:") ||
            toolLower == "what_is_this" || toolLower.startsWith("what_is_this:") ||
            toolLower == "take_photo" || toolLower == "selfie" || toolLower == "photo" ||
            toolLower == "click_photo" || toolLower.startsWith("photo_countdown:") ||
            toolLower.startsWith("selfie_countdown:") ||
            toolLower == "photo_analyze" || toolLower.startsWith("photo_analyze:") ||
            toolLower == "selfie_analyze" || toolLower.startsWith("selfie_analyze:") ||
            toolLower == "analyze_last_photo" || toolLower.startsWith("analyze_last_photo:") ||
            toolLower == "analyze_screen" || toolLower.startsWith("analyze_screen:")

    private fun isSuccessResult(result: String): Boolean =
        !result.isBlank() &&
            !result.contains("nahi ho paya", ignoreCase = true) &&
            !result.contains("fail", ignoreCase = true) &&
            !result.contains("error", ignoreCase = true) &&
            !result.contains("nahi mili", ignoreCase = true) &&
            !result.contains("nahi hua", ignoreCase = true) &&
            !result.contains("nahi kar", ignoreCase = true) &&
            !result.contains("nahi chala", ignoreCase = true) &&
            !result.contains("problem", ignoreCase = true) &&
            !result.contains("off hai", ignoreCase = true) &&
            !result.contains("settings mein off", ignoreCase = true) &&
            !result.contains("permission", ignoreCase = true) &&
            !result.contains("not available", ignoreCase = true) &&
            !result.contains("unavailable", ignoreCase = true)

    private fun extractTool(text: String): String? {
        val match = TOOL_REGEX.find(text) ?: return null
        return match.groupValues[1].trim()
    }

    private fun cleanToolTag(text: String): String =
        TOOL_REGEX.replace(text, "").trim()

    private fun sendCallTrigger(message: String) {
        var language = AppPreferences(context).language
        if (language.isBlank()) {
            language = "Hinglish"
        }
        val langLower = language.lowercase(Locale.ROOT)
        val suffix = if (langLower == "hinglish" || langLower == "hindi") "" else " [Respond in your mother tongue - $language]"
        var payload = message + suffix
        if (langLower != "hinglish" && langLower != "hindi") {
            payload = runBlocking {
                try {
                    TranslationManager.translateToUserLanguage(payload, language)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Call trigger translation failed: ${e.message}")
                    payload
                }
            }
        }
        val json = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", payload)))
                }))
                put("turnComplete", true)
            })
        }
        try {
            webSocketClient?.send(json.toString())
            Log.d(TAG, "📞 Call trigger sent: $payload")
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendCallTrigger: ${e.message}")
        }
    }

    private fun sendGreetingTrigger() {
        var language = AppPreferences(context).language
        if (language.isBlank()) {
            language = "Hinglish"
        }
        val langLower = language.lowercase(Locale.ROOT)
        val hinglishGreeting = "[Session start — give ONE short warm greeting in your mother tongue language. Speak slowly and clearly. Just 2-3 words. Then wait silently.]"
        var payload = hinglishGreeting
        if (langLower != "hinglish" && langLower != "hindi") {
            payload = runBlocking {
                try {
                    TranslationManager.translateToUserLanguage(hinglishGreeting, language)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Greeting translation failed: ${e.message}")
                    hinglishGreeting
                }
            }
        }
        val json = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", payload)))
                }))
                put("turnComplete", true)
            })
        }
        try {
            webSocketClient?.send(json.toString())
            Log.d(TAG, "👋 Greeting trigger sent")
        } catch (_: Exception) {
        }
    }

    fun disconnect() {
        try {
            webSocketClient?.close()
        } catch (_: Exception) {
        }
        scope.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        setupSent = false
    }

    companion object {
        private const val BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val TAG = "GeminiLiveSession"
        private const val TOOL_TAG = "[TOOL:"
        private const val TOOL_TAG_END = "]"

        private val TOOL_REGEX = Regex("[\\[\\(]\\s*[tT][oO][oO][lL]\\s*[:=-]\\s*([^\\]\\)]+)[\\]\\)]")

        private val BYE_PHRASES = listOf(
            "bye bye", "bye mj", "goodbye", "good bye", "alvida",
            "ok bye", "okay bye", "tata", "bas karo", "that's all", "thats all",
        )
        private val EXACT_BYE = listOf("bye", "bye.")

        // RECOVERY NOTE: NotificationCompat.CATEGORY_CALL == "call" (decompiler substituted
        // the constant for the literal in this set).
        private val BYPASS_LOCK_TOOLS = setOf(
            "get_time", "get_date", "get_day", "get_battery", "get_datetime",
            "time", "date", "day", "battery", "time_date", "datetime",
            "flashlight_on", "flashlight_off", "flashlight_toggle",
            "call_accept", "receive_call", "call_receive", "answer_call", "accept_call",
            "pick_call", "uthao", "utha_lo", "pickup",
            "call_reject", "reject_call", "decline_call", "cut_call", "end_call",
            "hangup", "hang_up", "mat_uthao", "kaat_de", "reject", "call",
            "select_contact",
            "volume_up", "volume_down", "volume_max", "volume_mute", "volume_unmute",
            "silent_mode", "vibrate_mode", "normal_mode", "volume_set", "get_volume",
            "brightness_up", "brightness_down", "brightness_max", "brightness_min",
            "brightness_auto", "brightness_set", "get_brightness",
            "touch_guard_on", "touch_guard_off", "anti_theft_on", "anti_theft_off",
            "touch_guard", "anti_theft", "save_memory", "clear_memories",
        )

        private val SILENT_TOOLS = setOf(
            "go_home", "flashlight_on", "flashlight_off", "flashlight_toggle",
            "take_photo", "click_photo", "photo", "selfie", "open_camera", "camera",
            "camera_open", "click_shutter", "shutter", "just_click", "click_now",
            "flip_camera", "switch_camera", "camera_flip", "camera_switch",
            "phone_off", "screen_off", "lock_screen", "power_menu", "power_dialog",
            "volume_up", "volume_down", "volume_max", "full_volume", "volume_full",
            "volume_mute", "mute", "volume_unmute", "unmute", "silent_mode",
            "phone_silent", "vibrate_mode", "phone_vibrate", "normal_mode",
            "phone_normal", "sound_on",
            "brightness_up", "brightness_down", "brightness_max", "full_brightness",
            "brightness_min", "brightness_low", "brightness_auto", "auto_brightness",
            "net_on", "wifi_on", "internet_on", "wifi_toggle", "net_off", "wifi_off",
            "internet_off", "data_on", "mobile_data_on", "data_off", "mobile_data_off",
            "data_toggle", "bluetooth_on", "bt_on", "bluetooth_off", "bt_off",
            "bluetooth_toggle", "bluetooth", "hotspot_on", "hotspot_off",
            "hotspot_toggle", "hotspot", "airplane_on", "airplane_off",
            "airplane_toggle", "flight_mode_on", "flight_mode_off", "spotify_play",
            "call_accept", "receive_call", "answer_call", "call_reject",
            "reject_call", "decline_call",
        )

        private val SILENT_PREFIXES = listOf(
            "open_app:", "open_app ", "spotify_play:", "volume_set:", "brightness_set:",
            "reminder:", "net:", "toggle_network:", "whatsapp:", "whatsapp_select:",
            "whatsapp_call:", "whatsapp_video:", "whatsapp_call_select:",
            "whatsapp_video_select:", "sms:", "sms_select:", "maps_nav:", "maps_show:",
            "maps_search:", "maps_nearby:", "nearby:", "youtube:", "yt:",
            "web_search:", "search_web:",
        )
    }
}
