package com.flexfly.ultron.ai.conversation

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

/**
 * Real [ConversationProvider] for the Gemini Live BidiGenerateContent API over a WebSocket.
 *
 * This reuses the SAME connection approach proven by the app's legacy `GeminiLiveSession`
 * (the `org.java-websocket` client, the same endpoint and setup handshake, PCM-in / PCM-out),
 * but scoped strictly to the [ConversationProvider] contract: it streams audio and emits
 * [ConversationEvent]s only. It deliberately does NOT touch the database, the `[TOOL:]`
 * grammar, or the legacy audio player — tool execution and memory are owned by the new
 * Brain/Tool/Memory phases, so pulling the legacy tool loop in here would double-execute.
 *
 * Falls back to [MockConversationProvider] only when no API key is configured, so debug/dev
 * builds without credentials still exercise the pipeline.
 */
class GeminiLiveProvider(
    private val config: ConversationConfig = ConversationConfig(),
) : ConversationProvider {

    companion object {
        private const val TAG = "GeminiLiveProvider"
        private const val MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
    }

    override val providerName: String = "Gemini Live (v1beta)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Disconnected)
    override val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow(ConversationMetrics(providerName = providerName))
    override val metrics: StateFlow<ConversationMetrics> = _metrics.asStateFlow()

    private var webSocket: WebSocketClient? = null
    private var setupSent = false
    private var currentSessionId: String = ""
    private var mockFallback: MockConversationProvider? = null

    // Reconnection bookkeeping (bounded retries, real backoff — genuine reconnection logic).
    private var reconnectAttempts = 0
    private var intentionalDisconnect = false

    override suspend fun initialize() {
        Log.d(TAG, "🟢 Initializing GeminiLiveProvider (endpoint: ${config.endpointUrl})")
        updateState(ConversationState.Initializing)
        updateState(ConversationState.Disconnected)
    }

    override suspend fun connect(sessionId: String) {
        if (_state.value is ConversationState.Connecting || _state.value is ConversationState.Connected || _state.value is ConversationState.StreamingInput) {
            Log.w(TAG, "⚠️ Gemini Live Provider already connected/connecting (${_state.value.javaClass.simpleName}). Ignoring duplicate connect().")
            return
        }
        currentSessionId = sessionId
        intentionalDisconnect = false

        if (config.apiKey.isBlank()) {
            Log.w(TAG, "⚠️ No Gemini API key — using MockConversationProvider fallback.")
            val mock = MockConversationProvider(config)
            mockFallback = mock
            scope.launch { mock.events.collect { _events.emit(it) } }
            mock.connect(sessionId)
            updateState(ConversationState.Connected)
            return
        }

        openSocket(sessionId)
    }

    private fun openSocket(sessionId: String) {
        updateState(ConversationState.Connecting)
        val uri = URI("${config.endpointUrl}?key=${config.apiKey}")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "✅ WebSocket open")
                reconnectAttempts = 0
                sendSetup()
            }

            override fun onMessage(message: String?) {
                if (message != null) handleMessage(message)
            }

            override fun onMessage(bytes: ByteBuffer?) {
                if (bytes != null) {
                    val arr = ByteArray(bytes.remaining())
                    bytes.get(arr)
                    val str = String(arr, Charsets.UTF_8)
                    if (str.trimStart().startsWith("{")) handleMessage(str)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "🔌 WebSocket closed ($code): $reason")
                setupSent = false
                scope.launch {
                    _events.emit(ConversationEvent.Disconnected(providerName))
                    if (!intentionalDisconnect) attemptReconnect(sessionId)
                    else updateState(ConversationState.Disconnected)
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "❌ WebSocket error: ${ex?.message}")
                scope.launch { _events.emit(ConversationEvent.ProviderError(ex?.message ?: "unknown", ex)) }
            }
        }
        webSocket = client
        try {
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "connect() failed: ${e.message}", e)
            scope.launch { attemptReconnect(sessionId) }
        }
    }

    /** Bounded exponential-ish reconnection using the configured retry count / delay. */
    private suspend fun attemptReconnect(sessionId: String) {
        if (intentionalDisconnect) return
        if (reconnectAttempts >= config.retryCount) {
            Log.w(TAG, "⚠️ Reconnect gave up after ${config.retryCount} attempts")
            updateState(ConversationState.Error("Reconnect failed after ${config.retryCount} attempts"))
            return
        }
        reconnectAttempts++
        _events.emit(ConversationEvent.Reconnecting(reconnectAttempts))
        updateState(ConversationState.Reconnecting)
        delay(config.reconnectDelayMs * reconnectAttempts)
        Log.d(TAG, "🔄 Reconnect attempt $reconnectAttempts")
        openSocket(sessionId)
    }

    private fun sendSetup() {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$MODEL")
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
        webSocket?.send(setup.toString())
        setupSent = true
        Log.d(TAG, "✅ Setup sent")
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            if (json.has("setupComplete")) {
                updateState(ConversationState.Connected)
                scope.launch {
                    _events.emit(ConversationEvent.Connected(providerName))
                    _metrics.value = _metrics.value.copy(sessionId = currentSessionId, connectionTimeMs = System.currentTimeMillis())
                }
                return
            }
            val serverContent = json.optJSONObject("serverContent") ?: return

            // Output audio → PartialResponse(data=...) so the service can play it.
            serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data")
                    if (data.isNotEmpty()) {
                        val pcm = Base64.decode(data, Base64.NO_WRAP)
                        scope.launch { _events.emit(ConversationEvent.PartialResponse(data = pcm)) }
                    }
                }
            }
            // Output transcript → FinalResponse on turn complete.
            serverContent.optJSONObject("outputTranscription")?.optString("text")?.let { text ->
                if (text.isNotEmpty()) scope.launch { _events.emit(ConversationEvent.PartialResponse(text = text)) }
            }
            if (serverContent.optBoolean("turnComplete", false)) {
                scope.launch { _events.emit(ConversationEvent.FinalResponse("")) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message}")
        }
    }

    override suspend fun sendAudio(pcmChunk: FloatArray) {
        mockFallback?.let { it.sendAudio(pcmChunk); return }
        val ws = webSocket ?: return
        if (!setupSent || !ws.isOpen) return
        // Convert normalized float [-1,1] → PCM16 little-endian → base64.
        val bytes = ByteArray(pcmChunk.size * 2)
        for (i in pcmChunk.indices) {
            val s = (pcmChunk[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", b64)
                })
            })
        }
        try {
            ws.send(msg.toString())
            if (_state.value !is ConversationState.StreamingInput) updateState(ConversationState.StreamingInput)
            _metrics.value = _metrics.value.copy(bytesSent = _metrics.value.bytesSent + bytes.size)
        } catch (_: Exception) {
        }
    }

    override suspend fun injectMessage(text: String) {
        mockFallback?.let { it.injectMessage(text); return }
        val ws = webSocket ?: return
        if (!setupSent || !ws.isOpen) return

        try {
            val message = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    }))
                    put("turnComplete", true)
                })
            }
            ws.send(message.toString())
            Log.d(TAG, "💉 Injected system message: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Inject error: ${e.message}")
        }
    }

    override suspend fun cancelGeneration() {
        mockFallback?.let { it.cancelGeneration(); return }
        updateState(ConversationState.Cancelling)
        _events.emit(ConversationEvent.GenerationCancelled())
        updateState(ConversationState.Connected)
    }

    override suspend fun disconnect() {
        intentionalDisconnect = true
        mockFallback?.disconnect()
        try {
            webSocket?.close()
        } catch (_: Exception) {
        }
        webSocket = null
        setupSent = false
        updateState(ConversationState.Disconnected)
        _events.emit(ConversationEvent.Disconnected(providerName))
        currentSessionId = ""
    }

    private fun updateState(newState: ConversationState) {
        if (_state.value != newState) {
            Log.d(TAG, "🔄 [GEMINI STATE] ${_state.value.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _state.value = newState
        }
    }

    override fun close() = release()

    override fun release() {
        intentionalDisconnect = true
        mockFallback?.release()
        try {
            webSocket?.close()
        } catch (_: Exception) {
        }
        webSocket = null
        scope.launch { updateState(ConversationState.Disconnected) }
    }
}

