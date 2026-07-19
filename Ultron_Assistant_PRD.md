# Ultron Personal AI Assistant — Product Requirements Document

**Owner:** Aashutosh
**Status:** Draft v0.1 — consolidates decisions made through Phase 2 implementation
**Distribution:** Personal use only, sideloaded, not published to Play Store
**Target device:** Infinix Note 50s (MediaTek Dimensity 7300 Ultimate, 8GB RAM, Android 15 / XOS 15)

---

## 1. Vision

A voice-first Android assistant with a rough, direct, "Ultron-from-Iron-Man" personality, controllable almost entirely by voice — device automation, calls, SMS, email, app control — with persistent memory across sessions and the ability to recognize the primary user ("Boss") versus other people speaking nearby.

## 2. Goals

- Hands-free control of phone functions (apps, calls, SMS, email, automation) via natural voice conversation.
- Distinct, consistent personality — blunt, bold, deep-voiced — not a generic assistant tone.
- Memory that persists and stays useful over months, not just within one session.
- Recognize who is speaking: Boss vs. enrolled contacts vs. unknown voices.
- Runs reliably as an always-available background service on a single personal device.

## 3. Non-Goals / Out of Scope (this version)

- **Play Store distribution** — sideload only; this removes Play policy review but not Android's runtime permission model, which still applies in full.
- **On-device LLM as primary brain** — deferred to a later version. Current version routes all reasoning through Gemini Live API.
- **Dedicated PC "brain server"** — considered and dropped. All processing (voice, reasoning, memory) runs on-phone.
- **Real-time multi-speaker diarization** (telling Boss and a friend apart while both are talking simultaneously) — out of scope. Current design assumes one primary speaker per session (whoever triggers the wake word).
- **Acoustic gender classification** — deliberately excluded. See §13.

## 4. Users & Personas

| Persona | Description | Trust level |
|---|---|---|
| Boss | Aashutosh, primary user | Full — device control, automation, all data access |
| Enrolled contact | Friend/family identified by voice, name + relation stored | Conversational only by default — **open question:** should enrolled contacts get any device-control authority? Not yet decided. |
| Unknown speaker | Unrecognized voice | Prompted for consent to enroll; if declined, treated as guest, not stored |

## 5. Functional Requirements

### FR-1 — Wake Word Detection
- Custom wake phrase ("Hey Ultron"), always-on, on-device, no network dependency.
- **[Status: implemented, unverified]** Uses `openwakeword-android-kt`, a community-maintained Kotlin port, not an officially packaged SDK. API surface assumed from README, not confirmed against full source.
- **[Status: not started]** Custom "Ultron" keyword requires separate training via openWakeWord's training notebook. Current build ships with a stock placeholder model (`hey_jarvis`).

### FR-2 — Voice Conversation (Live Session)
- Full-duplex voice conversation via Gemini Live API (`BidiGenerateContent`), native audio in/out — no separate STT/TTS engine.
- Idle-timeout closes the session after ~25s of local silence (RMS-gated, not merely absence of transmitted chunks) — cost and battery control.
- Session resumption: proactive reconnect ~9 minutes into a session using the server-issued resumption handle, since audio-only sessions cap around 15 minutes.
- **[Open]** Voice selection (`speechConfig.voiceConfig`) not yet set — needs a deep-voice option chosen from Google's current voice list, not assumed.

### FR-3 — Device Control & Automation
- App launching and in-app automation via Android Intents and Accessibility Service.
- Device actions exposed to the model as Live API tool declarations (`open_app`, `send_sms`, `place_call`, `run_automation`) — model decides when to call them mid-conversation; no custom command-parsing layer.
- Accessibility Service must be enabled manually by the user in system settings — this is an OS-level requirement, not a Play Store policy gate, and sideloading does not remove it.

### FR-4 — Persistent Memory
- Local storage (SQLite/ObjectBox) holds summarized conversation history, not raw transcripts.
- Memory exposed to the model as a `recall_memory(query)` tool — pulled in only when the model determines it's needed, not injected wholesale into every session's context.
- Embeddings generated via Gemini Embedding API; similarity search done locally (brute-force cosine, expected sufficient at personal scale).

### FR-5 — Speaker Identification & Enrollment
- **[Status: planned, Phase 3]** On detecting a voice not matching a stored profile, Ultron asks for name and relation to Boss before storing anything, and states plainly that the voice will be remembered.
- Speaker embedding computed via a converted ECAPA-TDNN model (TFLite) — **no ready Android library exists for this**; requires manual model conversion, unlike the wake-word and Live API pieces.
- One embedding computed per session (not per audio chunk).
- Enrolled contact record: `{name, relation, embedding, enrolled_date}`.
- Explicit "forget my voice" deletion path required — see §13.

### FR-6 — Calls & SMS
- Place/answer calls: `CALL_PHONE`, `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`.
- Send/receive SMS: `SEND_SMS`, `RECEIVE_SMS`.
- Call *screening* (auto-reject/redirect) requires `RoleManager.ROLE_CALL_SCREENING` — a framework-level gate, not a Play policy gate; sideloading does not bypass it, the role must still be requested and approved.

### FR-7 — Email
- Gmail API via OAuth device flow, Google Cloud project kept in "Testing" mode — avoids Google verification review at personal-use scale.

