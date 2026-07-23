# ULTRON_INTEGRATION_PLAN

## Overview
The goal is to seamlessly port the proven audio processing pipeline (VAD → WakeWord → ECAPA → Gemini Live) from `audio_model_test` into the main `UltronAssistant` codebase. 

Crucially, we must preserve Ultron's highly decoupled, reactive architecture (`VoiceService`, `VoiceSessionManager`, `StateFlows`), rather than bringing over the monolithic `AudioPipeline.kt` from the test project. Ultron relies on distinct modules (managers) observing each other through Coroutines.

## What Will Change

1. **WakeWordManager (Ultron)**
   - We will replace the generic/placeholder `WakeWordDetector` in Ultron with the 3-model OpenWakeWord ONNX pipeline from the test project.
   - The required DSP normalization logic (`x / 10 + 2`) and the 1280-sample chunks will be implemented inside a new `OpenWakeWordDetector` class within Ultron.

2. **SpeakerVerificationManager (Ultron)**
   - We will introduce `EcapaManager` (or integrate it directly into `SpeakerVerifier`) using `ecapa.onnx`.
   - We will migrate `AudioFeatureExtractor` to Ultron to generate the 80-bin Log-Mel Spectrograms required by ECAPA.
   - The `SpeakerVerificationManager` will observe `WakeWordManager.state`. When `WakeDetected` is emitted, it will grab the snapshot of `recentAudio` and run ECAPA.
   - We will implement the `speechFrames` vs `recentFrames` rolling buffer logic in `VoiceSessionManager` or `WakeWordManager` to ensure the "zero-frame" bug does not occur in Ultron.

3. **VoiceSessionManager (Ultron)**
   - Currently, `VoiceSessionManager` advances states. We will adapt it to respect the ECAPA output. If `VerificationResult.Verified`, it triggers the Gemini session.
   - We will map the 4 stages from the test project into Ultron's existing states:
     - STAGE 0 → `VoiceSessionState.Idle`
     - STAGE 1 → `VadState.SpeechStarted`
     - STAGE 2 → `WakeWordState.WakeDetected` + `VerificationResult`
     - STAGE 3 → `ConversationState.Connected` / `StreamingInput`

4. **Gemini Live Integration**
   - The test project's `GeminiManager.kt` contains the working WebSocket logic.
   - Ultron already has a `ConversationEngine` and `ProviderType.GEMINI_LIVE`. We will update Ultron's `GeminiLiveProvider` (or create it) using the exact JSON schemas, WebSocket handling, and Base64 audio streaming logic from `audio_model_test`.

## What Will Be Reused (Ultron Infrastructure)
- **AudioRecorderManager / AudioPlayerManager**: Ultron's existing hardware wrappers.
- **VoiceService**: The foreground service and coroutine scope handling.
- **VoiceSessionManager**: The core event bus and telemetry hub.
- **VadManager**: Ultron already integrates Silero VAD properly. We will only ensure the ONNX output tensor extraction matches the test project if there are version differences.
- **ConversationEngine**: The orchestrator for injecting responses back into the app.

## Risks & Mitigation
1. **Memory / ONNX Session Leaks**: Instantiating 5 ONNX sessions (VAD, 3x WW, ECAPA) requires careful lifecycle management. *Mitigation*: Ensure `VoiceService.onDestroy` cascades down to close all `OrtSession` and `OrtEnvironment` instances.
2. **Blocking the Main Thread**: Audio feature extraction (FFT) and ONNX inference are CPU intensive. *Mitigation*: All inference and DSP will strictly run on `Dispatchers.Default` (or `Dispatchers.IO` for WebSockets). The `AudioRecorder` flow will use buffered channels to prevent backpressure.
3. **Buffer Synchronization**: The `speechFrames` and `recentFrames` buffers need thread-safe locks. *Mitigation*: Use Kotlin `Mutex` or `synchronized()` blocks as proven in the test project.

## Migration Strategy
1. **Phase 1: DSP & Models**: Port `AudioFeatureExtractor`, the OpenWakeWord cascade, and the ECAPA model classes into `com.flexfly.ultron.voice`.
2. **Phase 2: WakeWord & Speaker Integration**: Wire the OpenWakeWord logic into Ultron's `WakeWordManager` and the ECAPA logic into `SpeakerVerificationManager`. Verify the `StateFlow` cascading works (VAD -> WW -> ECAPA).
3. **Phase 3: Gemini Live**: Update Ultron's `ConversationEngine` and Gemini provider to use the working WebSocket logic.
4. **Phase 4: Validation**: Run the app and observe the `VoiceService` notifications and logs as the state transitions from IDLE to CONVERSATION.
