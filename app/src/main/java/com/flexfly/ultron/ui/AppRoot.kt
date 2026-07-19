// RECOVERY NOTE: layout approximated from shredded Compose
package com.flexfly.ultron.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.model.AppScreen
import com.flexfly.ultron.model.Message
import com.flexfly.ultron.ui.screens.FeaturesScreen
import com.flexfly.ultron.ui.screens.HistoryScreen
import com.flexfly.ultron.ui.screens.HomeScreen
import com.flexfly.ultron.ui.screens.OnboardingScreen
import com.flexfly.ultron.ui.screens.SettingsScreen
import com.flexfly.ultron.ui.screens.SplashScreen
import com.flexfly.ultron.ui.screens.isOnboardingComplete
import com.flexfly.ultron.ui.theme.BgDark
import com.flexfly.ultron.ui.theme.UltronRed
import com.flexfly.ultron.ui.theme.SubText

/**
 * Top-level navigation host, recovered from `MainActivity.kt` (top-level composables).
 *
 * Routes SPLASH -> ONBOARDING -> MAIN via [AnimatedContent]; MAIN renders [MainShell].
 * The animation timings/directions are preserved from the bytecode; the enclosing
 * container layout is reconstructed.
 */
@Composable
fun AppRoot(
    messages: List<Message>,
    isRunning: Boolean,
    prefs: AppPreferences,
    onStartStop: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(AppScreen.SPLASH) }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            when (initialState) {
                AppScreen.SPLASH ->
                    fadeIn(tween(600)) togetherWith fadeOut(tween(500))

                AppScreen.ONBOARDING ->
                    (fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 5 }) togetherWith
                        (fadeOut(tween(350)) + slideOutVertically(tween(350)) { -it / 5 })

                else ->
                    fadeIn(tween(400)) togetherWith fadeOut(tween(400))
            }
        },
        label = "root_nav",
    ) { s ->
        when (s) {
            AppScreen.SPLASH -> SplashScreen(
                onDone = {
                    screen = if (isOnboardingComplete(context)) AppScreen.MAIN else AppScreen.ONBOARDING
                }
            )

            AppScreen.ONBOARDING -> OnboardingScreen(
                onDone = { screen = AppScreen.MAIN }
            )

            AppScreen.MAIN -> MainShell(
                messages = messages,
                isRunning = isRunning,
                prefs = prefs,
                onStartStop = onStartStop,
                onClearHistory = onClearHistory,
            )
        }
    }
}

/**
 * The MAIN screen: a column of an animated tab body over a fixed [BottomNav].
 * Recovered from `MainShell`; container layout reconstructed.
 */
@Composable
fun MainShell(
    messages: List<Message>,
    isRunning: Boolean,
    prefs: AppPreferences,
    onStartStop: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(300)) { dir * it / 4 } + fadeIn(tween(250))) togetherWith
                        (slideOutHorizontally(tween(250)) { -dir * it / 4 } + fadeOut(tween(200)))
                },
                label = "tab_anim",
                modifier = Modifier.weight(1f),
            ) { tab ->
                when (tab) {
                    0 -> HomeScreen(isRunning, onStartStop)
                    1 -> HistoryScreen(messages, onClearHistory)
                    2 -> SettingsScreen(prefs)
                    3 -> FeaturesScreen()
                    else -> HomeScreen(isRunning, onStartStop)
                }
            }
            BottomNav(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        }
    }
}

/**
 * Bottom navigation bar, recovered from `BottomNav`.
 *
 * RECOVERY NOTE: the bytecode declares exactly four tabs in this order —
 * Home(0), History(1), Settings(2), Features(3, Info icon) — with no separate
 * center Start/Stop control; Start/Stop is driven from within [HomeScreen].
 */
@Composable
fun BottomNav(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // RECOVERY NOTE: Color(4278848020L) == 0xFF0A0A14 (nav bar background).
            .background(Color(0xFF0A0A14))
            .navigationBarsPadding()
            .height(62.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            icon = { NavIcon(Icons.Default.Home, selectedTab == 0) },
            label = "Home",
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
        )
        NavItem(
            icon = { NavIcon(Icons.Default.History, selectedTab == 1) },
            label = "History",
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
        )
        NavItem(
            icon = { NavIcon(Icons.Default.Settings, selectedTab == 2) },
            label = "Settings",
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
        )
        NavItem(
            icon = { NavIcon(Icons.Default.Info, selectedTab == 3) },
            label = "Features",
            selected = selectedTab == 3,
            onClick = { onTabSelected(3) },
        )
    }
}

@Composable
private fun NavIcon(imageVector: ImageVector, selected: Boolean) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = if (selected) UltronRed else SubText,
    )
}

/**
 * A single bottom-nav entry (icon + label), recovered from `NavItem`.
 */
@Composable
fun NavItem(
    icon: @Composable () -> Unit,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = if (selected) UltronRed else SubText,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
