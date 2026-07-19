package com.flexfly.ultron.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ultron theme — always-dark, crimson-forward. onPrimary/onSurface pinned to
// bright white so bold headings read hard against the steel-black grounds.
private val DarkColorScheme = darkColorScheme(
    primary = UltronRed,
    onPrimary = Color.White,
    secondary = UltronCrimson,
    tertiary = UserCyan,
    background = BgDark,
    onBackground = Color.White,
    surface = CardBg,
    onSurface = Color.White,
    surfaceVariant = InputBg,
)

@Composable
fun UltronTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MaterialTheme.typography, // original used default Typography
        content = content,
    )
}
