# Session Termination Root Cause Analysis

## Timeline After Connected

**Connected**
↓
**Audio Streaming Started?** -> **NO**.
↓
**First PCM Sent?** -> **NO**.
↓
**First Gemini Response?** -> **NO**.
↓
**Who emits SessionEnded?** -> **`SilenceDetector` via `VoiceSessionManager.handleSilenceTimeout()`**
↓
**Exact File:** `e:\UltronAssistant\app\src\main\java\com\flexfly\ultron\voice\session\VoiceSessionManager.kt`
↓
**Exact Method:** `handleSilenceTimeout()`
↓
**Exact Line:** 184 (`endSession("SilenceTimeout")`)

---

## The Root Cause of Termination

The session is terminating cleanly and intentionally due to an **initial silence timeout**. 

Here is the exact chain of events:

1. **Manual Trigger:** When you tap the manual button, it forces the system to emit `SessionStarted`.
2. **Provider Connection:** `ConversationEngine` successfully connects to Gemini over WebSocket.
3. **Timeout Started:** Concurrently, `VoiceSessionManager.startNewSession()` enters the `WaitingForUser` state and starts a 10-second `SilenceDetector` timer (`initialSessionTimeoutMs` from `SessionConfig.kt`).
4. **Waiting for VAD:** The system waits for you to start speaking. It specifically waits for the `VadManager` to emit `VadState.SpeechStarted`. 
5. **Audio Dropped:** In `VoiceService.kt` (lines 294-297), audio is **only** forwarded to Gemini if `sessionState` is `Listening`. Because the VAD never triggered `SpeechStarted`, the session remains in `WaitingForUser`, and **all audio frames are intentionally dropped**.
6. **Timeout Expires:** 10 seconds pass without `VadManager` detecting speech. The `SilenceDetector` expires, calling `handleSilenceTimeout()`.
7. **Cleanup:** `VoiceSessionManager` calls `endSession("SilenceTimeout")`. This emits `SessionEnded`. `ConversationEngine` receives this, cleanly disconnects the provider, and the WebSocket closes (code 1000).

### Summary
`sendAudio()` is never called because the session state never advances from `WaitingForUser` to `Listening`. It never advances because the **Voice Activity Detector (VAD) is still failing to recognize your speech**. 

The manual button successfully bypassed the Wake Word model, but the pipeline still relies on the VAD to know *when* you are talking so it knows when to stream audio and when to stop. If VAD is too strict (or your mic is too quiet), the VAD stays idle, the audio is dropped, and the 10-second silence timeout naturally closes the session.
