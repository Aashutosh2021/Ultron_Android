package com.flexfly.ultron.model

import androidx.compose.ui.graphics.Color

/**
 * A single chat/transcript message persisted by [com.flexfly.ultron.data.DatabaseHelper].
 *
 * Recovered from `Message` (originally declared in DatabaseHelper.kt).
 * Defaults confirmed from the decompiled synthetic constructor:
 *  - [id] defaults to 0 (auto-assigned by SQLite)
 *  - [timestamp] defaults to "now"
 */
data class Message(
    val id: Long = 0L,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * A feature card shown on the "Features" screen, with bilingual (English/Hindi)
 * copy and example utterances.
 *
 * Recovered from `FeatureItem`. [accentColor] was a Compose [Color] inline value
 * class in the original (decompiled as a mangled `copy-qcMF2vU` signature).
 */
data class FeatureItem(
    val emoji: String,
    val titleEn: String,
    val titleHi: String,
    val badge: String = "",
    val descEn: String,
    val descHi: String,
    val examples: List<String>,
    val accentColor: Color,
)

/**
 * Display metadata for a supported assistant language.
 * Recovered from `LanguageInfo` (declared in Languages.kt).
 */
data class LanguageInfo(
    val name: String,
    val nativeName: String,
    val region: String,
)

/**
 * A single onboarding wizard step.
 * Recovered from `OnboardStep` (declared in Onboardingscreen.kt).
 */
data class OnboardStep(
    val index: Int,
    val eyebrow: String,
    val title: String,
    val subtitle: String,
    val hint: String,
    val isPassword: Boolean = false,
    val isDropdown: Boolean = false,
)

/**
 * Top-level navigation destinations.
 * Recovered from the `AppScreen` enum.
 */
enum class AppScreen {
    SPLASH,
    ONBOARDING,
    MAIN,
}

/**
 * UI state for the access-key verification flow ([com.flexfly.ultron.security.AccessKeyVerifier]).
 * Recovered from the `KeyVerifyState` sealed hierarchy.
 */
sealed class KeyVerifyState {
    data object Idle : KeyVerifyState()
    data object Verifying : KeyVerifyState()
    data class Success(val newKey: String? = null) : KeyVerifyState()
    data class Error(val message: String) : KeyVerifyState()
}
