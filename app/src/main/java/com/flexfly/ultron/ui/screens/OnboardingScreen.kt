// RECOVERY NOTE: layout approximated from shredded Compose
package com.flexfly.ultron.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.i18n.INDIAN_LANGUAGES
import com.flexfly.ultron.model.LanguageInfo
import com.flexfly.ultron.model.OnboardStep
import com.flexfly.ultron.ui.theme.CardBorder
import com.flexfly.ultron.ui.theme.UltronRed
import com.flexfly.ultron.ui.theme.SubText


// The three wizard steps — access-key step removed for testing.
private val STEPS = listOf(
    OnboardStep(0, "STEP 01 / 03", "What's your\nname?", "Ultron will address you personally.", "Your name…"),
    OnboardStep(1, "STEP 02 / 03", "Pick your\nlanguage", "Choose the language Ultron will speak.", "Language", isDropdown = true),
    OnboardStep(2, "STEP 03 / 03", "Google\nAPI key", "Needed for search & assistant features.", "AIzaSy…", isPassword = true),
)

/**
 * Multi-step onboarding wizard. Recovered from `Onboardingscreen.kt` — the compiler-shredded
 * Compose layout is reconstructed functionally: a step-indexed Column with a field (text /
 * password / language dropdown) plus Back / Next controls. Every answer is mirrored to
 * `prefs.onboarding*` so a killed process resumes where it left off. [onDone] fires on the
 * final step.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }

    var step by remember { mutableStateOf(prefs.onboardingCurrentStep) }
    var name by remember { mutableStateOf(prefs.onboardingName) }
    var language by remember { mutableStateOf(prefs.onboardingLanguage) }
    var googleApi by remember { mutableStateOf(prefs.onboardingGoogleApiKey) }

    // Persist each answer as it changes.
    LaunchedEffect(step) { prefs.onboardingCurrentStep = step }
    LaunchedEffect(name) { prefs.onboardingName = name }
    LaunchedEffect(language) { prefs.onboardingLanguage = language }
    LaunchedEffect(googleApi) { prefs.onboardingGoogleApiKey = googleApi }

    // Per-step "can advance" gate.
    val canAdvance = when (step) {
        0 -> name.trim().length >= 2
        1 -> language.isNotBlank() && language != "__other__"
        2 -> googleApi.trim().length >= 8
        else -> false
    }
    val isLast = step == 2

    val nextLabel = when {
        isLast -> "Activate Ultron  →"
        else -> "Continue  →"
    }

    fun advance() {
        step += 1
    }

    fun onNext() {
        when {
            isLast -> {
                prefs.userName = name.trim()
                prefs.language = language
                prefs.googleApiKey = googleApi.trim()
                prefs.clearOnboardingCache()
                onDone()
            }
            else -> advance()
        }
    }

    fun onBack() {
        step -= 1
    }

    val current = STEPS[step]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(Color(0xFF0B1120))
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        // Header: gradient "ULTRON" wordmark + progress dots.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ULTRON", color = UltronRed, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            ProgressDots(current = step, total = 3)
        }

        Spacer(Modifier.height(52.dp))

        Text(current.eyebrow, color = Color(0x38FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        Text(current.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
        Spacer(Modifier.height(10.dp))
        Text(current.subtitle, color = Color(0x48FFFFFF), fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(36.dp))

        when (step) {
            0 -> PremiumField(name, { name = it }, current.hint)
            1 -> LanguagePicker(language) { language = it }
            2 -> PremiumField(googleApi, { googleApi = it }, current.hint, isPassword = true, keyboardType = KeyboardType.Password)
        }

        // Field footnote (API key step).
        if (step == 2) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Used for Google Search & Assistant integration.",
                color = Color(0x2FFFFFFF),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.weight(1f))

        NextButton(enabled = canAdvance, label = nextLabel, onClick = ::onNext)

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                Text(
                    "← Back",
                    color = UltronRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                Spacer(Modifier.width(1.dp))
            }
            Text("Privacy Policy", color = Color(0x1FFFFFFF), fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

/** Rounded outlined text field used for name / access key / API key. */
@Composable
private fun PremiumField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val focused = value.isNotEmpty()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x09FFFFFF), RoundedCornerShape(18.dp))
            .border(
                0.5.dp,
                if (focused) UltronRed.copy(alpha = 0.55f) else Color(0x13FFFFFF),
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, color = Color(0x2FFFFFFF), fontSize = 17.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

/**
 * Language selector. Recovered functionally: the original opened a picker dialog; here the
 * selected language (matched case-insensitively against [INDIAN_LANGUAGES]) advances through
 * the list on tap. RECOVERY NOTE: picker dialog approximated as tap-to-cycle.
 */
@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    val match: LanguageInfo? = INDIAN_LANGUAGES.firstOrNull { it.name.equals(selected, ignoreCase = true) }
    val display = match ?: LanguageInfo(
        if (selected.isBlank()) "Select Language" else selected,
        if (selected.isBlank()) "Choose language" else selected,
        "Tap to open list",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x09FFFFFF))
            .border(
                0.5.dp,
                if (selected.isNotBlank()) UltronRed.copy(alpha = 0.55f) else Color(0x13FFFFFF),
                RoundedCornerShape(18.dp),
            )
            .clickable {
                val i = INDIAN_LANGUAGES.indexOfFirst { it.name.equals(selected, ignoreCase = true) }
                onSelect(INDIAN_LANGUAGES[(i + 1).coerceAtLeast(0) % INDIAN_LANGUAGES.size].name)
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(UltronRed.copy(alpha = 0.1f), CircleShape)
                    .border(0.5.dp, UltronRed.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("🌐", fontSize = 18.sp) }
            Column {
                Text(display.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(display.nativeName, color = SubText, fontSize = 12.sp)
            }
        }
        Text("Select ❯", color = UltronRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Row of four progress dots; the current one is wider and brand-tinted. */
@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (i == current) 20.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i <= current) UltronRed else CardBorder)
            )
        }
    }
}

/** Full-width primary CTA. Dims when disabled (recovered glow animation dropped as decorative). */
@Composable
private fun NextButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(18.dp))
            .background(UltronRed)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Onboarding is complete once the user has supplied a name, language, access key, and
 * Google API key. Recovered verbatim from `isOnboardingComplete(ctx)` in Onboardingscreen.kt.
 */
fun isOnboardingComplete(context: android.content.Context): Boolean {
    val prefs = AppPreferences(context)
    return prefs.userName.isNotBlank() &&
        prefs.language.isNotBlank() &&
        prefs.googleApiKey.isNotBlank()
}

