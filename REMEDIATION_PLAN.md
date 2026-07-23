# ULTRON — Remediation Plan

**Source:** `ARCHITECTURE_VERIFICATION_REPORT.md`
**Status:** Proposed — awaiting approval. **No code will be modified until this plan is approved.**
**Date:** 2026-07-20

---

## How to read this plan

- Issues are grouped **Critical → High → Medium → Low**, and **ordered within each tier by execution dependency** (do the earlier ones first — later fixes often can't be verified until earlier ones land).
- Each issue lists: **Root cause · Affected files · Risk of change · Recommended fix · Effort**.
- Effort is in ideal engineering days for one engineer familiar with the codebase: **S** = ≤0.5d, **M** = 1–2d, **L** = 3–5d, **XL** = >5d.
- **Risk of change** = the blast radius / regression danger of *doing* the fix, not the severity of the bug.

### The one decision that gates everything

Before any Critical work starts, a **product/architecture decision** is required (report §17 P0-1):

> **Is the 10-phase `VoiceService` stack the intended production runtime, or is the legacy `WakeWordService` + `GeminiLiveSession` + `ToolHandler` the product?**

The two are parallel and unconnected. This plan assumes the answer is **"the 10-phase stack becomes production, reusing legacy pieces as real backends."** If instead the 10-phase stack is to be shelved as a prototype, then C-1/C-2/C-3/C-5/C-7 collapse into a single "mark as non-shipping / move to a `dev` flavor" task (~S) and the rest of this plan is moot. **Confirm this before proceeding.**

---

## Recommended execution order (critical path)

```
Decision gate
   └─► C-1 entrypoint ─► C-2 audio bus ─► C-4 ship models ─► C-3 real provider ─► C-5 turn wiring
                                                                      │
                                                         ┌────────────┴───────────┐
                                                    C-7 tool bridge          C-6 real permissions
                                                         │
                                              (Critical tier verifiable end-to-end here)
   H-1 retire dual service ─► H-2/H-3 memory ─► H-6 ONNX mutex ─► H-4/H-5 brain/plugin
   M-1..M-6 hardening ─► L-1..L-5 polish
```

Rationale: nothing downstream can be integration-tested until the app actually *runs* the stack (C-1), audio actually *flows* (C-2), and a model is actually *present* (C-4). Those three are the unblockers.

---

# CRITICAL

> Each independently blocks a "production-ready" verdict. Do in the order below.

### C-1 — 10-phase stack is unreachable from the running app
- **Root cause:** `MainActivity` starts the legacy `WakeWordService`; the new `VoiceService` is only started from `VadDebugViewModel`, which no navigation route reaches. Two runtimes, only the legacy one wired to launch.
- **Affected files:** `MainActivity.kt` (~:297), `voice/VoiceService.kt`, `voice/ui/VadDebugViewModel.kt`, `AndroidManifest.xml` (services :142–143), `ui/AppRoot.kt` (nav).
- **Risk of change:** **High.** Switching the entrypoint changes the entire app's runtime behavior; the legacy service currently *works* (we debugged it earlier). Flipping to an unfinished stack regresses shipping functionality unless done behind a flag.
- **Recommended fix:** Do **not** hard-swap. Introduce a runtime toggle (BuildConfig flag or a hidden setting) selecting legacy vs. new service. Keep legacy as default until the Critical tier is complete and verified. This makes C-2…C-7 testable on-device without a user-facing regression.
- **Effort:** **M** (toggle + wiring + manifest confirm). The full switch-to-default is deferred to end of Critical tier.

### C-2 — Audio pipeline severed after VAD
- **Root cause:** `VadManager` consumes the mic `Flow` itself and republishes only `VadState`; raw frames never leave it. `WakeWordManager.processAudioFrame(...)` and the speaker path have **no audio feeder**.
- **Affected files:** `voice/VadManager.kt` (:75–90), `voice/wakeword/WakeWordManager.kt` (:93–121), `voice/speaker/SpeakerVerificationManager.kt` (:73–84), `voice/AudioRecorder.kt` (source), `voice/VoiceService.kt` (composition).
- **Risk of change:** **Medium.** Introduces a real fan-out of the audio stream; must avoid double-consuming `AudioRecord` (only one reader allowed) and must not block the capture loop. Backpressure handling matters.
- **Recommended fix:** Make `AudioRecorder`'s frame `Flow` the single source, consumed once and fanned out (e.g. a `SharedFlow` or explicit per-consumer push) so VAD, wake-word, and speaker each receive frames. Feed `WakeWordManager.processAudioFrame` from this bus; gate wake-word inference on `VadState.Speaking` (already the intended design). Keep the fan-out off the Main thread. Reuse the already-thread-safe `AudioBuffer` (ReentrantLock, verified).
- **Effort:** **L.** This is the core integration and the highest-value fix.

### C-4 — Neural models missing; detectors silently degrade to stubs
- **Root cause:** Code loads `silero_vad.onnx`, `hey_ultron.onnx`, `ecapa_tdnn.onnx`; none are in `assets/`. Each `init{}` swallows the missing-asset exception and installs a DSP/no-op fallback (wake-word fallback returns constant `0.0f`).
- **Affected files:** `app/src/main/assets/` (add models), `voice/VoiceActivityDetector.kt` (:59–72), `voice/wakeword/WakeWordDetector.kt` (:53–63), `voice/speaker/SpeakerVerifier.kt` (:54–64).
- **Risk of change:** **Medium.** Adding ~MBs of ONNX assets increases APK size and must be page-aligned/verified on-device; wrong model I/O shapes cause runtime inference exceptions. Model licensing must be confirmed.
- **Recommended fix:** (a) Obtain and ship the three verified models (Silero VAD v4/v5, an openWakeWord "hey ultron" model — note this needs *training* per the PRD, or a stock placeholder — and a converted ECAPA-TDNN). (b) Change release behavior so a **missing model fails loudly** (or is surfaced in a health check) instead of silently degrading — silent fallback is what disguises a broken build as a working one. Keep DSP fallback only under a debug flag.
- **Effort:** **L** for VAD + ECAPA (obtain/convert/verify shapes). **XL** if the custom "hey ultron" wake model must be trained (PRD flags this as not-started). Sequence: ship Silero + ECAPA first; wake model can start with a stock keyword.
- **Dependency note:** must land before C-3 verification (you can't test the voice path with no VAD) and before H-6.

### C-3 — Conversation provider performs no real network I/O (mocked LLM)
- **Root cause:** `GeminiLiveProvider.connect()` is `delay(300L)`; `sendAudio()` only counts bytes; empty key swaps in `MockConversationProvider` emitting hardcoded strings. No WebSocket, no reconnection.
- **Affected files:** `ai/conversation/GeminiLiveProvider.kt`, `ai/conversation/StreamingManager.kt`, `ai/conversation/ProviderFactory.kt`, `ai/conversation/ConversationEngine.kt` (already maps events correctly). **Reuse:** `ai/GeminiLiveSession.kt` (the *working* legacy Live WebSocket client).
- **Risk of change:** **Medium-High.** Real streaming introduces network failure modes, threading, and audio-format concerns the mock hid. Must not duplicate the legacy client's hard-won behavior.
- **Recommended fix:** Implement `GeminiLiveProvider` as a **thin adapter over the existing `GeminiLiveSession`** rather than a from-scratch WebSocket. Map `ConversationProvider` calls (`connect/sendAudio/cancelGeneration/disconnect`) onto the legacy session's methods, and legacy callbacks onto `ConversationEvent`s. This inherits the real endpoint, resumption, and tool-tag handling already in production. Add genuine reconnection at the adapter boundary.
- **Effort:** **L** (adapter) — far less than **XL** if rebuilt from scratch.
- **Dependency note:** verify after C-2 (needs real audio in) and C-4 (needs VAD to gate turns).

### C-5 — Session state machine cannot advance (turn-taking API unwired)
- **Root cause:** `VoiceSessionManager.onUserSpeechStarted/Ended`, `onAssistantStartedSpeaking/Finished` have **zero callers**. Session reaches `WaitingForUser` then only times out.
- **Affected files:** `voice/session/VoiceSessionManager.kt` (:114–173), driver wiring in `voice/VadManager.kt` (speech start/end → session) and `ai/conversation/ConversationEngine.kt` (assistant start/finish → session).
- **Risk of change:** **Medium.** Adds cross-component event coupling; risk of races between VAD speech events, provider generation events, and the session machine (interruption handling especially).
- **Recommended fix:** Wire `VadState.SpeechStarted/SpeechEnded` → `onUserSpeechStarted/onUserSpeechEnded`, and `ConversationEvent.Generating/FinalResponse` (or provider audio start/stop) → `onAssistantStartedSpeaking/onAssistantFinishedSpeaking`. This is pure event routing in `VoiceService`/managers, and it also fixes M-1's observability need (real consumers of transient states).
- **Effort:** **M.**
- **Dependency note:** requires C-2 (speech events real) and C-3 (assistant events real) first.

### C-7 — New tool/action framework disconnected from real device layer
- **Root cause:** `ToolFactory` registers 3 self-contained tools; the new `ai/tool`, `ai/brain`, `android/action` packages reference the real 60-tool `ai/ToolHandler.kt` nowhere. Phase 9 has one concrete action (`MediaPlaybackAction`, which *is* real).
- **Affected files:** `ai/tool/ToolFactory.kt`, `ai/tool/ToolDispatcher.kt`, `android/action/AndroidActionRegistry.kt`, bridge to `ai/ToolHandler.kt`, `ai/brain/Planner.kt` (produces `requiredTools` names).
- **Risk of change:** **Medium.** Bridging to the legacy dispatcher means the new permission pipeline (C-6) and the legacy per-tool setting checks must not conflict or double-execute. Tool-name namespaces must be reconciled (Planner emits names; `ToolHandler` expects `[TOOL:...]` grammar).
- **Recommended fix:** Introduce an adapter `Tool` implementation that delegates execution to `ToolHandler.handle(name, context)`, and populate `ToolRegistry` from the legacy tool catalog (or route `BrainEvent.ToolRequested` through a bridge that calls `ToolHandler`). Decide whether the new framework *owns* execution or *delegates* — recommend delegate-to-legacy initially to avoid reimplementing 60 tools.
- **Effort:** **L.**
- **Dependency note:** pairs with C-6; do C-6 first so the bridge runs under real permission checks.

### C-6 — Permission pipeline auto-grants everything (security-critical)
- **Root cause:** `PermissionManager.requestPermissions()` does `grantedPermissions.addAll(missing); return true` — never consults Android permission state.
- **Affected files:** `ai/tool/PermissionManager.kt`, `ai/tool/ExecutionPipeline.kt` (:34–45), `android/action/AndroidPermissionGateway.kt`, an Activity-bound requester (`MainActivity.kt`).
- **Risk of change:** **Medium.** Real permission checks will start *failing* tool executions that the mock silently allowed — this is correct but will surface as "tools stopped working" until the request UX is wired. Needs an Activity/`ActivityResult` path since a Service can't request runtime permissions.
- **Recommended fix:** Replace auto-grant with `ContextCompat.checkSelfPermission`; for missing permissions, surface a request via an Activity (the app already has a 14-step permission chain in `MainActivity` to reuse). Return `PermissionRequired` honestly instead of forcing `true`.
- **Effort:** **M.**
- **Dependency note:** land before C-7 so the tool bridge executes under real gating.

**Critical tier exit criteria:** with the toggle (C-1) on, speaking to the device wakes it (C-4 model), audio flows (C-2), a real Gemini turn completes (C-3), the session advances through `Listening→Processing→Responding` (C-5), a real device tool fires (C-7) under a real permission check (C-6). Only then flip the toggle default and consider retiring legacy (H-1).

---

# HIGH

### H-1 — Two foreground microphone services coexist
- **Root cause:** `WakeWordService` and `VoiceService` are both `FOREGROUND_SERVICE_TYPE_MICROPHONE` (manifest :142–143). Both could hold `AudioRecord`.
- **Affected files:** `AndroidManifest.xml`, `MainActivity.kt`, `service/WakeWordService.kt`, `voice/VoiceService.kt`.
- **Risk of change:** **High.** Retiring the legacy service removes the current working product; only safe after the Critical tier proves the new stack at parity.
- **Recommended fix:** Once Critical exit criteria pass, make `VoiceService` the sole mic owner: default the C-1 toggle to new, stop starting `WakeWordService`, and either delete it or keep it behind the dev toggle for one release as rollback insurance.
- **Effort:** **M.**

### H-2 — Runtime memory (Phase 8) is in-memory only
- **Root cause:** `MemoryStore` backs records with `ConcurrentHashMap`; no durable store. (Note: `SpeakerStore` *does* persist via SharedPreferences — only Phase 8 memory is volatile.)
- **Affected files:** `ai/memory/MemoryStore.kt`, `ai/memory/MemoryEngine.kt`, `ai/memory/MemoryRetentionPolicy.kt`.
- **Risk of change:** **Low-Medium.** Behind the `MemoryStore` seam; swapping the impl is localized. Schema/migration is the main concern.
- **Recommended fix:** Implement a Room-backed `MemoryStore` (records + category + timestamps). Trigger retention purge on a schedule/session-end rather than only in-memory.
- **Effort:** **L.**

### H-3 — Memory retrieval is substring match, not semantic
- **Root cause:** `MemoryRanking` scores by `text.contains(queryTerm)`. No embedding similarity — despite `embedding_model.onnx` shipping.
- **Affected files:** `ai/memory/MemoryRanking.kt`, `ai/memory/MemoryRetrievalStrategy.kt`, `ai/memory/MemoryStore.kt` (store vectors).
- **Risk of change:** **Medium.** Adds an inference dependency to the memory path; ranking quality changes behavior. Needs the embedding model's I/O verified.
- **Recommended fix:** Use the shipped `embedding_model.onnx` to embed memories at write and queries at read; rank by cosine similarity (brute force is fine at personal scale, per PRD). Keep substring as a fallback.
- **Effort:** **L.**
- **Dependency note:** do after H-2 (need somewhere to store vectors).

### H-6 — ONNX thread-safety not enforced
- **Root cause:** `OrtSession.run` is not thread-safe; nothing enforces single-threaded access. `SpeakerVerificationManager` can overlap enrollment/verification coroutines on one session.
- **Affected files:** `voice/speaker/SpeakerVerificationManager.kt`, the three detector classes, `voice/VoiceActivityDetector.kt`/`wakeword`/`speaker` `close()` paths (also fix the shared-`OrtEnvironment` close cascade noted in report §11).
- **Risk of change:** **Low.** Adding a `Mutex`/confinement is localized and defensive.
- **Recommended fix:** Guard each `OrtSession.run` with a per-detector `Mutex` (or confine to a single dispatcher). Separately, stop calling `environment?.close()` per-detector — `OrtEnvironment.getEnvironment()` is a process singleton; close once at app teardown or not at all.
- **Effort:** **M.**
- **Dependency note:** meaningful only once real models load (C-4).

### H-4 — Intent classification is keyword `contains`, not NLU
- **Root cause:** `IntentClassifier.classify` is an ordered `if (contains(...))` ladder with literal confidences.
- **Affected files:** `ai/brain/IntentClassifier.kt`, `ai/brain/Planner.kt`, `ai/brain/ReasoningPipeline.kt`.
- **Risk of change:** **Low.** Behind the classifier seam.
- **Recommended fix:** For a Gemini-Live-driven product, intent classification is arguably redundant (the LLM plans). Recommend either (a) demote to a cheap pre-filter and let the LLM own planning, or (b) replace with a real classifier later. Do **not** invest in expanding the keyword ladder. Mark confidences as heuristic.
- **Effort:** **S** (demote/annotate) or **L** (real classifier — defer).

### H-5 — Plugin "crash containment" is a load-time try/catch only
- **Root cause:** `PluginLoader.loadPlugin` wraps init/start once; post-load `supportedTools()`/`stop()` calls have no isolation. No process/thread boundary.
- **Affected files:** `plugin/sdk/PluginLoader.kt`, `plugin/sdk/PluginManager.kt` (:70–74, :105–107).
- **Risk of change:** **Low-Medium.** Wrapping each plugin call in guards is safe; true process isolation is a larger design change.
- **Recommended fix:** Near-term: wrap every plugin invocation (`supportedTools`, `stop`, tool execution) in try/catch with per-plugin health state and auto-disable on fault. Long-term (defer): consider `:plugin` isolated process if third-party plugins become real.
- **Effort:** **M** (guards). **XL** (process isolation — defer until dynamic loading exists).

---

# MEDIUM

### M-1 — VAD transient states conflated (never observed)
- **Root cause:** `SpeechStarted`/`SpeechEnded` are set then overwritten within one `processAudioFrame` call; `StateFlow` conflation drops them.
- **Affected files:** `voice/VadManager.kt` (:113–150).
- **Risk of change:** **Medium.** Restructuring the state machine can affect the wake-word gate that keys off `Speaking`.
- **Recommended fix:** Emit transient states as one-shot `SharedFlow` events, or hold each state for its real duration rather than collapsing in a single frame. Largely resolved for consumers by C-5 wiring, but the emission bug remains — fix at source.
- **Effort:** **M.**

### M-2 — Speaker verification runs on an all-zeros default buffer
- **Root cause:** `verifyCurrentSpeaker(audioSegment = FloatArray(16000))` is called with no argument from `handleWakeStateChange`.
- **Affected files:** `voice/speaker/SpeakerVerificationManager.kt` (:69, :73).
- **Risk of change:** **Low.** Localized once the audio bus (C-2) exists.
- **Recommended fix:** Feed the buffered utterance audio (from the C-2 bus) into `verifyCurrentSpeaker`; remove the silent-buffer default to make the mistake uncompilable.
- **Effort:** **S.**
- **Dependency note:** requires C-2.

### M-3 — `stopService` may throw on Android 8+
- **Root cause:** `VoiceService.stopService` calls `context.startService(...)` with `ACTION_STOP_VAD`; background `startService` can throw `IllegalStateException`.
- **Affected files:** `voice/VoiceService.kt` (:80–85).
- **Risk of change:** **Low.**
- **Recommended fix:** Stop via a bound-service call or `stopService(Intent)`/internal `stopSelf()` path rather than `startService` with an action.
- **Effort:** **S.**

### M-4 — Metrics mutated via non-atomic read-modify-write
- **Root cause:** Pervasive `_metrics.value = _metrics.value.copy(x = _metrics.value.x + 1)`; concurrent updates lose increments.
- **Affected files:** all `*Dispatcher`/`*Engine`/`*Manager` with metrics (e.g. `ToolDispatcher.kt:94`, `BrainEngine.kt:152`, others).
- **Risk of change:** **Low.** Mechanical.
- **Recommended fix:** Replace with `MutableStateFlow.update { it.copy(...) }`. A find-and-convert pass.
- **Effort:** **M** (many sites, but mechanical).

### M-5 — `usesCleartextTraffic="true"` app-wide
- **Root cause:** Global flag in manifest; legacy license server is `http://`.
- **Affected files:** `AndroidManifest.xml` (:134), `security/…` (license URL).
- **Risk of change:** **Medium.** Disabling cleartext breaks any remaining `http://` call (license server) until moved to TLS or a network-security-config allowlist.
- **Recommended fix:** Move the license endpoint to HTTPS or scope cleartext to that host via `network_security_config.xml`; then set `usesCleartextTraffic="false"`.
- **Effort:** **S** (config) — assuming server supports TLS; otherwise blocked on infra.

### M-6 — Mock/DSL/Sample classes in release graph
- **Root cause:** `MockTool`, `MockConversationProvider`, `Dsl*`/`Energy*` fallbacks, `Sample*Plugin` compiled into release.
- **Affected files:** `ai/tool/MockTool.kt`, `ai/conversation/GeminiLiveProvider.kt` (mock inner class), detector fallbacks, `plugin/sdk/Sample*`.
- **Risk of change:** **Low-Medium.** Moving to a `debug` source set can break references if release code depends on fallbacks (it currently does — C-4).
- **Recommended fix:** After C-4 makes fallbacks non-load-bearing, gate them behind `src/debug`. Until then, keep but clearly mark; don't ship silent fallbacks as production behavior.
- **Effort:** **M.**
- **Dependency note:** after C-4.

---

# LOW

### L-1 — Per-frame `Log.d` in VAD hot path
- **Root cause:** `updateState` logs every transition; high-frequency under audio frames.
- **Affected files:** `voice/VadManager.kt` (:167).
- **Risk / effort:** Low / **S.**
- **Fix:** Gate verbose transition logs behind `BuildConfig.DEBUG` or a log level.

### L-2 — Emoji-heavy production logs
- **Affected files:** all new-stack managers.
- **Risk / effort:** Low / **S.**
- **Fix:** Optional; standardize a tag/format for logcat filtering. Cosmetic.

### L-3 — Misleading capability surface (wake config vs. no-op fallback)
- **Affected files:** `voice/wakeword/*`.
- **Risk / effort:** Low / **S.**
- **Fix:** Resolved implicitly by C-4; add a health/status field exposing "running on fallback."

### L-4 — Sparse KDoc on `VoiceService` passthrough getters
- **Affected files:** `voice/VoiceService.kt` (:104–133).
- **Risk / effort:** Low / **S.**
- **Fix:** Optional doc pass; naming is already clear.

### L-5 — `verifyCurrentSpeaker` public with defaulted buffer
- **Affected files:** `voice/speaker/SpeakerVerificationManager.kt`.
- **Risk / effort:** Low / **S.**
- **Fix:** Folded into M-2 (remove the default; tighten visibility).

---

## Effort roll-up

| Tier | Issues | Rough effort |
|---|---|---|
| Critical | C-1…C-7 | ~**3–4 weeks** (C-4 wake-model training is the swing factor — could add weeks if a custom model is required) |
| High | H-1…H-6 | ~**2 weeks** |
| Medium | M-1…M-6 | ~**1 week** |
| Low | L-1…L-5 | ~**1–2 days** |

**Cross-cutting (recommended alongside Critical):** stand up `app/src/test` and add state-machine + pipeline unit tests as each Critical fix lands (report §15 — currently zero tests). Estimated **M–L**, and it de-risks every subsequent tier.

---

## What I need from you to proceed

1. **Confirm the decision gate:** is the 10-phase `VoiceService` the intended production runtime (this plan), or a prototype to shelve (collapses Critical to one task)?
2. **Model availability for C-4:** do you have the three ONNX models (Silero VAD, ECAPA-TDNN, "hey ultron" openWakeWord), or does the wake model need training?
3. **Approval to begin** — and if so, whether to start at C-1 (behind a toggle, zero user-facing risk) or to prototype C-2/C-3 on a branch first.

**No code will be changed until you approve.**
