# Phase 0/1 setup — wake word service

## 1. Add the dependency
In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("xyz.rementia:openwakeword:0.1.3")
    // ... your existing dependencies
}
```
Published on Maven Central directly — no extra repository line needed.

Also set (or confirm) in the same file:
```kotlin
android {
    defaultConfig {
        minSdk = 26
        targetSdk = 35
        compileSdk = 35
    }
}
```

## 2. Get the required model files
openWakeWord always needs 3 ONNX files together in `app/src/main/assets/`:
melspectrogram model, embedding model, and your wake-word classifier.

Fastest way to get the first two plus a pretrained placeholder keyword
(`hey_jarvis`), from any machine with Python:
```bash
pip install openwakeword
python -c "import openwakeword; openwakeword.utils.download_models()"
```
Copy the resulting `melspectrogram.onnx`, `embedding_model.onnx`, and
`hey_jarvis.onnx` into `app/src/main/assets/`.

Test with "Hey Jarvis" first. Confirms the whole pipeline — mic capture,
engine, detection, foreground service — actually works before you spend
time training "Hey Ultron."

## 3. Train "Hey Ultron" (after Jarvis works)
1. Official training repo: https://github.com/dscripka/openWakeWord
2. Follow the Colab notebook linked in that repo's README — synthetic
   training data, no need to record your own voice samples.
3. Export as ONNX, name it `hey_ultron.onnx`, drop it in `assets/`
   next to the other two files.
4. In `WakeWordService.kt`, change `WAKE_WORD_MODEL_FILE` to
   `"hey_ultron.onnx"` and `WAKE_WORD_NAME` to `"Hey Ultron"`.

## 4. Run it
Install on the Infinix Note 50s, grant mic + notification permissions
when asked, say "Hey Jarvis" (or "Hey Ultron" once trained). Filter
Logcat by tag `WakeWordService` to see detection events.

## Known gap at this phase
`onWakeWordDetected()` in `WakeWordService.kt` only logs right now.
That function is exactly where Phase 2 (opening the Gemini Live
session) plugs in — not built yet.

## Dependency risk, noted honestly
xyz.rementia:openwakeword is a small library — 10 stars, 1 fork, one
visible maintainer, last release mid-2025. API works and is clean, but
there's no large community behind it. If you hit a wall, the original
Java port it's based on is a useful fallback reference:
https://github.com/hasanatlodhi/OpenwakewordforAndroid
