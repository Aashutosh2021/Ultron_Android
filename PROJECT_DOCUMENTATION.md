# 🌌 ULTRON Assistant - Project Documentation

## 📋 Overview
ULTRON is a high-performance AI Assistant integrated into the Android ecosystem. It combines real-time voice processing, neural-network-based wake-word detection, biometric speaker verification, and a sophisticated Brain Engine capable of controlling device hardware, third-party apps, and executing complex automation tasks.

---

## 🌲 Project Tree Structure

```text
ULTRON/
├── app/
│   ├── src/main/java/com/flexfly/ultron/
│   │   ├── MainActivity.kt                # Entry point of the application
│   │   ├── UltronCameraActivity.kt        # Camera-specific UI and logic
│   │   ├── ai/                            # Core Intelligence Layer
│   │   │   ├── brain/                     # Logic and reasoning engine
│   │   │   ├── conversation/               # Dialogue management and flow
│   │   │   ├── memory/                    # Long-term and short-term memory
│   │   │   ├── tool/                      # Tool definitions and execution
 same  │   │   ├── GeminiLiveSession.kt     # Gemini Live API integration
│   │   │   ├── PromptBuilder.kt           # Dynamic prompt engineering
│   │   │   └── TranslationManager.kt      # Real-time language translation
│   │   ├── android/                       # Android Framework integration
│   │   │   └── action/                    # System-level action executors
│   │   ├── apps/                          # Third-party App Controllers
│   │   │   ├── SpotifyManager.kt          # Spotify API/UI control
│   │   │   ├── WhatsAppManager.kt         # WhatsApp automation
│   │   │   └── YouTubeManager.kt          # YouTube playback control
│   │   ├── audio/                         # General Audio Utilities
│   │   │   ├── AudioPlayerManager.kt      # TTS and Sound effect playback
│   │   │   └── AudioRecorderManager.kt    # Basic audio capture utilities
│   │   ├── automation/                    # Accessibility & System Automation
│   │   │   ├── UltronAccessibilityService.kt # Core Accessibility bridge
│   │   │   ├── AutoTyper.kt               # Automated text input
│   │   │   └── ScreenReader.kt            # UI element analysis and reading
│   │   ├── comms/                         # Communication Suite
│   │   │   ├── CallReceiver.kt            # Phone call interception
│   │   │   └── UltronSmsManager.kt        # SMS sending and reading
│   │   ├── data/                          # Persistence Layer
│   │   │   ├── DatabaseHelper.kt         # SQLite/Room database management
│   │   │   └── AppPreferences.kt           # SharedPreferences / DataStore
│   │   ├── device/                        # Hardware Abstraction Layer (HAL)
│   │   │   ├── BatteryMonitor.kt          # Power and charging state
│   │   │   ├── CameraManager.kt            # Camera hardware control
│   │   │   └── VolumeManager.kt           # System volume and audio routing
│   │   ├── i18n/                          # Internationalization
│   │   │   └── Languages.kt                # Language support and transliteration
│   │   ├── info/                          # External Data Providers
│   │   │   ├── NewsManager.kt             # Real-time news fetching
│   │   │   └── WeatherManager.kt          # Weather API integration
│   │   ├── model/                         # Shared Domain Models
│   │   ├── overlay/                        # Floating UI Components
│   │   │   └── CountdownOverlay.kt        # Visual timers and alerts
│   │   ├── plugin/                        # Extensibility SDK
│   │   │   └── sdk/                       # Plugin development kit
│   │   ├── receiver/                       # System Broadcast Receivers
│   │   │   └── ChargingReceiver.kt        # React to power state changes
│   │   ├── reminder/                       # Scheduling Engine
│   │   │   └── ReminderManager.kt         # Alarm and notification scheduling
│   │   ├── security/                      # Security and Anti-theft
│   │   │   └── AntiTheftManager.kt        # Device protection logic
 الخاص بـ service/                         # Background Infrastructure
│   │   │   └── WakeWordService.kt         # Persistent Voice background service
│   │   ├── ui/                            # Frontend Framework
│   │   │   ├── AppRoot.kt                 # Root UI composition
│   │   │   └── theme/                     # Visual design and styling
│   │   └── voice/                         # 🎙️ Proven Voice AI Pipeline
│   │       ├── AudioConfig.kt              # Audio hardware/model constants
│   │       ├── AudioFrame.kt              # Raw PCM data encapsulation
│   │       ├── AudioRecorder.kt           # High-precision PCM capture
│   │       ├── VadManager.kt               # Silero VAD orchestration
 la  │       ├── VoiceActivityDetector.kt    # Silero VAD ONNX implementation
│   │       ├── pipeline/                  # Pipeline orchestration logic
│   │       ├── session/                   # Gemini Live session management
│   │       ├── speaker/                    # ECAPA Speaker Verification
│   │       │   ├── SpeakerVerifier.kt    # ECAPA-TDNN ONNX logic
│   │       │   └── SpeakerVerificationManager.kt # Biometric orchestration
│   │       └── wakeword/                  # OpenWakeWord Pipeline
│   │ la          ├── WakeWordManager.kt    # Buffering and chunking logic
│   │ la          └── WakeWordDetector.kt   # 3-Stage (Mel->Emb->Clas) pipeline
│   └── ... (assets, res, jniLibs)
└── build.gradle.kts                       # Project build configuration
```

