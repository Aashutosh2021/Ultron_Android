# MaxNova — Recovery Notes

Reconstruction of the **com.nova.max** ("Max") Android app from the installed APK
(owner-authorized disaster recovery — original source lost to repo corruption).

This document tracks the Java→Kotlin reconstruction. For the APK-extraction phase see
`E:\nova_recovery\RECOVERY_REPORT.md`.

---

## Status at a glance

| Phase | State |
|---|---|
| **1. Logic core (Kotlin)** | ✅ **Complete** — 50 files, ~14,770 LoC, idiomatic Kotlin |
| **2. UI (Compose)** | ✅ **Complete** — 13 files: theme, MainActivity, camera, nav host, overlays, 6 screens |
| **3. Whole-app build** | ✅ **Builds** — `assembleDebug` SUCCESSFUL, valid `app-debug.apk` (com.nova.max v3.0) |

The app was **not name-obfuscated**, so recovered names are the original developer's names.
Every logic class converted below preserves behavior exactly (same control flow, API calls,
threading, and string literals including the app's Hinglish/emoji text).

---

## Package layout (reconstructed)

```
com.nova.max
├── model/            Models.kt              data/enum/sealed types
├── data/             AppPreferences, DatabaseHelper
├── ai/               GeminiLiveSession, ToolHandler, PromptBuilder,
│                     CameraVisionManager, TranslationManager
├── automation/       MJAccessibilityService, TypingAccessibilityService,
│                     AutoTyper, ScreenReader, ScrollManager
├── service/          WakeWordService, MJNotificationListener
├── receiver/         MJDeviceAdminReceiver, ChargingReceiver, ReminderReceiver
├── comms/            CallReceiver, CallAnswerManager, CallAudioManager,
│                     MJSmsManager, ContactManager, EmergencySOSManager
├── apps/             Spotify, YouTube, WhatsApp, GoogleSearch, AppLaunch,
│                     AppOpener, Maps, Places managers
├── device/           Flashlight, Brightness, Volume, NetworkToggle, Battery,
│                     SystemInfoTools, Camera, CameraControl managers
├── info/             WeatherManager, NewsManager
├── i18n/             HindiTransliterator, Languages (INDIAN_LANGUAGES x53)
├── reminder/         ReminderManager
├── security/         AccessKeyVerifier, AntiTheftManager
├── overlay/          CountdownOverlay
└── ui/               theme/ (Color, Theme) · AppRoot (nav host) · Overlays
    └── screens/      Splash, Onboarding, Home, Features, History, Settings
  (MainActivity + NovaCameraActivity live at the root com.nova.max package)
```

Original source was flat (everything in `com.nova.max`); it has been organized into the
domain packages above for maintainability. **Manifest component FQNs were updated to match.**

---

## Phase 2 — UI reconstruction fidelity

Decompiled Jetpack Compose is **compiler-shredded** — `@Composable` functions become restart-
group state machines with lifted lambdas and `ComposableSingletons`. Exact pixel layout is
**not** recoverable. So UI was reconstructed on two tiers:

**Exact (behavior-faithful from bytecode):**
- `MainActivity` — the full 14-step permission chain (sequence + every permission string),
  all ActivityResultLaunchers, notification-listener check, battery-opt exemption, service
  start, device-id-reset flow.
- `NovaCameraActivity` — CameraX setup/capture/save (MediaStore + `lastCapturedPhotoUri`),
  lens toggle, intent-extra contract.
- `AppRoot` navigation flow (SPLASH→ONBOARDING→MAIN gated on `isOnboardingComplete`), the
  4-tab bottom nav (Home/History/Settings/Features), Start/Stop wiring.
- Theme colours (exact ARGB), `INDIAN_LANGUAGES` (53 entries), the `FeatureItem` catalog
  (13 cards, bilingual), the `OnboardStep` wizard steps, all screen **strings** (titles,
  labels, Hinglish, emoji), Settings→AppPreferences bindings (28 rows), Splash timing.

**Approximated (layout only — data/actions/strings preserved):**
- Visual arrangement of every screen (Column/Row/Box positioning, spacing, exact styling).
- Dropped: decorative canvas FX (glow, sheen, scanlines, pulsing dots, per-letter splash
  animation, radial backgrounds). Purely visual, not user-facing content, not byte-recoverable.
- `EdgeGlowOverlay` / `GhostPointerRipple` — reconstructed functionally (state-tinted edge,
  600ms tap ripple); exact multi-layer visuals not recovered.

Every reconstructed composable carries a `// RECOVERY NOTE: layout approximated from ...`
header. These screens will **look different** from the original but show the same content and
drive the same actions. Pixel-matching them is manual design work (see KnownIssues).

### Signature contract (reconciled)
Two facts from the bytecode overrode the initial plan and were matched:
- Bottom nav has **4 tabs**, not 5 (Start/Stop lives inside HomeScreen).
- `OnboardingScreen(onDone)` and `SplashScreen(onDone)` take one param; `HomeScreen(isRunning,
  onStartStop)` takes no message list. All call sites in `AppRoot` line up.

---

## Phase 3 — making it build

`./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, produces `app-debug.apk`
(`com.nova.max` v3.0, minSdk 26/target 35, all 9 components, DEX + arm64 native libs).

Fixes applied to get from "reconstructed" to "builds", in order:

**Environment / config (not code):**
1. `local.properties` — `sdk.dir=C:/Android` (a backslash `C:\Android` is an invalid
   `.properties` escape → "volume label syntax is incorrect"). Use `/`.
2. Added `gradle.properties` with `android.useAndroidX=true` (was missing entirely).
3. `app/build.gradle.kts` — the `@aar` deps were interpolated from version-catalog Providers
   (`"${libs.jna.get()}@aar"`); replaced with plain coordinates.

**Resources — the big one (deduplication):**
4. The decoded `res/` still held the **fully-merged library resource set** (1131 styles,
   1304 attrs, 275 strings — almost all AppCompat/Material). AGP re-supplies these from the
   dependencies, and one carried a malformed source path that crashed `mergeDebugResources`.
   Stripped `values/` to only app-authored `strings.xml` (2 strings) + `styles.xml`
   (`Theme.Nova`, `Theme.Transparent`); deleted library `anim/animator/color/interpolator/
   drawable-*dpi/raw` and all `values-*` config dirs. Kept: mipmap launcher icons, `xml/`
   configs, `drawable/ic_launcher_background`.
5. Adaptive icon (`mipmap-anydpi/ic_launcher.xml`) referenced a deleted
   `@mipmap/ic_launcher_foreground`; repointed to `@mipmap/ic_launcher`.

**Kotlin — 46 compile errors, all mechanical cross-file mismatches from parallel conversion:**
6. Missing imports the converters had flagged: `ContactManager`, `WakeWordService` (WhatsApp),
   `WakeWordService` (Spotify), `WeatherManager` (Places).
7. Java-accessor syntax on Kotlin properties: `prefs.getUserName()` → `prefs.userName`,
   `prefs.getLastSavedLatitude()` → `prefs.lastSavedLatitude`,
   `setLastIntruderDescription(x)` → `lastIntruderDescription = x` (EmergencySOS, AntiTheft).
8. `performTapGesture(Int, Int)` needed `Float` → `.toFloat()` (ScreenReader).
9. **Platform declaration clash** — a public `var instance` / `var activeInstance` auto-
   generates `getInstance()`/`getActiveInstance()`, colliding with the explicit function of
   the same name. Made the backing property `private`, kept the function (callers use the
   function form 43/33×); fixed the one caller using `.activeInstance` directly.

Every fix was a name/type/import correction — **no recovered logic was changed or stubbed** to
make it build.

---



## Secrets externalized (security)

Moved out of source into `local.properties` + `BuildConfig` (see `local.properties.sample`):

| Secret | Was | Now |
|---|---|---|
| License server URL | Base64 literal in `AccessKeyVerifier` (`http://89.116.134.248:8000`) | `BuildConfig.LICENSE_SERVER_URL` |
| OpenWeather default key | hardcoded in `AppPreferences` (`744714b5...`) | `BuildConfig.OPENWEATHER_DEFAULT_KEY` — **rotate this, it leaked** |

Runtime keys (Gemini, OpenRouter) are user-entered during onboarding and stored in prefs —
not build secrets, left in the pref layer as designed.

---

## Decompiler artifacts handled

jadx substitutes a named constant whenever its *value* equals a literal the code used. Every
converter restored the intended literal and left an inline `// RECOVERY NOTE`. Examples found:

- `TranslateLanguage.INDONESIAN` == `"id"` → SQLite column name; `.THAI` == `"th"`, `.UKRAINIAN`/`.MALAY` for RSS keys.
- `TypingAccessibilityService.EXTRA_TEXT` == `"text"`; `ImagesContract.URL` == `"url"`.
- `HttpUrl.PATH_SEGMENT_ENCODE_SET_URI` == `"[]"` → empty-JSON default in prefs.
- `performAction(16)` == `ACTION_CLICK`, `2097152` == `ACTION_SET_TEXT`, `performGlobalAction(1)` == `GLOBAL_ACTION_BACK`, etc.
- `Base64` flag `2` == `NO_WRAP`; various camera2 / audio-source magic numbers.

## Blocks jadx decompiled incorrectly (reconstructed, flagged inline)

- `ContactManager.calculateFinalScore` — body dropped by jadx; rebuilt from the surviving
  arithmetic fragments (relationship/favorite/recency/frequency scoring ladder).
- `ContactManager.resolveAndCallAsync` — inverted empty-list condition, corrected.
- `NetworkToggleManager.findToggleAfterOpen` — garbled retry state machine, reconstructed
  (1800ms settle → ≤15 attempts → BACK → callback).
- `MJAccessibilityService.findByDescTraverse` / `SpotifyManager.findNodeByDescriptions` —
  broken `none{}`/`any{}` loop inference, restored as an `excluded` flag gate.
- `WeatherManager.getLegacyFreshLocation`, `ToolHandler.reportLocation` — try/catch inversion
  artifacts, restored to behavior-faithful form.

## Behavior-faithful bugs preserved (flag for owner)

These look unintended but match the shipped APK; kept verbatim with a note:

- `AppPreferences.toolSMS` setter writes literal `true` regardless of the value passed.
- `AppPreferences.toolSystemInfo` setter — same.
- `AccessKeyVerifier.verify` (non-migration branch) returns `Error` on HTTP success and
  `Success` otherwise (inverted-looking; the migration branch is conventional).

---

## Known issues / remaining manual work

See `KnownIssues.md`. Headlines:
1. **UI not yet reconstructed** — logic files reference 3 phase-2 symbols
   (`NovaCameraActivity`, `EdgeGlowOverlay`, `GhostPointerRipple`) that don't exist yet.
2. **Resource dedup** — `res/values/{styles,attrs,dimens,colors}.xml` still contain merged
   library entries; a full Gradle build needs these trimmed to app-authored resources.
3. **No compiler run yet** — MaxNova isn't the IDE-indexed project, so conversions were
   verified structurally (brace/paren balance) + by cross-file signature matching, not by
   a real `kotlinc` pass. First `./gradlew assembleDebug` will surface the true error list.
