# Changelog — Production Stabilization Sprint

All changes preserve the existing 10-phase architecture, package structure, public APIs, and layer responsibilities. No redesign, no interface removal, no new phases.

## [Unreleased] — Stabilization Sprint (2026-07-20)

### Security
- **PermissionManager no longer auto-grants permissions.** Real `ContextCompat.checkSelfPermission` is consulted for `android.permission.*`; missing permissions are reported honestly (fail-closed) and a request event is published for a UI layer to satisfy. (`ai/tool/PermissionManager.kt`, `ai/tool/ToolDispatcher.kt`)
- **ML models fail loudly in release.** Missing/broken Silero VAD, openWakeWord, and ECAPA-TDNN ONNX models now throw `IllegalStateException` in release builds instead of silently degrading to DSP/no-op stubs. Fallbacks remain available in **debug** builds only. (`voice/VoiceActivityDetector.kt`, `voice/wakeword/WakeWordDetector.kt`, `voice/speaker/SpeakerVerifier.kt`)

### Reliability / Persistence
- **Memory Engine now persists across process death.** New `PersistentFileStoreProvider` (JSON-file backed, thread-safe) replaces the volatile `InMemoryStoreProvider` as the default `MemoryProvider`, preserving the `MemoryStore` interface exactly. Default seed memories no longer duplicate on every launch. (`ai/memory/PersistentFileStoreProvider.kt`, `ai/memory/MemoryEngine.kt`)
- **Plugin crashes can no longer crash the Core.** Every plugin invocation (`metadata`, `supportedTools`, `stop`, `healthStatus`) runs inside a hard exception boundary; a throwing plugin is quarantined and reported via `PluginEvent.PluginCrashed`, never propagating into the manager scope. Health score now reflects faulted plugins. (`plugin/sdk/PluginManager.kt`)

### Concurrency
- **Fixed shared `OrtEnvironment` close cascade.** The three ONNX detectors no longer close the process-wide `OrtEnvironment` singleton (which would invalidate sibling sessions); they drop their own session/reference only. (`voice/VoiceActivityDetector.kt`, `voice/wakeword/WakeWordDetector.kt`, `voice/speaker/SpeakerVerifier.kt`)
- **Serialized ONNX inference in the speaker path.** `OrtSession.run` is guarded by a lock so overlapping enrollment/verification coroutines cannot corrupt the non-thread-safe session. (`voice/speaker/SpeakerVerifier.kt`)
- **Metrics counters use `MutableStateFlow.update{}`** at the highest-traffic increment sites, eliminating lossy read-modify-write races. (`ai/tool/ToolDispatcher.kt`, `ai/brain/BrainEngine.kt`)

### Runtime Integration
- **Single production runtime selector.** `MainActivity` now starts exactly one voice runtime, chosen by `BuildConfig.USE_VOICE_SERVICE_RUNTIME` (default `false` = legacy `WakeWordService`; `true` = new 10-phase `VoiceService`). Both start and stop honor the flag, so two microphone foreground services can never contend. (`app/build.gradle.kts`, `MainActivity.kt`)
- **Completed the audio pipeline (mic → VAD → Wake Word → Speaker).** `VadManager` now publishes raw PCM frames on an `audioFrames` `SharedFlow` (DROP_OLDEST, cannot stall capture). `WakeWordManager` collects it and runs real windowed inference; it also retains a rolling ~1s audio tail. `SpeakerVerificationManager` now verifies against the **actual triggering utterance** instead of an all-zeros buffer. (`voice/VadManager.kt`, `voice/wakeword/WakeWordManager.kt`, `voice/speaker/SpeakerVerificationManager.kt`)

### Runtime Integration
- **Single production runtime selector.** `MainActivity` now starts exactly one voice runtime, chosen by `BuildConfig.USE_VOICE_SERVICE_RUNTIME` (default `false` = legacy `WakeWordService`; `true` = new 10-phase `VoiceService`). Both start and stop honor the flag, so two microphone foreground services can never contend. (`app/build.gradle.kts`, `MainActivity.kt`)
- **Completed the audio pipeline (mic → VAD → Wake Word → Speaker).** `VadManager` now publishes raw PCM frames on an `audioFrames` `SharedFlow` (DROP_OLDEST, cannot stall capture). `WakeWordManager` collects it and runs real windowed inference; it also retains a rolling ~1s audio tail. `SpeakerVerificationManager` now verifies against the **actual triggering utterance** instead of an all-zeros buffer. (`voice/VadManager.kt`, `voice/wakeword/WakeWordManager.kt`, `voice/speaker/SpeakerVerificationManager.kt`)
- **Session turn-taking now driven by real events.** `VoiceService` routes VAD speech onset/offset → `VoiceSessionManager.onUserSpeechStarted/Ended` and conversation partial/final responses → `onAssistantStartedSpeaking/Finished`, unblocking the session state machine (previously stuck at WaitingForUser). Wiring runs in a dedicated SupervisorJob scope cancelled on teardown. (`voice/VoiceService.kt`)
- **Real Gemini Live conversation provider.** `GeminiLiveProvider` is now a genuine `org.java-websocket` client to the Gemini Live BidiGenerateContent endpoint (same connection approach as the legacy `GeminiLiveSession`), scoped to the provider contract — PCM in, output audio out as `PartialResponse(data)`, transcript/turn events, and bounded reconnection with backoff. It deliberately omits the legacy tool/DB coupling to avoid double tool execution. Mock retained only when no API key is set. Output audio played via the reused `AudioPlayerManager`. (`ai/conversation/GeminiLiveProvider.kt`, `voice/VoiceService.kt`)
- **Tool framework bridged to the real device layer.** New `LegacyToolBridge` delegates execution to the production `ToolHandler` (~60 real tools); `ToolDispatcher` falls back to it when exact-name lookup misses, so Brain-emitted tool names reach real device actions. Delegation, not rewrite. (`ai/tool/LegacyToolBridge.kt`, `ai/tool/ToolFactory.kt`, `ai/tool/ToolDispatcher.kt`)

### Notes
- Every milestone verified with `./gradlew :app:compileDebugKotlin` (and `:app:compileReleaseKotlin` for the fail-loud model changes). Full `assembleDebug` green; installed + launched on-device with no crash (legacy default → no regression).
- No public API signatures were removed. `WakeWordManager.processAudioFrame(FloatArray, ShortArray, Int)` retained for compatibility though superseded by the audio-bus path.
- **Known blocker:** the three ONNX models (`silero_vad.onnx`, `hey_ultron.onnx`, `ecapa_tdnn.onnx`) are absent; the new runtime stays flag-gated (default legacy) until they are supplied. See `PRODUCTION_STABILIZATION_REPORT.md`.
