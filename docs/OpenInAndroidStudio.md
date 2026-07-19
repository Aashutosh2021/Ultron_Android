# Opening MaxNova in Android Studio

The project builds from the command line (`./gradlew assembleDebug`) and is set up for
Android Studio. If the toolbar shows **"Add Configuration"** instead of an **app** run
config, do this:

## First open
1. **File → Open** → select the `MaxNova` folder (the one with `settings.gradle.kts`).
   Do *not* open a parent folder.
2. Wait for **Gradle sync** to finish (bottom status bar). First sync downloads Gradle 8.9
   and dependencies — give it a few minutes.
3. After sync, the toolbar dropdown shows **app**. Pick a device/emulator and hit **Run**.

A shared run configuration is already committed at `.run/app.run.xml`, so **app** should
appear automatically once sync completes.

## If "Add Configuration" persists after sync
- **File → Sync Project with Gradle Files** (elephant icon).
- Ensure the JDK is set: **Settings → Build Tools → Gradle → Gradle JDK = 17** (or the
  bundled JBR 17+). AGP 8.7 requires JDK 17.
- **Build → Make Project** once; the config detector runs after a successful build.
- Last resort: **File → Invalidate Caches / Restart**.

## "Failed to migrate project to Daemon JVM criteria"
Newer Android Studio tries to pin the Gradle daemon's JVM via a toolchain and can fail if no
matching JDK toolchain is found. This project does **not** use that feature — it runs on the
Gradle JDK you set above (JDK 17). If you see this message:
- It's safe to dismiss; the project builds without it (verified via `./gradlew assembleDebug`).
- Make sure **Settings → Build Tools → Gradle → Gradle JDK** is set to a real JDK 17.
- If Studio re-adds `id("org.gradle.toolchains.foojay-resolver-convention")` to
  `settings.gradle.kts`, that's the trigger — it was removed on purpose and isn't needed.
  You can leave it or remove it; either way, keep Gradle JDK = 17.
- If a `gradle/gradle-daemon-jvm.properties` file appears and sync keeps failing, delete it.

## "Unknown command-line option '--jvm-vendor'" (updateDaemonJvm fails)
Recent Android Studio tries to pin the daemon JVM to its bundled JetBrains Runtime with
`updateDaemonJvm --jvm-vendor "jetbrains"`. That option needs **Gradle 8.10+**. The wrapper is
pinned to **Gradle 8.10.2** for exactly this reason, so the command works. If you (or Studio)
downgrade the wrapper below 8.10, this error returns — keep `gradle-wrapper.properties` at
8.10.2 or newer.

## "Please select Android SDK"
`local.properties` already sets `sdk.dir`. If Studio still prompts:
- **File → Project Structure → SDK Location** → set **Android SDK location** to your SDK
  (e.g. `C:\Users\<you>\AppData\Local\Android\Sdk` or `C:\Android`), click Apply.
- Or **Settings → Languages & Frameworks → Android SDK** → confirm the path and that
  **API 35** is installed.
- The build itself finds the SDK fine (CLI `./gradlew assembleDebug` succeeds); this prompt is
  just Studio wanting the path registered in its own settings once.

## Requirements
- Android Studio Ladybug (2024.2) or newer — matches AGP 8.7.0 / Gradle 8.10.2.
- Android SDK with **API 35** installed (Settings → SDK Manager).
- `local.properties` must point at your SDK (`sdk.dir=...`). Already present; change it if your
  SDK lives elsewhere.
- Gradle JDK = **17** (Settings → Build Tools → Gradle → Gradle JDK).

## Run configuration details
`.run/app.run.xml` launches the default launcher activity (`MainActivity`) via `installDebug`.
Module: `MaxNova.app.main`. If your Studio names the module differently and the config errors,
just delete `.run/app.run.xml` and let Studio create its own after sync.
