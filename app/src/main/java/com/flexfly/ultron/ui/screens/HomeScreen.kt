package com.flexfly.ultron.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.ui.theme.BgDark
import com.flexfly.ultron.ui.theme.CardBg
import com.flexfly.ultron.ui.theme.CardBorder
import com.flexfly.ultron.ui.theme.SubText
import com.flexfly.ultron.ui.theme.UltronCrimson
import com.flexfly.ultron.ui.theme.UltronEmber
import com.flexfly.ultron.ui.theme.UltronRed
import com.flexfly.ultron.ui.theme.UltronRedBright
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Enhanced Ultron home dashboard — structure modeled on the MAX reference UI
// (clock, wordmark, activation, system stat bars, command tiles) but re-skinned
// in Ultron's crimson/steel identity with bolder weights. Self-contained: reads
// AppPreferences + device stats directly, keeps the (isRunning, onStartStop)
// signature so AppRoot is untouched.

private val UltronGradient = Brush.linearGradient(listOf(UltronRedBright, UltronRed, UltronCrimson, UltronEmber))
private val GreenCharge = Color(0xFF34D399)

/** Live device stats, refreshed together on a timer. */
private data class DeviceStats(
    val batteryPct: Int = 0,
    val charging: Boolean = false,
    val ramUsedGb: Double = 0.0,
    val ramTotalGb: Double = 0.0,
    val storageUsedGb: Double = 0.0,
    val storageTotalGb: Double = 0.0,
)

/**
 * Main dashboard. Time-of-day greeting + live clock + status pill, the ULTRON
 * brand/activation block wired to [onStartStop], a calendar card, SYSTEM stat
 * cards with gradient progress bars, and a COMMANDS row.
 */
