# Runtime Stall Root Cause Analysis

## Trace & Execution Stop Point

The execution successfully follows:
1. `VoiceService.onStartCommand()` -> `startVadPipeline()`
2. `startVadPipeline()` -> `conversationEngine?.start()`
3. `ConversationEngine.start()` -> `provider.initialize()`
4. `GeminiLiveProvider.initialize()` sets state to `Initializing` then `Disconnected`.

**Where execution stops:** 
Execution stops waiting inside `ConversationEngine.kt` at **line 66** within the `sessionObservationJob` coroutine:
```kotlin
        sessionObservationJob = scope.launch {
            Log.d(TAG, "🚀 ConversationEngine subscribing to VoiceSessionManager.events")
            sessionManager.events.collect { sessionEvent ->
                handleSessionEvent(sessionEvent)
            }
        }
```

The call to `provider.connect()` is intentionally gated behind `VoiceSessionEvent.SessionStarted` (handled at **line 76** of `ConversationEngine.kt`). Because `SessionStarted` is never emitted, `connect()` is never invoked, meaning WebSocket creation and `onOpen()` are never reached. 

**Conclusion on Execution Halt:** There is no missing coroutine launch, no suspended coroutine deadlock, and no immediate cancellation. The execution stops naturally, blocked by the state machine waiting for a session to start.

---

## Why WakeWordManager Never Emits WakeWordDetected

Even though `AudioRecord` is actively capturing audio and sending frames, `WakeWordManager` is intentionally ignoring them. 

**Code Path:**
In `WakeWordManager.kt` (**line 98**), the method `processFloatFrame` explicitly drops all audio unless the state is `Listening`:
```kotlin
    private fun processFloatFrame(frame: FloatArray) {
        if (_state.value !is WakeWordState.Listening) return // Execution stops here!
        // ... openWakeWord inference runs below
    }
```

`WakeWordManager` only enters `Listening` when it receives `VadState.SpeechStarted` from `VadManager` (handled at **line 138**).

**Why VadManager isn't emitting SpeechStarted:**
The user logs indicate "No SpeechStarted". In `VadManager.kt` (**line 132**), `SpeechStarted` is only emitted when `continuousSpeechMs >= config.minSpeechDurationMs` (default 250ms). 

This means that `SileroVadDetector.processFrame(frame)` is returning a confidence value below `config.speechThreshold` (default 0.50f).

**Root Cause:**
The application is not stalled due to a programmatic bug, deadlock, or architectural failure. It is correctly sitting in an `Idle` state. The pipeline cascades like this:
1. VAD waits for `continuousSpeechMs > 250ms` to emit `SpeechStarted`.
2. WakeWordManager waits for `SpeechStarted` to run inference.
3. VoiceSession waits for `WakeWordDetected` to emit `SessionStarted`.
4. ConversationEngine waits for `SessionStarted` to call `provider.connect()`.

If the Wake Word is not being detected, it is because either:
1. You are not speaking into the microphone.
2. The microphone volume is too low, or the `SileroVadDetector` threshold (`0.50f`) is too strict for your current audio environment, preventing the VAD from triggering the downstream Wake Word model.
