# ULTRON AI Operating System - Project Structure

This document provides a comprehensive overview of the **ULTRON Assistant** project structure, listing all folders, files, and their architectural roles in short.

---

## 📁 Root Directory (`/`)

```text
e:\UltronAssistant
├── build.gradle.kts                      # Root Gradle build script
├── settings.gradle.kts                   # Project module & repository settings
├── gradle.properties                     # JVM and Gradle build configuration flags
├── local.properties                      # Local SDK & build secret configuration
├── export_ecapa.py                       # Python script for exporting/quantizing ECAPA-TDNN ONNX model
├── train_test.py                         # ML model evaluation & feature testing script
├── ARCHITECTURE_VERIFICATION_REPORT.md   # Architectural audit & verification documentation
├── CODEBASE_OVERVIEW.md                  # Comprehensive technical overview of ULTRON OS
├── PRODUCTION_STABILIZATION_REPORT.md    # Stability audit & production fix logs
├── REMEDIATION_PLAN.md                   # Systematic refactoring & remediation guide
├── SETUP_NOTES.md                        # Development setup and environment notes
├── Ultron_Assistant_PRD.md               # Product Requirements Document for ULTRON
└── VOICE_AI_INTEGRATION_REPORT.md        # Voice AI (VAD + openWakeWord + Speaker) integration report
```

---

## 🧠 ONNX Models & Assets (`app/src/main/assets/`)

```text
app/src/main/assets/
├── silero_vad.onnx                       # Silero Voice Activity Detection (VAD) model (2.3 MB)
├── melspectrogram.onnx                   # openWakeWord Stage 1: PCM Audio -> Mel-Spectrogram features (1.08 MB)
├── embedding_model.onnx                  # openWakeWord Stage 2: Mel-Spectrogram -> 96-dim Speech Embeddings (1.32 MB)
├── hey_jarvis.onnx                       # openWakeWord Stage 3: "Hey Jarvis" Classifier ONNX model (1.27 MB)
├── hey_ultron.onnx                       # openWakeWord Stage 3: "Hey Ultron" Classifier ONNX model (1.27 MB)
└── ecapa_tdnn.onnx                       # ECAPA-TDNN Speaker Verification embedding model (52 KB)
```

---

## 🏗️ Android Source Code Architecture (`app/src/main/java/com/flexfly/ultron/`)

