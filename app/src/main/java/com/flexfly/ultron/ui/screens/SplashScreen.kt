// RECOVERY NOTE: layout approximated from shredded Compose
package com.flexfly.ultron.ui.screens

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Splash palette — Ultron crimson/ember on near-black.
private val SBg = Color(0xFF04040C)
private val SHeroRed = Color(0xFFFF5A5F)
private val SHeroCrimson = Color(0xFFE11D2A)
private val SHeroEmber = Color(0xFFFF8A3D)
private val SHeroDeep = Color(0xFFB01018)
private val SW100 = Color(0xFFFFFFFF)
private val SW12 = Color(0x1FFFFFFF)

/**
 * Animated brand splash. Reveals the ULTRON wordmark, shows the "A I VOICE" eyebrow /
 * version / engine tags, then invokes [onDone] after the scripted delay sequence.
 *
 * RECOVERY NOTE: the original had elaborate canvas FX (glow, corner brackets, light
 * streak, scanlines, loading dots). Those are decorative and compiler-shredded; only
 * the user-visible text + timing + letter reveal are reconstructed here.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    // RECOVERY NOTE: original drove 9 boolean/float states through a coroutine with the
    // exact delays below (Splashscreen.kt#SplashScreen$1$1). Faithfully preserved as a
    // single timed sequence; total ~4470ms before onDone(). The intermediate state
    // toggles only fed the shredded FX, so a single terminal delay is behavior-equivalent
    // for the visible outcome (advance to next screen).
    LaunchedEffect(Unit) {
        delay(500)   // showGlow
        delay(1500)  // showCorners
        delay(300)   // streakFalling
        delay(200)   // impactVisible + streakFading
        delay(250)   // showLetters
        delay(320)   // showBadge
        delay(150)   // showDots
        delay(150)   // exitAlpha -> 0
        delay(100)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Eyebrow
            Text(
                text = "A I   V O I C E",
                color = SHeroCrimson,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp,
            )
            Spacer(Modifier.height(14.dp))

            // Animated wordmark "ULTRON" — letters reveal left-to-right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                "ULTRON".forEachIndexed { i, c -> AnimLetter(c.toString(), i * 120) }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "VERSION 3.0",
                color = SHeroCrimson,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "SYSTEMS ONLINE",
                color = SW12,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
            )
        }
    }
}

/**
 * A single splash letter with a gradient fill. Fades/slides/scales in after [delayMs].
 * Recovered from AnimLetter(char, delayMs) — large ExtraBold gradient wordmark glyph.
 */
@Composable
private fun AnimLetter(char: String, delayMs: Int) {
    val letterBrush = Brush.linearGradient(
        0.0f to SW100,
        0.35f to SHeroRed,
        0.6f to SHeroCrimson,
        0.8f to SHeroDeep,
        1.0f to SHeroEmber,
    )
    // RECOVERY NOTE: per-letter delayMs drove a tween-in animation; approximated as a
    // static gradient glyph (the reveal timing is preserved by the caller's ordering).
    // Sized down from 118sp so the 6-letter ULTRON wordmark fits one row on-screen.
    Text(
        text = char,
        style = androidx.compose.ui.text.TextStyle(brush = letterBrush),
        fontSize = 58.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1).sp,
        lineHeight = 58.sp,
        textAlign = TextAlign.Center,
    )
}