### FR-8 — Background Reliability (XOS)
- No API exists for any of the following; all three are manual, one-time setup steps required for the app to survive backgrounding on this device's XOS skin:
  1. Battery optimization exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, still requires a user tap).
  2. Infinix Security app → Autostart → enabled.
  3. Infinix Security app → Protected Apps → whitelisted.

## 6. Non-Functional Requirements

| Category | Requirement |
|---|---|
| Latency | Wake-to-response should feel conversational; no hard target set yet — needs on-device measurement once Phase 1/2 is tested. |
| Battery | Wake-word listening must not meaningfully drain battery over a full day; idle-timeout on Live sessions is the primary cost/battery control. |
| Cost | Hard billing cap required on the Google Cloud project regardless of assumed free-tier status — Live API's audio/preview lane pricing was not confirmed via official docs during planning. |
| Privacy | All memory and voice profile data stored locally on-device, not synced to any external server by default. |
| Reliability | Foreground service must survive Doze mode and XOS background kill; session manager must survive dropped connections via resumption. |

## 7. Technical Architecture

| Layer | Technology | Notes |
|---|---|---|
| Wake word | openWakeWord (community Android port) | Unofficial library — verify maintenance status |
| Voice brain (STT+LLM+TTS) | Gemini Live API, native-audio model | Model string needs re-verification before each release — preview names shift |
| Device actions | Live API tool-calling | Replaces custom NLU |
| Memory embeddings | Gemini Embedding API | |
| Memory storage | SQLite/ObjectBox, on-device | |
| Speaker ID | ECAPA-TDNN, converted to TFLite | Manual conversion required |
| Device control | Accessibility Service + Android Intents | |
| Calls/SMS | Native Android Telephony APIs | |
| Email | Gmail API + OAuth | |
| Networking | OkHttp WebSocket | For the Live API connection |

## 8. Data Model (local storage, draft)

```
speaker_profiles
  id, name, relation, embedding (blob), enrolled_date

memory_entries
  id, speaker_id, summary_text, embedding (blob), created_at

sessions_log
  id, speaker_id, started_at, ended_at, close_reason
```

## 9. Permissions Matrix

| Permission | Phase | Purpose |
|---|---|---|
| RECORD_AUDIO | 1 | Wake word + Live conversation |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_MICROPHONE | 1 | Always-on listener |
| POST_NOTIFICATIONS | 1 | Required for foreground service notification |
| INTERNET | 2 | Live API WebSocket |
| CALL_PHONE, READ_PHONE_STATE, ANSWER_PHONE_CALLS | 6 | Calling |
| SEND_SMS, RECEIVE_SMS | 6 | Messaging |
| Accessibility Service (user-granted, not a manifest permission) | 3 | App/device automation |
| ROLE_CALL_SCREENING (requested via RoleManager) | 6 (stretch) | Call auto-handling |

## 10. Risks & Open Questions

| Item | Confidence | Risk if wrong |
|---|---|---|
| `openwakeword-android-kt` API surface matches assumed usage | Guessing | Wake-word code may not compile as-is |
| Live API audio/preview lane is covered by free tier | Guessing | Unexpected billing — hard cap is mandatory regardless |
| `gemini-3.1-flash-live-preview` is still current at build time | Likely, time-sensitive | Model string will need re-verification before shipping |
| ECAPA-TDNN converts cleanly to TFLite at usable size for this chip | Guessing | Speaker ID may need a smaller/different model |
| Enrolled contacts' device-control authority | Undecided | Needs an explicit product decision, not a technical one |
| Silence RMS threshold (500.0) is correct for this device's mic | Guessing | Idle-timeout may fire too early or never |

## 11. Success Metrics (draft — refine after Phase 1/2 testing)

- Wake-word false-accept / false-reject rate over a week of normal use.
- Session idle-timeout fires within expected window, not mid-sentence.
- Speaker enrollment false-accept rate (wrongly recognizing an unenrolled voice as Boss) — should be near zero given device-control implications.
- App survives 24 hours backgrounded on XOS without being killed.

## 12. Roadmap & Status

| Phase | Scope | Status |
|---|---|---|
| 0 | Project scaffold, permissions, manifest | Drafted |
| 1 | Wake-word foreground service | Drafted, untested on-device |
| 2 | Live API session manager, audio I/O, idle-timeout, resumption | Drafted, untested on-device |
| 3 | Device control via tool-calling; speaker ID & enrollment | In progress |
| 4 | Persistent memory (`recall_memory` tool) | Not started |
| 5 | Calls/SMS | Not started |
| 6 | Email | Not started |
| — | XOS background survival hardening | Manual steps documented, not automated |

## 13. Ethical & Privacy Requirements

- Voice enrollment of anyone other than Boss requires **stating the intent out loud** before storing anything: "I'll remember your voice so I recognize you next time — okay?" Silent fingerprinting is not acceptable, even for personal use — it's another person's biometric data.
- A "forget my voice" deletion path must exist and actually delete the stored embedding, not just hide the record.
- Acoustic gender inference is explicitly excluded: pitch-based classifiers misclassify a meaningful fraction of real voices, and the enrollment flow already collects relation/name directly from the person — inferring instead of asking is both less accurate and needlessly presumptuous.
- Memory and voice data remain local to the device by default; any future sync to cloud storage would need to be a separate, explicit decision — not a side effect of some other feature.