@Composable
fun HomeScreen(isRunning: Boolean, onStartStop: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val name = prefs.userName.ifBlank { "Boss" }
    val timeout = prefs.maxActiveTimeout

    var now by remember { mutableStateOf(Date()) }
    var stats by remember { mutableStateOf(DeviceStats()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            stats = readStats(context)
            delay(5000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        HomeHeader(name = name, now = now, isRunning = isRunning)
        BrandBlock(isRunning = isRunning, onStartStop = onStartStop)
        SectionLabel("CALENDAR", UltronRed)
        CalendarCard(now)
        SectionLabel("SYSTEM", UltronRed)
        SystemCards(stats)
        SectionLabel("COMMANDS", UltronRed)
        CommandsRow(timeoutSeconds = timeout)
        Text(
            "ULTRON  v3.0",
            color = SubText.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

// ── Header: greeting + big clock + date + status pill ────────────────────────
@Composable
private fun HomeHeader(name: String, now: Date, isRunning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(greeting(name), color = SubText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now),
                color = Color.White,
                fontSize = 52.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp,
            )
            Text(
                SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(now),
                color = SubText,
                fontSize = 13.sp,
            )
        }
        StatusPill(isRunning)
    }
}

@Composable
private fun StatusPill(isRunning: Boolean) {
    val accent = if (isRunning) UltronCrimson else SubText
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isRunning) UltronRed.copy(alpha = 0.14f) else CardBg)
            .border(0.8.dp, accent.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(7.dp))
        Text(
            if (isRunning) "ONLINE" else "OFFLINE",
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

// ── Brand block: eyebrow / wordmark / version / activation ───────────────────
@Composable
private fun BrandBlock(isRunning: Boolean, onStartStop: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Divider()
            Text(
                "  A I   V O I C E  ",
                color = UltronCrimson,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Divider()
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "ULTRON",
            style = TextStyle(brush = UltronGradient),
            fontSize = 72.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "•  VERSION 3.0  •",
            color = SubText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier
                .clip(CircleShape)
                .background(CardBg)
                .border(0.8.dp, CardBorder, CircleShape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            if (isRunning) "Ultron is listening" else "Say \"Ultron\" to activate",
            color = SubText,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        StartButton(isRunning = isRunning, onClick = onStartStop)
        if (isRunning) {
            Spacer(Modifier.height(16.dp))
            ManualTriggerButton()
        }
    }
}

@Composable
private fun ManualTriggerButton() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(CardBg)
            .border(1.dp, UltronEmber.copy(alpha = 0.5f), CircleShape)
            .clickable { com.flexfly.ultron.voice.VoiceService.getActiveInstance()?.triggerManualSession() }
            .padding(horizontal = 40.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(UltronEmber)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Speak Now (No Wake Word)",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StartButton(isRunning: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (isRunning) Modifier.background(UltronGradient)
                else Modifier.background(CardBg).border(1.dp, UltronRed.copy(alpha = 0.5f), CircleShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 40.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (isRunning) Color.White else UltronCrimson)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (isRunning) "Tap to stop" else "Tap to start",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Calendar card ────────────────────────────────────────────────────────────
@Composable
private fun CalendarCard(now: Date) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TODAY", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now), color = SubText, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(SimpleDateFormat("EEEE", Locale.getDefault()).format(now), color = UltronCrimson, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Day", color = SubText, fontSize = 10.sp)
            }
            Spacer(Modifier.width(14.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(UltronRed.copy(alpha = 0.14f))
                    .border(0.8.dp, UltronRed.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    SimpleDateFormat("dd", Locale.getDefault()).format(now),
                    color = UltronRedBright,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

// ── System stat cards with gradient progress bars ────────────────────────────
@Composable
private fun SystemCards(stats: DeviceStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            label = "BATTERY",
            value = "${stats.batteryPct}",
            unit = "%",
            fraction = stats.batteryPct / 100f,
            sub = if (stats.charging) "Charging" else "${stats.batteryPct}% left",
            chargingPill = stats.charging,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = "RAM",
            value = "%.0f".format(stats.ramUsedGb),
            unit = "/${"%.0f".format(stats.ramTotalGb)} GB",
            fraction = if (stats.ramTotalGb > 0) (stats.ramUsedGb / stats.ramTotalGb).toFloat() else 0f,
            sub = "${pct(stats.ramUsedGb, stats.ramTotalGb)}% used",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = "STORAGE",
            value = "%.0f".format(stats.storageUsedGb),
            unit = "GB",
            fraction = if (stats.storageTotalGb > 0) (stats.storageUsedGb / stats.storageTotalGb).toFloat() else 0f,
            sub = "${pct(stats.storageUsedGb, stats.storageTotalGb)}% used",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    unit: String,
    fraction: Float,
    sub: String,
    modifier: Modifier = Modifier,
    chargingPill: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(0.8.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(label, color = SubText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(2.dp))
            Text(unit, color = SubText, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (chargingPill) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GreenCharge.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(GreenCharge))
                Spacer(Modifier.width(5.dp))
                Text("Charging", color = GreenCharge, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
        ProgressBar(fraction)
        Spacer(Modifier.height(6.dp))
        Text(sub, color = SubText, fontSize = 10.sp)
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(CircleShape)
            .background(CardBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(UltronGradient),
        )
    }
}

// ── Commands row ─────────────────────────────────────────────────────────────
@Composable
private fun CommandsRow(timeoutSeconds: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CommandTile("🎙", "Hey Ultron", "Wake word", UltronRed, Modifier.weight(1f))
        CommandTile("✕", "Bye Bye", "Dismiss", UltronCrimson, Modifier.weight(1f))
        CommandTile("⏱", "${timeoutSeconds}s", "Timeout", UltronEmber, Modifier.weight(1f))
    }
}

@Composable
private fun CommandTile(glyph: String, title: String, sub: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(0.8.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.15f))
                .border(0.8.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = SubText, fontSize = 10.sp)
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(8.dp))
        Text(text, color = SubText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(0.8.dp, CardBorder, RoundedCornerShape(18.dp)),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(1.dp)
            .background(CardBorder),
    )
}

// ── Data helpers ─────────────────────────────────────────────────────────────
private fun greeting(name: String): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val part = when {
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    return "$part, $name"
}

private fun pct(used: Double, total: Double): Int =
    if (total > 0) ((used / total) * 100).toInt() else 0

private fun readStats(context: Context): DeviceStats {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val stat = StatFs(Environment.getDataDirectory().path)
    val totalBytes = stat.blockCountLong * stat.blockSizeLong
    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
    val gb = 1_073_741_824.0
    return DeviceStats(
        batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        charging = bm.isCharging,
        ramUsedGb = (mem.totalMem - mem.availMem) / gb,
        ramTotalGb = mem.totalMem / gb,
        storageUsedGb = (totalBytes - freeBytes) / gb,
        storageTotalGb = totalBytes / gb,
    )
}