```text
com/flexfly/ultron/
├── MainActivity.kt                       # Application entry point, UI host & permission sequence manager
├── UltronCameraActivity.kt               # Headless/custom camera activity for vision processing
│
├── voice/                                # PHASE 1-4: VOICE AI & SESSION PIPELINE
│   ├── AudioBuffer.kt                    # Thread-safe sliding PCM sample buffer
│   ├── AudioConfig.kt                    # Immutable hardware audio recording configuration
│   ├── AudioRecorder.kt                  # Hardware AudioRecord streaming loop (16kHz Mono 16-bit)
│   ├── VadManager.kt                     # Phase 1: Silero VAD state machine (Idle -> Speaking -> Ended)
│   ├── VadState.kt                       # Sealed class for Voice Activity Detection states
│   ├── VoiceActivityDetector.kt          # Interface for VAD implementations & SileroVadDetector
│   ├── VoiceService.kt                   # Foreground Service hosting all 10 engine components
│   │
│   ├── wakeword/                         # Phase 2: Wake Word Detection Engine
│   │   ├── WakeWordConfig.kt             # OpenWakeWord thresholds and window configuration
│   │   ├── WakeWordDetector.kt           # 3-Stage feature extractor (Mel -> Embedding -> Classifier)
│   │   ├── WakeWordManager.kt            # openWakeWord inference manager downstream of VAD
│   │   └── WakeWordState.kt              # Wake word detection state flow types
│   │
│   ├── speaker/                          # Phase 3: Speaker Verification Engine
│   │   ├── EnrollmentManager.kt          # Manages owner voice embedding enrollment (3-sample average)
│   │   ├── SpeakerConfig.kt              # Cosine similarity thresholds for speaker verification
│   │   ├── SpeakerEmbedding.kt           # FloatArray wrapper with cosine similarity calculation
│   │   ├── SpeakerStore.kt               # Persistent SharedPreferences store for owner voice profile
│   │   ├── SpeakerVerificationManager.kt # ECAPA-TDNN speaker verification downstream of Wake Word
│   │   ├── SpeakerVerifier.kt            # Interface & ECAPA-TDNN ONNX embedding extractor
│   │   └── VerificationResult.kt         # Sealed states: Verified, Rejected, NotEnrolled
│   │
│   ├── session/                          # Phase 4: Voice Session Orchestrator
│   │   ├── SessionConfig.kt              # Turn silence timeouts and initial utterance limits
│   │   ├── SessionMetrics.kt             # Real-time voice session statistics & counters
│   │   ├── SilenceDetector.kt            # Timer handling user turn silence timeouts
│   │   ├── VoiceSessionEvent.kt          # Event bus for session lifecycles
│   │   ├── VoiceSessionManager.kt        # Authoritative lifecycle manager for voice turns
│   │   └── VoiceSessionState.kt          # State Flow: Idle, WaitingForUser, Listening, Processing, Responding
│   │
│   └── ui/                               # Voice Debug UI Components
│       ├── VadDebugScreen.kt             # Real-time debug dashboard for VAD, Wake Word & Speaker
│       └── VadDebugViewModel.kt          # ViewModel observing VoiceService state flows
│
├── ai/                                   # COGNITIVE ENGINE LAYERS (Phases 5-8)
│   ├── CameraVisionManager.kt            # Image frame capture & ML Kit vision analysis
│   ├── GeminiLiveSession.kt              # Direct WebSocket client for Gemini Live API
│   ├── PromptBuilder.kt                  # System prompt builder with context injection
│   ├── ToolHandler.kt                    # Legacy bridge executing high-level assistant actions
│   ├── TranslationManager.kt             # ML Kit multi-language on-device translation
│   ├── WebAnswerClient.kt                # Web search & external Q&A client fallback
│   │
│   ├── conversation/                     # Phase 5: Conversation Engine
│   │   ├── ConversationConfig.kt         # Configuration for Gemini Live & Mock providers
│   │   ├── ConversationEngine.kt         # Manages full-duplex conversational audio & text streams
│   │   ├── ConversationEvent.kt          # Events for streaming audio chunks and text responses
│   │   ├── ConversationMetrics.kt        # Latency and token streaming metrics
│   │   ├── ConversationProvider.kt       # Abstract contract for AI conversation backends
│   │   ├── ConversationSession.kt        # State container for active conversation turn
│   │   ├── ConversationState.kt          # State Flow: Idle, Connecting, Connected, StreamingInput, Processing
│   │   ├── GeminiLiveProvider.kt         # Production WebSocket provider for Gemini Live Realtime API
│   │   ├── ProviderFactory.kt            # Factory selecting provider (Gemini Live vs Mock)
│   │   └── StreamingManager.kt           # Manages continuous bidirectional audio stream buffers
│   │
│   ├── brain/                            # Phase 6: Brain Engine
│   │   ├── BrainContext.kt               # Aggregated environment & user context snapshot
│   │   ├── BrainEngine.kt                # Central cognitive intelligence coordinating intent & planning
│   │   ├── BrainEvent.kt                 # Cognitive events (PlanGenerated, StrategySelected)
│   │   ├── BrainMetrics.kt               # Intent classification latency metrics
│   │   ├── BrainState.kt                 # State Flow: Idle, ClassifyingIntent, Planning, Executing
│   │   ├── ContextAssembler.kt           # Gathers memory, device status, and app state into context
│   │   ├── IntentClassifier.kt           # Classifies user utterances into structured Intents
│   │   ├── Planner.kt                    # Generates step-by-step ExecutionPlans for tools
│   │   ├── ReasoningPipeline.kt          # Multi-step reasoning pipeline for complex requests
│   │   └── ResponseStrategy.kt           # Selects response mode (Direct Speech vs Tool Execution)
│   │
│   ├── tool/                             # Phase 7: Tool Execution Framework
│   │   ├── ExecutionPipeline.kt          # Sequential/parallel step execution engine
│   │   ├── LegacyToolBridge.kt           # Compatibility layer mapping legacy tools to framework
│   │   ├── MockTool.kt                   # Test tool for framework validation
│   │   ├── PermissionManager.kt          # Tool permission gate & runtime validator
│   │   ├── Tool.kt                       # Abstract base contract for all ULTRON tools
│   │   ├── ToolCategory.kt               # Enum categorizing tools (System, Communication, Media, etc.)
│   │   ├── ToolDispatcher.kt             # Central router receiving ExecutionPlans and dispatching tools
│   │   ├── ToolExecutionEvent.kt         # Tool execution lifecycle events (Started, Finished, Failed)
│   │   ├── ToolExecutionState.kt         # State Flow: Idle, Validating, Executing, Success, Error
│   │   ├── ToolExecutor.kt               # Worker executing tool tasks with timeout safety
│   │   ├── ToolFactory.kt                # Dynamically instantiates tool instances
│   │   ├── ToolMetadata.kt               # Parameter schema and description for LLM tool selection
│   │   ├── ToolMetrics.kt                # Execution duration and success/failure metrics
│   │   ├── ToolRegistry.kt               # Central registry storing all available tools
│   │   └── ToolRequest.kt                # Encapsulates tool invocation parameters
│   │
│   └── memory/                           # Phase 8: Memory Engine
│       ├── MemoryCategory.kt             # Memory types (UserPreference, InteractionHistory, Fact)
│       ├── MemoryEngine.kt               # Core abstraction for storing, retrieving & ranking memory
│       ├── MemoryEvent.kt                # Memory mutation events
│       ├── MemoryMetrics.kt              # Memory lookup & storage metrics
│       ├── MemoryQuery.kt                # Query structure with relevance scoring parameters
│       ├── MemoryRanking.kt              # Ranks retrieved memories using time decay & semantic match
│       ├── MemoryRecord.kt               # Individual memory entry model with metadata
│       ├── MemoryRetentionPolicy.kt      # Manages expiration and pruning of old memories
│       ├── MemoryRetrievalStrategy.kt    # Strategy selector for context retrieval
│       ├── MemoryState.kt                # Memory engine lifecycle state
│       ├── MemoryStore.kt                # Memory persistence interface
│       └── PersistentFileStoreProvider.kt# JSON file-backed persistent memory store
│
├── android/                              # PHASE 9: ANDROID ACTION LAYER
│   └── action/                           # Framework Interaction Engine
│       ├── AndroidAction.kt              # Contract for OS-level operations
│       ├── AndroidActionCategory.kt      # Action types (DeviceControl, AppManagement, SystemSettings)
│       ├── AndroidActionDispatcher.kt    # Receives validated tool requests & executes OS actions
│       ├── AndroidActionEvent.kt         # OS action execution events
│       ├── AndroidActionExecutor.kt      # High-privilege executor interacting with Android Framework
│       ├── AndroidActionMetadata.kt      # OS action declarations and capabilities
│       ├── AndroidActionMetrics.kt       # OS action success/failure metrics
│       ├── AndroidActionPipeline.kt      # Pipeline handling multi-step OS workflows
│       ├── AndroidActionRegistry.kt      # Registry of all native Android actions
│       ├── AndroidActionRequest.kt       # Parameter model for OS action execution
│       ├── AndroidActionState.kt        # State Flow: Idle, Dispatched, Executing, Completed
│       ├── AndroidPermissionGateway.kt   # Validates OS permissions before execution
│       └── MediaPlaybackAction.kt        # Native media playback controller
│
├── plugin/                               # PHASE 10: PLUGIN SDK & EXTENSIONS
│   └── sdk/                              # Third-Party Modular Extension Platform
│       ├── Plugin.kt                     # Base interface for ULTRON extensions
│       ├── PluginCapability.kt           # Declared capabilities and capabilities check
│       ├── PluginContext.kt              # Sandboxed access context provided to plugins
│       ├── PluginEvent.kt                # Plugin lifecycle events
│       ├── PluginLifecycle.kt            # Plugin state machine (Loaded -> Enabled -> Disabled)
│       ├── PluginLoader.kt               # Dynamic plugin discovery & classloader
│       ├── PluginManager.kt              # Orchestrates extension plugins without core modifications
│       ├── PluginMetadata.kt             # Plugin identity, author, version and schema
│       ├── PluginMetrics.kt              # Plugin call metrics
│       ├── PluginRegistry.kt             # Central registry of loaded plugins
│       └── SampleMediaPlugin.kt          # Reference implementation of a Media Plugin
│
├── apps/                                 # App-Specific Integration Managers
│   ├── AppLaunchManager.kt               # Generic app launcher by package name / label
│   ├── AppOpener.kt                      # Deep link and intent app opener
│   ├── GoogleSearchManager.kt            # Google search intent handler
│   ├── MapsManager.kt                    # Google Maps navigation intent handler
│   ├── PlacesManager.kt                  # Nearby places lookup manager
│   ├── SpotifyManager.kt                 # Spotify playback intent integration
│   ├── WhatsAppManager.kt                # WhatsApp messaging & call automation
│   └── YouTubeManager.kt                 # YouTube search and video launcher
│
├── automation/                           # Accessibility & Screen Control
│   ├── AutoTyper.kt                      # Automated text typing via accessibility
│   ├── ScreenReader.kt                   # UI hierarchy reader & node tree parser
│   ├── ScrollManager.kt                  # Gesture scrolling automation (Up, Down, Swipes)
│   ├── TypingAccessibilityService.kt     # Specialized typing helper service
│   └── UltronAccessibilityService.kt     # Main Android Accessibility Service for UI automation
│
├── comms/                                # Calls, SMS & Emergency Telephony
│   ├── CallAnswerManager.kt              # Auto-answer & call management
│   ├── CallAudioManager.kt              # Audio routing during telephone calls
│   ├── CallReceiver.kt                   # BroadcastReceiver for incoming phone calls
│   ├── ContactManager.kt                 # Android Contacts provider query manager
│   ├── EmergencySOSManager.kt            # Emergency SMS & SOS call trigger
│   └── UltronSmsManager.kt               # Native SMS sending & reading engine
│
├── data/                                 # Local Storage & Database
│   ├── AppPreferences.kt                 # SharedPreferences helper (API keys, toggles, settings)
│   └── DatabaseHelper.kt                 # SQLite database helper for chat messages history (`mj_conversations.db`)
│
├── device/                               # Native Device Control Managers
│   ├── BatteryMonitor.kt                 # Battery state & charging status listener
│   ├── BrightnessManager.kt              # System display brightness controller
│   ├── CameraControlManager.kt           # Hardware camera trigger helper
│   ├── CameraManager.kt                  # CameraX image capture manager
│   ├── FlashlightManager.kt              # Camera torch / flashlight toggle manager
│   ├── NetworkToggleManager.kt           # Wi-Fi & Bluetooth setting intent toggles
│   ├── SystemInfoTools.kt                # System metrics (RAM, storage, OS version) tools
│   └── VolumeManager.kt                  # Audio stream volume manager (Alarm, Media, Ring)
│
├── info/                                 # External Information Providers
│   ├── NewsManager.kt                    # RSS/API news headline fetcher
│   └── WeatherManager.kt                 # Weather forecasting client
│
├── reminder/                             # Reminders & Alarms
│   └── ReminderManager.kt                # AlarmManager schedule helper for user reminders
│
├── security/                             # Anti-Theft & Security Features
│   └── AntiTheftManager.kt               # Anti-theft trigger & alarm manager
│
├── service/                              # System Services
│   └── UltronNotificationListener.kt     # Android NotificationListenerService for intercepting notifications
│
├── receiver/                             # Android Broadcast Receivers
│   ├── ChargingReceiver.kt               # Listens for power connect/disconnect events
│   ├── ReminderReceiver.kt               # Triggered by AlarmManager to fire scheduled reminders
│   └── UltronDeviceAdminReceiver.kt      # Device Admin receiver for screen lock & security
│
├── i18n/                                 # Internationalization & Transliteration
│   ├── HindiTransliterator.kt            # Hinglish/Devanagari text transliteration engine
│   └── Languages.kt                      # Supported language codes & metadata
│
├── overlay/                              # System Alert Window Overlays
│   └── CountdownOverlay.kt               # Floating countdown overlay window
│
├── model/                                # Core Data Models
│   └── Models.kt                         # Shared data classes (Message, Intent, ExecutionPlan, etc.)
│
├── audio/                                # Audio Playback Utilities
│   ├── AudioPlayerManager.kt             # Base64 PCM audio chunk player (AudioTrack)
│   └── AudioRecorderManager.kt           # Helper for mic recording sessions
│
└── ui/                                   # Jetpack Compose User Interface
    ├── AppRoot.kt                        # Master Compose scaffold & navigation host
    ├── Overlays.kt                       # Floating status widgets & indicators
    ├── theme/                            # Design System & Styling
    │   ├── Color.kt                      # Ultron dark/vibrant color palette
    │   └── Theme.kt                      # MaterialTheme wrapper
    └── screens/                          # Application View Screens
        ├── FeaturesScreen.kt             # Overview of active OS capabilities
        ├── HistoryScreen.kt              # Chat & voice session history log
        ├── HomeScreen.kt                 # Primary assistant screen with animated orb
        ├── OnboardingScreen.kt           # Initial setup & permission walkthrough
        ├── SettingsScreen.kt             # API key & preference configuration screen
        └── SplashScreen.kt               # Animated splash screen
```
