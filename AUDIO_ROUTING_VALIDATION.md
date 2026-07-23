# Audio Routing Validation

## Architectural Analysis

To answer the architectural question: **Is there a circular dependency causing the VAD to never receive audio because the session is waiting?**

**No.** There is zero circular dependency. 

The audio routing strictly flows from the hardware to the VAD **first**, and only then reaches the state-dependent gates in `VoiceService`.

## Exact Routing Evidence

### 1. AudioRecord to VadManager
In `VadManager.kt` (lines 83-89), `AudioRecorder` directly feeds `VadManager` without any intermediate gates:
```kotlin
        job = scope.launch {
            try {
                recorder.startStreaming().collect { frame ->
                    processAudioFrame(frame) // Unconditionally receives every single frame
                }
            }
```

### 2. Processing and Forwarding inside VadManager
Inside `VadManager.processAudioFrame` (lines 104-108), two things happen unconditionally for every frame:
```kotlin
    private suspend fun processAudioFrame(frame: FloatArray) {
        // 1. Broadcast the raw audio to downstream consumers (like VoiceService)
        _audioFrames.tryEmit(frame)

        // 2. Run the VAD neural network on the frame
        val confidence = detector.processFrame(frame)
```
**Conclusion:** `VadManager.processFrame()` executes continuously, regardless of what the rest of the application is doing.

### 3. VoiceService downstream filtering
The dropping of audio happens **downstream** of the VAD. In `VoiceService.kt` (lines 294-297), it subscribes to the `vad.audioFrames` bus:
```kotlin
        turnWiringJobs += wiringScope.launch {
            vad.audioFrames.collect { frame ->
                // Audio is only dropped BEFORE Gemini, NOT before VAD.
                if (session.sessionState.value is VoiceSessionState.Listening) {
                    conv.sendAudioChunk(frame)
                }
            }
        }
```

## Summary of Answers

1. **Does VadManager still receive every AudioRecord frame while sessionState == WaitingForUser?**
   **YES**. It is hardwired directly to the `AudioRecorder` flow.

2. **Are frames discarded before reaching VadManager?**
   **NO**. Frames are only discarded by `VoiceService` *after* `VadManager` has already finished processing them and published them to the shared bus.

3. **Is there a circular dependency?**
   **NO**. The audio flows cleanly:
   `AudioRecord` → `VadManager` (Model infers -> Emits SpeechStarted -> Updates SessionState) → `_audioFrames` → `VoiceService` (Checks SessionState -> Forwards to Gemini).

Since there is no circular dependency blocking audio from reaching the VAD, the only remaining physical truth is that `detector.processFrame(frame)` is continuously executing but actively deciding the audio it hears is **not speech** (confidence < 0.50f).