/**
 * Mock Conversation Provider for offline testing and architectural verification.
 */
class MockConversationProvider(
    private val config: ConversationConfig = ConversationConfig(),
) : ConversationProvider {

    companion object {
        private const val TAG = "MockConversationProvider"
    }

    override val providerName: String = "Mock Provider (Offline)"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val streamingManager = StreamingManager(config.streamingChunkSizeBytes)

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Disconnected)
    override val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow(ConversationMetrics(providerName = providerName))
    override val metrics: StateFlow<ConversationMetrics> = _metrics.asStateFlow()

    private var currentSessionId: String = ""
    private var mockResponseJob: Job? = null

    override suspend fun initialize() {
        _state.value = ConversationState.Disconnected
    }

    override suspend fun connect(sessionId: String) {
        currentSessionId = sessionId
        _state.value = ConversationState.Connecting
        delay(150L)
        _state.value = ConversationState.Connected
        _events.emit(ConversationEvent.Connected(providerName))
        _metrics.value = _metrics.value.copy(sessionId = sessionId, connectionTimeMs = System.currentTimeMillis())
        Log.d(TAG, "✅ MockProvider Connected (SessionID: $sessionId)")
    }

    override suspend fun sendAudio(pcmChunk: FloatArray) {
        if (_state.value is ConversationState.Connected || _state.value is ConversationState.StreamingInput) {
            _state.value = ConversationState.StreamingInput
            val bytes = streamingManager.pushAudioChunk(pcmChunk)
            _metrics.value = _metrics.value.copy(bytesSent = streamingManager.bytesSent)

            // Simulate incoming response generation after speech
            mockResponseJob?.cancel()
            mockResponseJob = scope.launch {
                delay(400L)
                _state.value = ConversationState.Generating
                delay(300L)
                _state.value = ConversationState.StreamingOutput
                _events.emit(ConversationEvent.PartialResponse(text = "Hello! I am ULTRON."))
                delay(500L)
                _events.emit(ConversationEvent.FinalResponse(text = "How can I assist your system today?"))
                _state.value = ConversationState.Connected
            }
        }
    }

    override suspend fun injectMessage(text: String) {
        Log.d(TAG, "💉 MockProvider injected: $text")
    }

    override suspend fun cancelGeneration() {
        mockResponseJob?.cancel()
        mockResponseJob = null
        _state.value = ConversationState.Cancelling
        _events.emit(ConversationEvent.GenerationCancelled())
        Log.d(TAG, "⚡ MockProvider Generation Cancelled")
        delay(100L)
        _state.value = ConversationState.Connected
    }

    override suspend fun disconnect() {
        mockResponseJob?.cancel()
        _state.value = ConversationState.Disconnected
        _events.emit(ConversationEvent.Disconnected(providerName))
        currentSessionId = ""
    }

    override fun close() {
        release()
    }

    override fun release() {
        mockResponseJob?.cancel()
        _state.value = ConversationState.Disconnected
    }
}
