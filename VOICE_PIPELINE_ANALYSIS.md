# VOICE_PIPELINE_ANALYSIS

## Current AudioTest Architecture
The standalone `audio_model_test` project implements a robust, continuous voice processing pipeline orchestrated by `AudioPipeline.kt`. It connects the hardware microphone layer to a series of ONNX neural network models (Silero VAD, OpenWakeWord, and ECAPA-TDNN) and a WebSocket stream (Gemini Live), controlled by a 4-stage state machine.

### Key Components
1. **AudioRecorder**: Reads raw PCM audio continuously (16kHz, mono, 16-bit). It processes audio into chunks (typically 512 samples/32ms).
2. **AudioPipeline**: The master state machine. Listens to PCM frames from `AudioRecorder`, routes them to models, and buffers audio using concurrent rolling buffers.
3. **SileroVadManager**: Uses `silero_vad.onnx` to determine speech probabilities. Returns a `SPEECH` or `SILENCE` state.
4. **WakeWordManager**: Implements the OpenWakeWord pipeline. Uses three ONNX models: melspectrogram extraction, Google Speech Embeddings, and the OpenWakeWord classifier.
5. **EcapaManager**: Uses `ecapa.onnx` for speaker verification. Compares extracted embeddings using cosine similarity.
6. **GeminiManager**: Real-time WebSocket client for the Gemini Live API. Uses `BidiGenerateContentSetup` and `BidiGenerateContentClientContent` schemas.

---

## DSP Flow
The Digital Signal Processing (DSP) flow in `audio_model_test` heavily relies on raw PCM Float arrays and MelSpectrogram transformations.

### Pre-WakeWord (OpenWakeWord Pipeline)
OpenWakeWord requires a very specific 3-model cascade, replicated in `WakeWordManager.kt`:
1. **Raw PCM**: Fed in 1280-sample (80ms) chunks to `melspectrogram.onnx`.
2. **Normalization (`x / 10 + 2`)**: The `melspectrogram.onnx` output is in standard log-mel scale. The Google Speech Embedding model (trained on AudioSet) expects inputs normalized to roughly mean=0, std=1. The `x / 10 + 2` transform is an empirical shift-and-scale approximation (shifting the log-mel features by +20 and dividing by 10) baked into the original openwakeword Python library to match the training distribution of the embedding model.
3. **Google Speech Embedding**: Takes 76 context frames of 32 mel-bins (buffer shifted on each new frame) and generates a 96-dimensional embedding.
4. **Wake Word Classifier**: Takes 16 context frames of the 96-D embeddings and outputs a final probability score.

### Pre-ECAPA (Speaker Verification)
Unlike OpenWakeWord, the ECAPA model requires a standard 80-bin Log-Mel Spectrogram. `EcapaManager.kt` uses a custom Kotlin `AudioFeatureExtractor` to compute FFTs, apply 80 Mel filters, and calculate natural logs. The resulting 2D array is flattened and passed into `ecapa.onnx` as a `[1, numFrames, 80]` tensor to extract a 192-dimensional speaker embedding.

---

## State Machine
The `AudioPipeline` uses a strict 4-stage state machine:

- **STAGE 0 (IDLE)**: VAD listens. If `prob > 0.5`, transition to STAGE 1. If WakeWord detects (bypassing VAD), transition directly to STAGE 2.
- **STAGE 1 (VOICE_DETECTED)**: VAD is active. Frames are buffered into a `speechFrames` list. WakeWord continuously scores. If `score > 0.5` (or 0.1 threshold), transition to STAGE 2.
- **STAGE 2 (WAKE_WORD_DETECTED)**: Triggers ECAPA on the buffered frames asynchronously. If cosine similarity > 0.65, transition to STAGE 3. If low similarity, reject and return to IDLE.
- **STAGE 3 (GEMINI_ACTIVE)**: All incoming PCM frames bypass VAD/WakeWord logic and stream directly to Gemini via WebSocket. Waits for Gemini `turnComplete` and VAD silence to return to IDLE.

---

## Important Implementation Details (Rolling Buffers)
1. **`speechFrames` vs `recentFrames`**: 
   - `speechFrames` collects audio *after* the VAD triggers `SPEECH`.
   - `recentFrames` is a sliding window (LinkedList) holding the absolute latest 60 frames (~2.0s).
   - **Why `recentFrames` exists**: VAD has a short delay, and the user might say "Hey Jarvis" extremely fast. By the time VAD triggers, the WakeWord might already be halfway spoken. If `speechFrames` has less than 20 frames (insufficient for ECAPA), the pipeline falls back to `recentFrames` to ensure the ECAPA model has enough audio context (including the pre-VAD audio) to generate an embedding, solving the "zero-frame" crash issue.
2. **Session Persistence**: Models (Mel, Emb, WW, ECAPA, VAD) are loaded once into memory via `OrtEnvironment` and kept alive to prevent garbage collection spikes and high latency.