---

## 📂 Folder & File Role Definitions

### 1. `voice/` (The Voice AI Pipeline)
This is the core of the real-time interaction system. It follows a strict linear pipeline:
- **`AudioRecorder`**: Captures raw PCM 16-bit audio.
- **`VadManager`**: Uses **Silero VAD** to determine if the user is speaking, preventing the WakeWord model from running on silence (saves battery).
- **`wakeword/`**: Implements **OpenWakeWord**. It processes 1280-sample chunks through a Mel-Spectrogram $\rightarrow$ Embedding $\rightarrow$ Classifier pipeline to detect the "Hey Jarvis" trigger.
- **`speaker/`**: Implements **ECAPA-TDNN**. It extracts a voice embedding and compares it via cosine similarity to the owner's enrolled profile to ensure only the authorized user can trigger the AI.
- **`session/`**: Manages the lifecycle of the conversation, transitioning from "Wake Detected" to "Gemini Active".

### 2. `ai/` (The Brain Engine)
The decision-making center of ULTRON.
- **`brain/`**: The primary reasoning engine that parses user intent and decides which tool to use.
- **`conversation/`**: Manages the state of the dialogue, ensuring context is maintained across multiple turns.
- **`memory/`**: Stores user preferences and past interactions for personalized responses.
- **`tool/`**: A registry of capabilities (e.g., "Send SMS", "Set Timer") that the Brain can invoke.

### 3. `automation/` (System Control)
Allows ULTRON to interact with the Android OS as if it were a human user.
- **`UltronAccessibilityService`**: The heart of the automation, allowing the app to read the screen and perform clicks/swipes.
- **`AutoTyper`**: Simulates keyboard input for sending messages in apps like WhatsApp.
- **`ScreenReader`**: Analyzes the current view hierarchy to understand what is happening on screen.

### 4. `device/` (Hardware HAL)
Direct interfaces with Android hardware.
- **`CameraManager`**: Controls camera switching, flash, and capture.
- **`VolumeManager`**: Manages media, call, and alarm volume streams.
- **`BatteryMonitor`**: Tracks power levels to optimize AI processing (e.g., lowering sampling rate on low battery).

### 5. `apps/` (Third-Party Integration)
Specialized managers that know how to navigate and control specific external applications through a mix of APIs and Accessibility actions.

### 6. `comms/` (Communications)
Handles all telephony and messaging logic, providing a unified interface for the Brain to send SMS or manage calls.

### 7. `ui/` (User Interface)
The visual layer, focusing on a minimalist, high-end "Assistant" aesthetic with floating overlays and intuitive transitions.

---

## 🛠️ Technical Stack
- **Language**: Kotlin / Java
- **Inference**: ONNX Runtime (Android SDK)
- **Models**: Silero VAD, OpenWakeWord, ECAPA-TDNN, Gemini Live
- **OS**: Android 12+
- **Architecture**: Modular Service-Oriented Architecture (SOA)