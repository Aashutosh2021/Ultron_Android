# VOICE_PIPELINE_IMPLEMENTATION

## Overview
This document outlines the final implementation details of integrating the `audio_model_test` standalone pipeline into `UltronAssistant`'s reactive, multi-manager architecture.

## 1. Asset Migration
We copied the ONNX models from the reference project into `UltronAssistant`'s `assets/` folder:
- `melspectrogram.onnx`
- `embedding_model.onnx`
- `hey_jarvis.onnx`
- `ecapa_tdnn.onnx`
- `silero_vad.onnx`

## 2. DSP Engine (`AudioFeatureExtractor.kt`)
We ported the `AudioFeatureExtractor.kt` class from the test project into `com.flexfly.ultron.voice.AudioFeatureExtractor.kt`. This pure Kotlin engine is responsible for generating the exact 80-bin Log-Mel Spectrogram features required by the ECAPA-TDNN model, avoiding native/JNI dependencies.

## 3. Wake Word Stage (`OpenWakeWordDetector`)
We updated `WakeWordDetector.kt` to exactly match the openWakeWord 3-model DSP requirement:
- Included the crucial log-mel normalization formula: `x / 10 + 2`.
- Preserved the rolling embedding buffer storing the latest 16 96-dimensional vectors for Wake Word classification.
- Audio is fed into this layer by `WakeWordManager.kt` via chunked buffers of 1280 samples (80ms at 16kHz).

## 4. Speaker Verification Stage (`SpeakerVerifier`)
We successfully updated `EcapaTdnnVerifier` in `SpeakerVerifier.kt` to extract Log-Mel features before feeding them into ONNX:
1. `AudioFeatureExtractor.computeLogMelSpectrogram(samples)` translates raw PCM arrays to Log-Mel.
2. The 2D structure is flattened to a 1D tensor representing shape `[1, numFrames, 80]`.
3. The float array is extracted from the ONNX output using a direct ByteBuffer `.get()` to safely extract the 192-dimensional vector.

## 5. State Machine Connectivity
The test project used `AudioPipeline` to transition states sequentially. Ultron relies on disjoint components (`VadManager`, `WakeWordManager`, `SpeakerVerificationManager`, `VoiceSessionManager`) emitting states on `StateFlow`.
We achieved the same workflow by adding a direct synchronization rule in `VoiceService.kt`:
- `WakeWordManager` observes `VadManager.state`. It transitions to `Listening` only if `VadManager` declares `Speaking`.
- `SpeakerVerificationManager` observes `WakeWordManager.state`. When `WakeDetected`, it extracts the `recentAudio` snippet and validates the speaker.
- `VoiceSessionManager` issues a `SessionStarted` upon validation.
- In `VoiceService.kt`, we listen for `SessionStarted` (which sets state to `WaitingForUser`) and instantly promote it to `Listening` via `onUserSpeechStarted()` if VAD is already `Speaking`. This solves the issue of losing the first utterance.

## 6. Gemini Live Endpoint
The integration of `GeminiLiveProvider.kt` in Ultron was verified to be fully correct out of the box, containing the precise JSON structures, Base64 PCM encoding, and WebSocket configuration required for Gemini BidiGenerateContent.
