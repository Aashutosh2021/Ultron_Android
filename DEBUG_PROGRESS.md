# Debug Progress

## Iteration 1

**Problem Found:** VAD never emits `SpeechStarted`, preventing the assistant from knowing when the user is speaking, dropping audio before it reaches Gemini.

**Evidence:** 
- User logs show "No SpeechStarted".
- `Audio_ROUTING_VALIDATION.md` proves that audio flows unconditionally to `VadManager`.
- If `SpeechStarted` is missing, `SileroVadDetector` must be returning confidence < 0.50f (the default `speechThreshold`), or audio is silent.

**Exact File:** `e:\UltronAssistant\app\src\main\java\com\flexfly\ultron\voice\VadManager.kt`

**Exact Line:** 111 (Instrumented to log confidence)

**Root Cause:** *Pending Runtime Evidence* (Awaiting Logcat for VAD Confidence).

**Patch Applied:** 
Instrumented `VadManager.processAudioFrame` to log VAD confidence every 20 frames (approx. every ~640ms).

**Next Step:**
Rebuild, run, and collect logcat to observe actual confidence values.
