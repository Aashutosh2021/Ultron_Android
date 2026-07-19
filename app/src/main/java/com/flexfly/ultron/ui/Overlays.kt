package com.flexfly.ultron.ui

import android.graphics.PointF
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import com.flexfly.ultron.ui.theme.SunsetYellow
import com.flexfly.ultron.ui.theme.UltronCrimson
import com.flexfly.ultron.ui.theme.UltronRed
import com.flexfly.ultron.ui.theme.UserCyan

// RECOVERY NOTE: visual overlays reconstructed functionally from compiled Compose
// (Wakewordservice.kt / Edgeglowoverlay.kt). The exact multi-layer glow/shimmer/vignette
// stack is decorative and not byte-recoverable; behavior — a state-tinted animated screen
// edge and a tap ripple — is preserved.

/**
 * Full-screen edge glow shown while Ultron is active. The border colour reflects
 * who is "talking": user (cyan), Ultron (crimson), or idle-but-connected (red).
 * Signature: `EdgeGlowOverlay(isUserSpeaking, isUltronSpeaking, isConnected, shouldDismiss)`.
 */
@Composable
fun EdgeGlowOverlay(
    isUserSpeaking: Boolean,
    isUltronSpeaking: Boolean,
    isConnected: Boolean,
    shouldDismiss: Boolean,
) {
    val target = when {
        shouldDismiss || !isConnected -> 0f
        else -> 1f
    }
    val alpha by animateFloatAsState(target, tween(400), label = "edge_alpha")
    val progress by animateFloatAsState(
        target,
        tween(1200, easing = FastOutSlowInEasing),
        label = "edge_progress"
    )
    if (alpha <= 0.01f) return

    val baseColor = when {
        isUltronSpeaking -> SunsetYellow
        else -> UltronRed
    }
    val glowColor by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(500),
        label = "glow_color"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 2.dp.toPx()
        val inset = strokeWidth / 2f
        val p = progress.coerceIn(0f, 1f)
        val a = alpha.coerceIn(0f, 1f)

        val corner = 26.dp.toPx()
        val cornerDiam = corner * 2f

        val leftPath = Path().apply {
            moveTo(size.width / 2f, size.height - inset)
            lineTo(inset + corner, size.height - inset)
            arcTo(
                rect = Rect(inset, size.height - inset - cornerDiam, inset + cornerDiam, size.height - inset),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            lineTo(inset, inset + corner)
            arcTo(
                rect = Rect(inset, inset, inset + cornerDiam, inset + cornerDiam),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            lineTo(size.width / 2f, inset)
        }
        val rightPath = Path().apply {
            moveTo(size.width / 2f, size.height - inset)
            lineTo(size.width - inset - corner, size.height - inset)
            arcTo(
                rect = Rect(size.width - inset - cornerDiam, size.height - inset - cornerDiam, size.width - inset, size.height - inset),
                startAngleDegrees = 90f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false
            )
            lineTo(size.width - inset, inset + corner)
            arcTo(
                rect = Rect(size.width - inset - cornerDiam, inset, size.width - inset, inset + cornerDiam),
                startAngleDegrees = 0f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false
            )
            lineTo(size.width / 2f, inset)
        }

        val measure = PathMeasure()
        val outPath = Path()

        // Draw left side
        measure.setPath(leftPath, false)
        measure.getSegment(0f, measure.length * p, outPath, true)
        drawPath(outPath, color = glowColor.copy(alpha = a * 0.15f), style = Stroke(width = strokeWidth * 8))
        drawPath(outPath, color = glowColor.copy(alpha = a * 0.3f), style = Stroke(width = strokeWidth * 4))
        drawPath(outPath, color = glowColor.copy(alpha = a), style = Stroke(width = strokeWidth))

        // Draw right side
        outPath.reset()
        measure.setPath(rightPath, false)
        measure.getSegment(0f, measure.length * p, outPath, true)
        drawPath(outPath, color = glowColor.copy(alpha = a * 0.15f), style = Stroke(width = strokeWidth * 8))
        drawPath(outPath, color = glowColor.copy(alpha = a * 0.3f), style = Stroke(width = strokeWidth * 4))
        drawPath(outPath, color = glowColor.copy(alpha = a), style = Stroke(width = strokeWidth))
    }
}

/**
 * A single expanding ripple drawn at [coords] to visualise an auto-tap gesture, then
 * calls [onFinished]. Recovered timing: 600ms tween — scale 0→1 (LinearOutSlowIn),
 * alpha 1→0 (FastOutSlowIn).
 */
@Composable
fun GhostPointerRipple(coords: PointF, onFinished: () -> Unit) {
    var triggered by remember { mutableStateOf(false) }
    LaunchedEffect(coords) {
        triggered = true
    }
    val scale by animateFloatAsState(
        if (triggered) 1f else 0f,
        tween(600, easing = LinearOutSlowInEasing),
        label = "pointer_scale",
    )
    val alpha by animateFloatAsState(
        if (triggered) 0f else 1f,
        tween(600, easing = FastOutSlowInEasing),
        label = "pointer_alpha",
        finishedListener = { if (triggered) onFinished() },
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val maxR = 90.dp.toPx()
        drawCircle(
            color = UltronRed.copy(alpha = alpha),
            radius = maxR * scale,
            center = Offset(coords.x, coords.y),
        )
    }
}
