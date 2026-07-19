# MaxNova — Decompiled-Java → Idiomatic Kotlin Conversion Guide

You are restoring the ORIGINAL Kotlin source of an app the user owns, from jadx-decompiled
Java. The app was **not name-obfuscated**, so class/method/field/parameter names in the Java
are the ORIGINAL developer names — preserve them exactly unless a rename clearly improves
readability (and if you rename, note it).

## Golden rules
1. **Preserve behavior exactly.** This is recovery, not refactoring. Same control flow, same
   API calls, same threading, same string literals (including emoji and Hinglish text).
2. **Idiomatic Kotlin, not Java-in-Kotlin.** Undo the decompiler's transformations:
   - `Intrinsics.checkNotNullParameter(x, "x")` → delete (it's the Kotlin null-check the
     compiler re-inserts). Non-null params stay non-null.
   - `Intrinsics.areEqual(a, b)` → `a == b`
   - `Intrinsics.checkNotNull(x)` → `x!!` only if truly needed, prefer smart-casts.
   - Manual `copy$default` / `component1..N` / `equals`/`hashCode`/`toString` on a class
     marked `/* data */` → collapse to a Kotlin `data class`.
   - `EnumEntries` / `$VALUES` boilerplate marked "Failed to restore enum" → real `enum class`.
   - Nested `INSTANCE` singletons → `object`, or `data object` for sealed leaves.
   - Manual getter `/* renamed from: isX, from getter */ getIsX()` → property `val isX`.
3. **Nullability:** a field/param the decompiler leaves as a boxed/`@Nullable` type or that is
   compared to null → Kotlin nullable (`String?`). Params guarded by `checkNotNullParameter`
   are non-null.
4. **Coroutines:** `Continuation` params + `$1`/`$2` state-machine inner classes are decompiled
   `suspend fun`s. Restore `suspend fun foo(...): T` and drop the state machine. Bodies using
   `BuildersKt.launch`/`withContext` → `launch { }` / `withContext(Dispatchers.X) { }`.
   `CoroutineScope` fields → `CoroutineScope(...)`.
5. **Lambdas / anonymous classes:** `X$onCreate$1.java` etc. are lambdas or `object :` literals.
   Fold them back inline. Function-type fields → `(T) -> R` / `() -> Unit`.
6. **Companion:** `public static final Companion` + `Xxx.Companion` access → `companion object`.
   `$stable` fields and `DefaultConstructorMarker` → delete (compiler synthetics).
7. **Compose `Color`:** mangled names like `copy-qcMF2vU`, `Color.m5116hashCodeimpl`, a `long`
   field holding a color → `androidx.compose.ui.graphics.Color`.
8. **String building:** `"a" + x + "b"` → template `"a${x}b"` when it reads better.
9. **Imports:** fix jadx's mangled package names — `kotlin.p004io` → `kotlin.io`,
   `com.google.mlkit.p003nl.translate` → `com.google.mlkit.nl.translate`. Remove the bogus
   `androidx.constraintlayout.widget.ConstraintLayout` import jadx adds to every file.
10. **Android idioms:** `getSystemService` casts → `getSystemService<T>()` KTX where obvious;
    otherwise keep explicit. Keep `Log.d(TAG, ...)` calls verbatim (they're valuable for the
    owner). Keep `TAG` as a `private const val` in a `companion object`.

## Package placement
Put the class under `com.nova.max.<subpackage>` as assigned. Update the `package` line.
Do NOT add `import com.nova.max.model.*` blindly — only import what the file uses (Message,
FeatureItem, LanguageInfo, OnboardStep, AppScreen, KeyVerifyState live in `com.nova.max.model`).

## When Java is ambiguous
If a decompiled block is marked `/* JADX WARN */` or looks structurally broken, reconstruct the
most behavior-faithful version and add a `// RECOVERY NOTE: ...` comment explaining the
assumption. Never silently drop logic.

## Output
Write the `.kt` file(s) to the given path. Return a concise summary:
- file(s) written
- any symbols renamed (old → new + why)
- any RECOVERY NOTE assumptions
- anything that will need a manual look (cross-file refs, missing types)
