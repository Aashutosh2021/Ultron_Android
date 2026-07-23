# UltronAssistant - Complete Codebase Overview

**Last Updated**: July 19, 2026  
**Version**: 3.0  
**Platform**: Android (Min SDK 26, Target SDK 35)  
**Language**: Kotlin + Jetpack Compose

---

## Executive Summary

**UltronAssistant** is a sophisticated Android voice assistant that provides hands-free control of device functions through natural voice conversation. It integrates Google's Gemini Live API for real-time voice interaction, offline speech recognition via Vosk, and deep Android system automation through Accessibility Services.

**Core Philosophy**: Voice-first, always-on, personal device assistant with an "Ultron-from-Iron-Man" personality — blunt, direct, and conversational.

---

## Table of Contents

1. [Project Architecture](#project-architecture)
2. [Module Organization](#module-organization)
3. [Core Features](#core-features)
4. [Technology Stack](#technology-stack)
5. [Data Flow & Integration](#data-flow--integration)
6. [Data Models & Storage](#data-models--storage)
7. [Android Permissions & Components](#android-permissions--components)
8. [Background Services](#background-services)
9. [External API Integrations](#external-api-integrations)
10. [UI Architecture](#ui-architecture)
11. [Development Roadmap](#development-roadmap)
12. [Security & Privacy](#security--privacy)

---

## Project Architecture

### Build Configuration

```
Root: /e/UltronAssistant
├── app/
│   ├── src/main/
│   │   ├── java/com/flexfly/ultron/ (56 Kotlin files, 18,251 LOC)
│   │   ├── res/ (layouts, drawables, strings)
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts (main app config)
│   └── proguard-rules.pro (R8 minification)
├── build.gradle.kts (root config)
├── gradle/
├── settings.gradle.kts
├── Ultron_Assistant_PRD.md (product requirements)
└── local.properties (API keys - NOT committed)
```

**Build System**: Gradle + Kotlin DSL  
**Compilation Target**: Java 1.8  
**Key Features**:
- Jetpack Compose enabled
- BuildConfig generation for secrets
- R8 minification (release builds only)
- Native library page-alignment for Vosk

---

## Module Organization

The codebase is organized into **17 major packages** under `com.flexfly.ultron`:

### 1. **AI & LLM Integration** (`/ai`)

Core brain of the assistant — manages voice conversation with Google Gemini and processes AI responses.

| Component | Purpose |
|-----------|---------|
| `GeminiLiveSession` | WebSocket client for Gemini Live bidirectional streaming API. Handles audio up/down, detects `[TOOL:...]` tags in responses, manages VAD & turn-taking. |
| `ToolHandler` | Central dispatcher for 50+ device/app/communication commands. Routes `[TOOL:name:arg]` tags to appropriate managers. |
| `PromptBuilder` | Assembles system prompts for Gemini. Integrates persona, mechanics, memories, conversation history, anti-theft context. |
| `CameraVisionManager` | Analyzes captured images via Gemini Vision. |
| `TranslationManager` | On-device translation using ML Kit. |

**Key Interaction**:
1. Gemini responds with speech containing `[TOOL:flashlight:on]`
2. GeminiLiveSession detects & extracts tool tag
3. ToolHandler dispatches to FlashlightManager
4. Result injected back as `[SYSTEM]` message for Gemini context

---

### 2. **Automation & Input** (`/automation`)

Provides programmatic control of device UI and input — this is how the assistant "types" and "clicks" on your behalf.

| Component | Purpose |
|-----------|---------|
| `UltronAccessibilityService` | Core automation engine. Searches UI nodes, executes gestures (tap, swipe, long-press), captures screenshots, injects text via IME. |
| `TypingAccessibilityService` | Specialized input automation service. |
| `AutoTyper` | High-level typing operations. |
| `ScreenReader` | Extracts UI content for analysis. |
| `ScrollManager` | Programmatic scroll gestures. |

**Example Flow**: "Type my email address" → AutoTyper → TypingAccessibilityService → IME injection

---

### 3. **Communications** (`/comms`)

Handles phone calls, SMS, and messaging automation.

| Component | Purpose |
|-----------|---------|
| `CallAnswerManager` | Automatically answers incoming calls. |
| `CallAudioManager` | Manages ring volume and call audio routing. |
| `UltronSmsManager` | Sends SMS (multipart support) with contact awareness. |
| `ContactManager` | Contact search and resolution. |
| `EmergencySOSManager` | Emergency contact handling. |
| `CallReceiver` | Monitors phone state broadcasts. |

---

### 4. **Device Control** (`/device`)

Direct control of hardware features.

| Component | Purpose |
|-----------|---------|
| `CameraManager` | CameraX-based photo/video capture with countdown timer, front/back switching. |
| `CameraControlManager` | Flashlight integration. |
| `FlashlightManager` | Torch control via Camera2 API. |
| `VolumeManager` | Ring/media/call volume control. |
| `BrightnessManager` | Screen brightness adjustment. |
| `NetworkToggleManager` | WiFi/Bluetooth toggle. |
| `BatteryMonitor` | Battery status and optimization tracking. |
| `SystemInfoTools` | Device info (OS version, RAM, storage, etc.). |

---

### 5. **App Integration** (`/apps`)

Launches and controls third-party apps through Intent automation.

| Component | Purpose |
|-----------|---------|
| `AppLaunchManager` | Package lookup and app launching (50+ apps detected). |
| `WhatsAppManager` | Sends WhatsApp messages via accessibility automation. |
| `SpotifyManager` | Spotify playback control through UI automation. |
| `YouTubeManager` | YouTube search and playback. |
| `MapsManager` | Google Maps navigation. |
| `PlacesManager` | OpenStreetMap Nominatim places search. |
| `GoogleSearchManager` | Google Search intent launching. |

---

### 6. **Audio I/O** (`/audio`)

Handles microphone recording and audio playback.

| Component | Purpose |
|-----------|---------|
| `AudioRecorderManager` | PCM format microphone recording. |
| `AudioPlayerManager` | Audio playback with volume control. |

---

### 7. **Data & Persistence** (`/data`)

Local storage for conversations and user preferences.

| Component | Purpose |
|-----------|---------|
| `DatabaseHelper` | SQLite database (`mj_conversations.db`). Stores messages (id, role, content, timestamp). Formats history for Gemini API. |
| `AppPreferences` | SharedPreferences wrapper for 50+ settings. Covers service state, tool toggles, API keys, contacts, memories. |

---

### 8. **Information Services** (`/info`)

Provides real-time data to the assistant.

| Component | Purpose |
|-----------|---------|
| `WeatherManager` | OpenWeather API integration. Default city: Ahmedabad, IN. |
| `NewsManager` | News feed retrieval. |

---

### 9. **Reminders** (`/reminder`, `/receiver`)

Alarm scheduling and notification handling.

| Component | Purpose |
|-----------|---------|
| `ReminderManager` | Alarm scheduling and management. |
| `ReminderReceiver` | Alarm callback handler. |

---

### 10. **Notifications & Events** (`/receiver`)

System event monitoring.

| Component | Purpose |
|-----------|---------|
| `UltronNotificationListener` | Monitors all device notifications in real-time. Feeds AI awareness of incoming messages, calls, alerts. |
| `ChargingReceiver` | Listens to device charging events. |

---

### 11. **Security & Anti-Theft** (`/security`)

Device protection and license verification.

| Component | Purpose |
|-----------|---------|
| `AccessKeyVerifier` | Validates device license against remote server. Resets app on failure. |
| `AntiTheftManager` | Intruder detection via camera (stores description of unauthorized users). |
| `UltronDeviceAdminReceiver` | Device admin permission handler (lock screen, wipe capability). |

---

### 12. **Internationalization** (`/i18n`)

Multi-language support across Indian and global languages.

| Component | Purpose |
|-----------|---------|
| `Languages.kt` | Metadata for 53 languages (Hindi, Hinglish, Sanskrit, Tamil, Telugu, Kannada, Malayalam, Bengali, Assamese, Marathi, Gujarati, Punjabi, Odia, Urdu, regional dialects + 39 global languages). |
| `HindiTransliterator` | Devnagari script conversion. |

---

### 13. **Core Service** (`/service`)

The heart of the application — the always-on voice assistant service.

**`WakeWordService`** (Foreground Service):
- Vosk model loading for offline speech-to-text
- Continuous AudioRecord listening
- Gemini Live WebSocket session management
- Tool execution and callback injection
- Overlay UI rendering (Compose inside Service)
- Silence detection timeout (15s default, configurable)
- Charging and call event monitoring
- Lifecycle support: LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner

**Coroutine Structure**:
- IO Scope: Vosk processing, network operations
- Main Scope: UI updates, tool callbacks

---

### 14. **UI & Presentation** (`/ui`)

Jetpack Compose-based user interface.

| Component | Purpose |
|-----------|---------|
| `AppRoot` | Top-level navigation composable. Routes: SPLASH → ONBOARDING → MAIN |
| `SplashScreen` | 600ms fade transition, initialization check. |
| `OnboardingScreen` | 6+ step wizard (name, language, API keys, verification). Bilingual support. |
| `HomeScreen` | Main chat interface with message bubbles, start/stop button, input field. |
| `FeaturesScreen` | Scrollable feature cards with bilingual examples and emoji. |
| `HistoryScreen` | Reverse-chronological conversation display. |
| `SettingsScreen` | Tool toggles (20+), contact management, memories, preferences. |
| `Theme` | Dark/Light mode support, Material 3 components. |
| `Overlays` | EdgeGlowOverlay, GhostPointerRipple, CountdownOverlay (rendered in Service). |

---

### 15. **Data Models** (`/model`)

Core data structures.

```kotlin
Message(id, role: "user"|"assistant", content, timestamp)
FeatureItem(emoji, titles, descriptions, examples, accentColor)
LanguageInfo(name, code, nativeText)
OnboardStep(title, description, icon)
KeyVerifyState(sealed: Verifying, Verified, Failed)
```

---

### 16. **Main Activities**

**`MainActivity`** (Launcher, singleTop):
- 14-step permission chain (~500 LOC)
  1. RECORD_AUDIO
  2. POST_NOTIFICATIONS (Android 13+)
  3-11. Call, SMS, Contacts, Overlay, Settings, Alarms, Location, Bluetooth
  12-14. Camera, Media Files
- Database & preferences initialization
- Battery optimization exemption request
- Access key verification on startup
- Notification listener permission check
- Compose UI root setup

**`UltronCameraActivity`** (Standalone):
- CameraX preview + shutter UI
- Photo/video capture to MediaStore
- Front/back camera flip
- Optional vision analysis trigger

---

### 17. **Broadcast Receivers** (`/receiver`)

System event listeners.

| Component | Purpose |
|-----------|---------|
| `CallReceiver` | Intercepts phone state broadcasts. |
| `ChargingReceiver` | Monitors device charging state. |
| `ReminderReceiver` | Handles alarm callbacks. |

---

## Core Features

### 1. **Voice Conversation**
- **Technology**: Google Gemini Live API (full-duplex, native audio)
- **Always-On**: Vosk offline STT triggers "Hey Ultron" wake word
- **Session Management**: Auto-reconnect at ~9 minutes, idle-timeout at 15s silence
- **Tool Integration**: Model detects `[TOOL:...]` tags in speech, triggers device actions

### 2. **Hands-Free Automation**
- **UI Control**: Accessibility Service for app automation
- **Typing**: Input injection via IME
- **Screenshots**: Real-time screen capture & analysis
- **Scrolling**: Programmatic gesture control

### 3. **Communications**
- **Calls**: Place/answer calls, control audio routing
- **SMS**: Send/receive multipart messages
- **WhatsApp**: Send messages via accessibility automation
- **Contacts**: Full contact database access & resolution

### 4. **Device Control**
- **Camera**: Photo/video capture, flashlight control
- **Audio**: Volume control (ring, media, call)
- **Display**: Brightness adjustment, screen control
- **Network**: WiFi/Bluetooth toggle
- **Power**: Battery monitoring, optimization tracking

### 5. **App Integration**
- **50+ Apps Detected**: WhatsApp, Instagram, YouTube, Maps, Gmail, Spotify, Netflix, Uber, Zomato, PayTM, Flipkart, gaming apps, office apps
- **Intent-Based Launching**: Direct app control
- **Navigation**: Maps directions, search

### 6. **Information Services**
- **Weather**: OpenWeather API (default city configurable)
- **News**: Real-time news feeds
- **System Info**: Device specs, storage, RAM
- **Location**: GPS coordinates

### 7. **Multi-Language Support**
- **53 Languages**: Hindi, Hinglish, Sanskrit, Tamil, Telugu, Kannada, Malayalam, Bengali, Assamese, Marathi, Gujarati, Punjabi, Odia, Urdu + 39 global languages
- **On-Device Translation**: ML Kit
- **Script Support**: Devnagari transliteration

### 8. **Persistent Memory** (Phase 4 - Planned)
- **SQLite Storage**: Conversation history with embeddings
- **Recall Tool**: `recall_memory(query)` for context retrieval
- **Similarity Search**: Local brute-force cosine similarity

### 9. **Speaker Identification** (Phase 3 - In Progress)
- **ECAPA-TDNN Model**: Voice profile embedding (TFLite)
- **Enrollment**: "Boss" vs. enrolled contacts vs. unknown speakers
- **Privacy**: Explicit consent before storing voice profiles
- **Deletion**: "Forget my voice" capability

### 10. **Security & Anti-Theft**
- **License Verification**: Remote device validation
- **Intruder Detection**: Camera-based unauthorized access logging
- **Device Admin**: Lock screen, wipe capability

---

## Technology Stack

### AndroidX & Jetpack
- **Core**: `androidx.core:core-ktx` (1.13.1)
- **AppCompat**: `androidx.appcompat:appcompat` (1.7.0)
- **ConstraintLayout**: `androidx.constraintlayout:constraintlayout` (2.1.4)
- **Compose**: BOM 2025.05.00
  - `compose.ui`, `compose.material3`, `compose.material.icons.extended`
  - `activity-compose` (1.10.1)
  - `navigation-compose` (2.8.0)
- **Lifecycle**: `lifecycle-runtime-ktx` (2.9.0), `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-process`
- **DataStore**: `datastore-preferences` (1.1.1)
- **Camera**: CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`, `camera-extensions`) — 1.3.4

### Networking & Communication
- **OkHttp**: `okhttp3:okhttp` (4.12.0)
- **WebSocket**: `org.java-websocket:Java-WebSocket` (1.5.7) — Gemini Live API
- **Location**: `play-services-location` (21.3.0)

### AI & ML
- **Gemini API**: Raw WebSocket (via Java-WebSocket)
- **Vosk STT**: `com.alphacephei:vosk-android` (0.3.38) — offline speech recognition
- **JNA**: `net.java.dev.jna:jna` (5.13.0) — native library binding for Vosk
- **ML Kit Translation**: `com.google.mlkit:translate` (17.0.3) — on-device translation

### Concurrency
- **Coroutines**: `kotlinx-coroutines-android` (1.8.1), `kotlinx-coroutines-play-services`

### UI & Material Design
- **Material 3**: `com.google.android.material:material` (1.12.0)

### Build System
- **AGP**: 8.7.0
- **Kotlin**: 2.0.21
- **Gradle**: Latest with Kotlin DSL

---

## Data Flow & Integration

### Voice Conversation Flow

```
User: "Hey Ultron, turn on the flashlight"
  ↓
[Vosk Wake Word Detector]
  ├─ Runs continuously in WakeWordService
  ├─ Detects "Hey Ultron" locally
  └─ Triggers audio capture
  ↓
[AudioRecorderManager]
  └─ Captures PCM audio chunks
  ↓
[GeminiLiveSession] (WebSocket)
  ├─ Sends audio to Google Gemini Live API
  ├─ Receives streaming audio response
  └─ Detects [TOOL:flashlight:on] tag
  ↓
[ToolHandler]
  └─ Parses tool command
  ↓
[FlashlightManager]
  └─ Calls Camera2 API to turn on flashlight
  ↓
[System Response]
  ├─ Tool result injected as [SYSTEM] message
  ├─ Sent back to Gemini for context
  └─ Gemini continues conversation: "Done, flashlight is on"
  ↓
[AudioPlayerManager]
  └─ Plays Gemini's audio response

Silence Detected (15s) → Session closes
```

### Message Storage

```
Chat Input
  ↓
DatabaseHelper.insertMessage(role="user", content="...")
  ↓
SQLite mj_conversations.db
  └─ messages table (id, role, content, timestamp)
  ↓
getHistoryAsJson() → Format for Gemini API
  ├─ {"role": "user", "parts": [{"text": "..."}]}
  └─ Sent in next Gemini request
```

### Preferences & Configuration

```
AppPreferences (SharedPreferences wrapper)
  └─ Backing store: max_app_prefs
  ├─ User Settings: name, language, API keys
  ├─ Service State: enabled/disabled
  ├─ Tool Toggles: 20+ boolean flags
  ├─ Contacts: favorite_contacts, emergency_contacts (JSON)
  ├─ Memories: user_memories (JSON array)
  └─ Security: last_intruder_description, captured_photo_uri
```

---

## Data Models & Storage

### SQLite Database (`mj_conversations.db`)

```sql
CREATE TABLE messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  role TEXT,          -- "user" or "assistant"
  content TEXT,       -- message text
  timestamp INTEGER   -- milliseconds
);
```

### Message Format for Gemini API

```json
{
  "contents": [
    {
      "role": "user",
      "parts": [{"text": "What's the weather?"}]
    },
    {
      "role": "model",
      "parts": [{"text": "[TOOL:weather]... It's sunny in Ahmedabad"}]
    }
  ]
}
```

### SharedPreferences Keys (50+)

**Service & State**:
- `service_enabled` (Boolean)
- `onboarding_completed` (Boolean)

**User Info**:
- `user_name`, `onboarding_name`, `language`

**API Keys**:
- `access_key`, `google_api_key`, `llama_vision_api_key`, `openweather_api_key`

**Tool Toggles** (20+):
- `tool_camera`, `tool_flashlight`, `tool_sms`, `tool_maps`, `tool_youtube`, `tool_spotify`, etc.

**Contacts & Communication**:
- `favorite_contacts` (JSON array)
- `emergency_contacts` (JSON array)
- `last_call_contact`, `last_message_recipient`

**Memories & History**:
- `user_memories` (JSON array)
- `conversation_count`, `total_sessions`

**Location**:
- `last_saved_latitude`, `last_saved_longitude`, `last_saved_location_time`

**Security & Anti-Theft**:
- `last_intruder_description`, `last_captured_photo_uri`

**Settings**:
- `max_active_timeout` (15000ms default)
- `tool_auto_type`, `tool_screen_reader`, `call_control_enabled`
- `weather_city`

---

## Android Permissions & Components

### Permission Chain (14 Steps)

1. **RECORD_AUDIO** — Wake-word detection & voice input
2. **POST_NOTIFICATIONS** (Android 13+) — Foreground service notification
3. **CALL_PHONE** — Place calls
4. **READ_CONTACTS, WRITE_CONTACTS** — Contact access
5. **SYSTEM_ALERT_WINDOW** — Overlay UI rendering
6. **WRITE_SETTINGS** — System settings modification
7. **SCHEDULE_EXACT_ALARM** (Android 12+) — Reminder scheduling
8. **SEND_SMS** — SMS sending
9. **ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION** — GPS
10. (Skipped)
11. **READ_PHONE_STATE, ANSWER_PHONE_CALLS, READ_CALL_LOG** — Call control
12. **BLUETOOTH_CONNECT, BLUETOOTH_ADMIN** (Android 12+) — Bluetooth
13. **CAMERA** — Photo/video capture & flashlight
14. **READ_MEDIA_IMAGES/VIDEOS** (Android 13+) or **WRITE/READ_EXTERNAL_STORAGE** — Media access

### Critical System Permissions

- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` — Keep service alive with mic access
- `SYSTEM_ALERT_WINDOW` — Draw overlays
- `BIND_NOTIFICATION_LISTENER_SERVICE` — Monitor notifications
- `BIND_DEVICE_ADMIN` — Device admin capabilities
- `BIND_ACCESSIBILITY_SERVICE` — UI automation
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Doze mode exemption
- `QUERY_ALL_PACKAGES` — Detect installed apps

### Registered Components

| Component | Type | Purpose |
|-----------|------|---------|
| `MainActivity` | Activity (Launcher, singleTop) | Entry point |
| `UltronCameraActivity` | Activity | Standalone camera capture |
| `WakeWordService` | Foreground Service | Always-on voice listener |
| `UltronNotificationListener` | Notification Listener Service | Real-time notification monitoring |
| `UltronAccessibilityService` | Accessibility Service | UI automation |
| `UltronDeviceAdminReceiver` | Device Admin Receiver | Lock/wipe capability |
| `CallReceiver` | Broadcast Receiver | Phone state events |
| `ChargingReceiver` | Broadcast Receiver | Charging events |
| `ReminderReceiver` | Broadcast Receiver | Alarm callbacks |
| `FileProvider` | Provider | Media file access |

### Queried Packages (50+)

WhatsApp, Instagram, YouTube, Maps, Gmail, Google Drive, Spotify, Netflix, Uber, Uber Eats, Zomato, PayTM, Google Pay, PhonePe, Flipkart, Amazon, Jio, Truecaller, YouTube Music, Telegram, Discord, Slack, MS Word, Excel, Outlook, PDF Reader, Calculator, Calendar, Contacts, Messages, Phone, Chrome, Firefox, Edge, Settings, Clock, Maps, Camera, Gallery, Files, Downloads, etc.

---

## Background Services

### WakeWordService (Foreground Service)

**Lifecycle**:
- Started by MainActivity or on device boot (not persistent foreground service in current code)
- Sticky: Attempt to restart if killed
- Notification requirement: Always displays notification for user awareness

**Key Responsibilities**:
1. Load Vosk offline speech model
2. Open AudioRecord to continuous microphone stream
3. Detect wake word locally
4. On wake-word trigger:
   - Connect to Gemini Live API (WebSocket)
   - Stream audio to Gemini
   - Receive and play response audio
   - Extract `[TOOL:...]` tags
   - Dispatch to ToolHandler
   - Inject results back into conversation
5. Close session on 15s silence timeout
6. Manage overlay UI (Compose in Service)
7. Monitor charging/call events

**Coroutine Architecture**:
```kotlin
val ioScope = CoroutineScope(Dispatchers.IO + Job())    // Vosk, network
val mainScope = CoroutineScope(Dispatchers.Main)         // UI updates
```

**Lifecycle Support**:
- Implements `LifecycleOwner`, `ViewModelStoreOwner`, `SavedStateRegistryOwner`
- Allows Compose components to observe lifecycle inside Service

---

### UltronAccessibilityService

**Purpose**: Provides programmatic control of device UI

**Capabilities**:
- Window change detection
- UI node tree traversal
- Gesture execution (tap, swipe, long-press, pinch)
- Screenshot capture
- Text injection via IME
- Action execution on accessibility nodes

**Triggered By**: Automation tool commands (`[TOOL:click:...]`, `[TOOL:type:...]`, etc.)

---

### UltronNotificationListener

**Purpose**: Real-time awareness of device notifications

**Data Captured**:
- Package name
- Notification title & text
- Timestamp

**Use Case**: AI can reference "You have a message from Mom" without tool-calling

---

## External API Integrations

### Google Gemini Live API

**Purpose**: Voice conversation engine (STT + LLM + TTS)

**Connection**: WebSocket (Java-WebSocket library)

**Protocol**:
- Audio chunks streamed as binary frames
- Model responses with embedded tool tags: `[TOOL:...]`
- Session resumption at ~9 minutes (via server handle)

**Setup** (in OnboardingScreen):
- User enters Google API key
- Stored in SharedPreferences
- Injected into WebSocket auth header

---

### Google Maps API

**Purpose**: Navigation, location visualization

**Integration**: MapsManager (Intent-based)

**Flow**: "Navigate to Mumbai" → Launch Maps Intent → Maps app opens with destination

---

### OpenStreetMap Nominatim API

**Purpose**: Places search (no API key required)

**Integration**: PlacesManager

**Flow**: "Find nearby restaurants" → HTTP request to Nominatim → Results returned

---

### OpenWeather API

**Purpose**: Weather data

**Integration**: WeatherManager

**Configuration**:
- Default API key: BuildConfig.OPENWEATHER_DEFAULT_KEY
- Override in SharedPreferences: `openweather_api_key`
- Default city: "Ahmedabad,IN" (configurable)

---

### Google Play Location Services

**Purpose**: Precise GPS coordinates

**Integration**: Play Services API

**Permissions**: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION

---

### License Server

**Purpose**: Device verification and anti-piracy

**Endpoint**: Remote License Verification Server

**Flow**:
1. AccessKeyVerifier on app startup
2. Sends Android ID + access_key
3. Server validates
4. On failure: App state reset, service disabled

---

### Gmail API

**Purpose**: Email sending/reading (Phase 6)

**Integration**: OAuth device flow

**Status**: Not yet implemented

---

## UI Architecture

### Navigation Structure

```
AppRoot (Compose Navigation)
  ├─ [Splash (600ms)]
  │   └─ Transition decision based on onboarding_completed
  │
  ├─ [Onboarding] (if not completed)
  │   ├─ Step 1: Name input
  │   ├─ Step 2: Language selection (53 languages)
  │   ├─ Step 3: API key entry (Gemini, Maps, etc.)
  │   ├─ Step 4: Device verification
  │   ├─ Step 5: Accessibility Service setup
  │   └─ Step 6: Final confirmation
  │
  └─ [Main Shell] (Bottom Tab Navigation)
      ├─ Home Tab
      │   ├─ Chat bubble interface
      │   ├─ Start/Stop button
      │   ├─ Message input field
      │   └─ Real-time response display
      │
      ├─ Features Tab
      │   ├─ Scrollable feature cards
      │   ├─ Bilingual text (English + selected language)
      │   ├─ Emoji icons
      │   └─ Example prompts
      │
      ├─ History Tab
      │   └─ Reverse-chronological message list
      │
      └─ Settings Tab
          ├─ Tool toggles (20+)
          ├─ Contact management (favorites, emergency)
          ├─ Memories viewer
          ├─ Language switcher
          ├─ API key management
          └─ About / Help
```

### Theme & Styling

- **Dark Mode**: Primary theme (UltronDark)
- **Material 3**: Compose Material3 components
- **Custom Colors**:
  - `BgDark`: Primary background
  - `UltronRed`: Accent color
  - `SubText`: Secondary text
  - Material 3 color schemes

### Overlay UI Components

**Rendered in WakeWordService** (Compose in Service):

1. **EdgeGlowOverlay** — Visual feedback during edge gestures
2. **GhostPointerRipple** — Pointer visualization for clicks
3. **CountdownOverlay** — Camera countdown timer display

---

## Development Roadmap

### Completed (Phase 1–2)
- ✅ Wake-word detection (Vosk)
- ✅ Gemini Live session manager
- ✅ Audio I/O pipeline
- ✅ Idle-timeout & resumption
- ✅ Tool-calling framework

### In Progress (Phase 3)
- 🟡 Device control via tool-calling
- 🟡 Speaker ID & enrollment
- 🟡 Voice profile embeddings

### Not Started (Phase 4–6)
- ⬜ Persistent memory (`recall_memory` tool)
- ⬜ Calls/SMS integration
- ⬜ Email (Gmail API)
- ⬜ XOS background survival hardening

### Known Issues & Risks
- ⚠️ OpenWakeWord API surface not fully confirmed
- ⚠️ Gemini Live audio/preview tier pricing unclear
- ⚠️ Model string (`gemini-3.1-flash-live-preview`) may shift
- ⚠️ ECAPA-TDNN TFLite conversion path untested
- ⚠️ Silence RMS threshold (500.0) needs device-specific tuning
- ⚠️ Enrolled contacts' device-control authority: undecided policy question

---

## Security & Privacy

### Data Storage

- **Conversations**: SQLite (local, unencrypted)
- **Preferences**: SharedPreferences (local, unencrypted)
- **Voice Profiles**: On-device embeddings (local, not synced)
- **API Keys**: SharedPreferences (unencrypted — consider EncryptedSharedPreferences)

### Permissions & Access Control

- **Accessibility Service**: Full UI automation access (intentional)
- **Notification Listener**: All app notifications (intentional, for context)
- **Device Admin**: Lock screen, wipe capability (intentional)
- **Audio Recording**: Always-on microphone (necessary for wake-word)

### Privacy Principles (from PRD §13)

1. **Explicit Consent**: Voice enrollment requires out-loud statement before storage
2. **Deletion**: "Forget my voice" capability must actually delete embeddings
3. **No Inference**: Acoustic gender classification explicitly excluded
4. **Local by Default**: No cloud sync without explicit user decision

### License Verification

- Remote validation against license server
- App disables on verification failure
- Hard billing cap required on Google Cloud project

### Anti-Theft Features

- Camera-based intruder detection
- Stores description of unauthorized access attempts
- Device admin capabilities for remote lock/wipe

---

## Build & Deployment

### Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (with R8 minification)
./gradlew assembleRelease

# Requires local.properties with API keys:
# OPENWEATHER_DEFAULT_KEY=...
```

### Installation

- **Target Device**: Infinix Note 50s (MediaTek Dimensity 7300, 8GB RAM, Android 15)
- **Distribution**: Sideload only (not Play Store)
- **Manual Setup**:
  1. Battery optimization exemption
  2. Infinix Security → Autostart → Enable
  3. Infinix Security → Protected Apps → Whitelist app

### Version Management

- **Current**: v3.0 (versionCode: 3)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)

### R8 Minification

- **Release only**: R8 shrinking enabled, renaming disabled
- **ProGuard rules**: `app/proguard-rules.pro`

---

## Key Statistics

| Metric | Value |
|--------|-------|
| **Kotlin Files** | 56 |
| **Total LOC** | 18,251 |
| **Major Packages** | 17 |
| **Activities** | 2 |
| **Services** | 3 |
| **Broadcast Receivers** | 3 |
| **Supported Languages** | 53 |
| **Tool Commands** | 50+ |
| **Permissions Requested** | 30+ |
| **Direct Dependencies** | 15+ |
| **SharedPreferences Keys** | 50+ |

---

## Summary

**UltronAssistant** is a comprehensive, production-grade Android assistant that demonstrates deep integration with system APIs, modern Jetpack technologies, and sophisticated AI orchestration. The architecture cleanly separates concerns (AI, automation, device control, comms, data), making it maintainable and extensible.

**Key Strengths**:
- Voice-first design with always-on capability
- Offline wake-word + online reasoning + local memory
- 53-language support with bilingual UI
- Extensive device automation via Accessibility Service
- Tool-based AI response execution (clean separation)

**Development Areas**:
- Speaker identification (in progress)
- Persistent semantic memory (planned)
- Email integration (planned)
- Background service hardening for XOS (manual)

---

*Document compiled from codebase analysis (July 2026)*
