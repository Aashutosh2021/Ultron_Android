// RECOVERY NOTE: layout approximated from shredded Compose
package com.flexfly.ultron.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.service.WakeWordService
import com.flexfly.ultron.i18n.INDIAN_LANGUAGES
import com.flexfly.ultron.model.LanguageInfo
import com.flexfly.ultron.ui.theme.CardBg
import com.flexfly.ultron.ui.theme.CardBorder
import com.flexfly.ultron.ui.theme.InputBg
import com.flexfly.ultron.ui.theme.UltronCrimson
import com.flexfly.ultron.ui.theme.UltronRed
import com.flexfly.ultron.ui.theme.SubText
import com.flexfly.ultron.ui.theme.UserCyan

// Accent colors were file-private constants in the decompiled SettingsscreenKt
// (Hero*/W*). Recovered here as local vals; the shared brand palette lives in theme/Color.kt.
private val HeroBlue = Color(0xFF4C86FA)
private val HeroCyan = Color(0xFF10C7EE)
private val HeroYellow = Color(0xFFF5C842)
private val HeroGreen = Color(0xFF2CD879)
private val HeroPurple = Color(0xFF8B5CF6)
private val HeroOrange = Color(0xFFFFA83C)
private val RemoveRed = Color(0xFFE44C44)

/**
 * Recovered from `SettingsscreenKt.SettingsScreen`. The original was a full-screen
 * LazyColumn of animated cards (FadeSlideIn + SettingsCard). The card chrome / glow
 * backgrounds are approximated; every settings row is bound to its original
 * [AppPreferences] property with the recovered (mostly Hinglish) copy.
 *
 * RECOVERY NOTE: the original language picker (`LanguagePickerDialog` from Languages.kt)
 * and the contact-picker ActivityResult flow are separate composables not reconstructed
 * here. Language is edited via an inline chip list; favorite contacts are shown read-only
 * with remove buttons (add-contact wiring is a TODO — needs the ActivityResultLauncher).
 */
@Composable
fun SettingsScreen(prefs: AppPreferences) {
    val service = WakeWordService.getActiveInstance()
    var showEnrollmentDialog by remember { mutableStateOf(false) }

    if (showEnrollmentDialog) {
        VoiceEnrollmentDialog(
            service = service,
            onDismiss = {
                showEnrollmentDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // --- Header ---
        item {
            Column {
                Text("SETTINGS", color = UltronRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Configure Ultron", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("Personalize your AI assistant experience", color = SubText, fontSize = 13.sp)
            }
        }

        // --- Profile ---
        item {
            SectionLabel("Profile")
            SettingsCard(HeroBlue) {
                CardHeader("Your Name", "Ultron will address you by this name", HeroBlue)
                var name by remember { mutableStateOf(prefs.userName) }
                SettingTextRow(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "e.g. Ravi",
                )
                SaveRow("Save Name") { if (name.isNotBlank()) prefs.userName = name.trim() }
            }
        }

        // --- Language ---
        item {
            SectionLabel("Language")
            SettingsCard(HeroCyan) {
                CardHeader(
                    "Assistant Language",
                    "Ultron will respond in this language for prompts, tools, and notifications",
                    HeroCyan,
                )
                var language by remember {
                    mutableStateOf(prefs.language.ifBlank { "Hinglish" })
                }
                var isLanguageExpanded by remember { mutableStateOf(false) }
                val current = INDIAN_LANGUAGES.firstOrNull { it.name.equals(language, true) }
                    ?: LanguageInfo(language, language, "Custom Language")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(0.02f), RoundedCornerShape(14.dp))
                        .border(0.5.dp, Color.White.copy(0.12f), RoundedCornerShape(14.dp))
                        .clickable { isLanguageExpanded = !isLanguageExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("${current.name} (${current.nativeName})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(current.region, color = SubText, fontSize = 11.sp)
                    }
                    Text(if (isLanguageExpanded) "Collapse ❮" else "Change ❯", color = HeroCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                AnimatedVisibility(visible = isLanguageExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(Color.White.copy(0.02f), RoundedCornerShape(14.dp))
                            .border(0.5.dp, Color.White.copy(0.12f), RoundedCornerShape(14.dp))
                    ) {
                        INDIAN_LANGUAGES.forEach { lang ->
                            val isSelected = lang.name.equals(language, true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        language = lang.name
                                        prefs.language = lang.name
                                        isLanguageExpanded = false
                                        WakeWordService.getActiveInstance()?.injectReminderMessage(
                                            "[SYSTEM] The user just changed your language to ${lang.name}. From now on, you MUST speak STRICTLY in ${lang.name}."
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${lang.name} (${lang.nativeName})",
                                        color = if (isSelected) Color.White else SubText,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(lang.region, color = SubText, fontSize = 11.sp)
                                }
                                if (isSelected) {
                                    Text("✓", color = HeroCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (lang != INDIAN_LANGUAGES.last()) {
                                Spacer(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(0.1f)))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "🌐 Ultron ki saari responses is language mein hongi — system prompts, tool messages, notifications, call announcements, sab kuch!",
                    color = HeroCyan.copy(0.65f),
                    fontSize = 11.sp,
                )
            }
        }

        // --- Voice Security ---
        item {
            SectionLabel("Voice Security")
            SettingsCard(HeroPurple) {
                CardHeader(
                    "Voice Profile",
                    "Only enrolled voices can wake up the assistant",
                    HeroPurple,
                )
                
                var isEnrolled by remember { mutableStateOf(service?.isVoiceEnrolled() ?: false) }
                // Reactively update if enrollment finishes while on screen
                val debugInfo by service?.getSpeakerDebugInfo()?.collectAsState(initial = null) ?: mutableStateOf(null)
                if (debugInfo?.enrollmentProgress == 3) {
                    isEnrolled = true
                }

                if (isEnrolled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(RemoveRed.copy(0.14f), RoundedCornerShape(14.dp))
                            .border(0.5.dp, RemoveRed.copy(0.3f), RoundedCornerShape(14.dp))
                            .clickable { 
                                service?.clearVoiceEnrollment()
                                isEnrolled = false
                            }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🗑", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset Voice Profile", color = RemoveRed.copy(0.9f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HeroPurple.copy(0.14f), RoundedCornerShape(14.dp))
                            .border(0.5.dp, HeroPurple.copy(0.3f), RoundedCornerShape(14.dp))
                            .clickable { 
                                service?.startVoiceEnrollment()
                                showEnrollmentDialog = true
                            }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🎙", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Enroll Voice", color = HeroPurple.copy(0.9f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Speak 'Ultron' continuously 3 times to enroll.",
                        color = HeroPurple.copy(0.65f),
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // --- Favorite Contacts ---
        item {
            SectionLabel("Favorite Contacts")
            SettingsCard(HeroYellow) {
                CardHeader(
                    "Priority Contacts",
                    "Agar same naam ke multiple contacts hain, Ultron favorites ko pehle use karega",
                    HeroYellow,
                )
                var showNicknameDialog by remember { mutableStateOf(false) }
                var selectedNameForFav by remember { mutableStateOf("") }
                var selectedNumberForFav by remember { mutableStateOf("") }
                var customNickname by remember { mutableStateOf("") }

                var contacts by remember { mutableStateOf(prefs.getFavoriteContacts()) }
                contacts.forEach { contact ->
                    FavoriteContactRow(contact) {
                        prefs.removeFavoriteContact(contact.number)
                        contacts = prefs.getFavoriteContacts()
                    }
                }
                val context = LocalContext.current
                val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
                    if (uri != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val cursor = context.contentResolver.query(uri, null, null, null, null)
                            var name = ""
                            var number = ""
                            if (cursor != null && cursor.moveToFirst()) {
                                val hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                                if (hasPhoneIdx != -1 && cursor.getString(hasPhoneIdx).toInt() > 0) {
                                    val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                                    val id = cursor.getString(idIdx)
                                    val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                                    name = cursor.getString(nameIdx) ?: ""
                                    
                                    val phoneCursor = context.contentResolver.query(
                                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                        null,
                                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                        arrayOf(id),
                                        null
                                    )
                                    if (phoneCursor != null && phoneCursor.moveToFirst()) {
                                        val numIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                        number = phoneCursor.getString(numIdx) ?: ""
                                    }
                                    phoneCursor?.close()
                                }
                            }
                            cursor?.close()
                            if (name.isNotBlank() && number.isNotBlank()) {
                                withContext(Dispatchers.Main) {
                                    selectedNameForFav = name
                                    selectedNumberForFav = number
                                    customNickname = ""
                                    showNicknameDialog = true
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HeroYellow.copy(0.14f), RoundedCornerShape(14.dp))
                        .border(0.5.dp, HeroYellow.copy(0.3f), RoundedCornerShape(14.dp))
                        .clickable { contactPicker.launch(null) }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⭐", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Favorite Contact", color = HeroYellow.copy(0.9f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                if (showNicknameDialog) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showNicknameDialog = false }) {
                        Column(
                            modifier = Modifier
                                .background(CardBg, RoundedCornerShape(20.dp))
                                .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                                .padding(24.dp)
                        ) {
                            Text("Add Favorite Contact", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Name: $selectedNameForFav", color = SubText, fontSize = 13.sp)
                            Text("Number: $selectedNumberForFav", color = SubText, fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            
                            Text("Nickname / Label (Optional)", color = HeroYellow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = customNickname,
                                onValueChange = { customNickname = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(InputBg, RoundedCornerShape(10.dp))
                                    .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            )
                            Spacer(Modifier.height(24.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    "Cancel",
                                    color = SubText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable { showNicknameDialog = false }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    "Save",
                                    color = HeroYellow,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            val labelVal = customNickname.trim().ifBlank { "Favorite" }
                                            prefs.addFavoriteContact(
                                                AppPreferences.FavoriteContact(
                                                    name = selectedNameForFav,
                                                    number = selectedNumberForFav,
                                                    label = labelVal
                                                )
                                            )
                                            contacts = prefs.getFavoriteContacts()
                                            showNicknameDialog = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    "💡 Example: Agar 3 'Harsh' hain aur ek favorite hai, Ultron automatically wahi select karega. Spelling variations (Priti, Preeti, Prity) bhi handle karega.",
                    color = HeroYellow.copy(0.65f),
                    fontSize = 11.sp,
                )
            }
        }

        // --- Call Settings ---
        item {
            SectionLabel("Call Settings")
            SettingsCard(HeroGreen) {
                CardHeader("Incoming Call Control", "Incoming call aane pe Ultron kya kare?", HeroGreen)
                var callControl by remember { mutableStateOf(prefs.callControlEnabled) }
                CallControlToggleRow(callControl) {
                    callControl = it
                    prefs.callControlEnabled = it
                }
            }
        }

        // --- Behavior ---
        item {
            SectionLabel("Behavior")
            SettingsCard(HeroPurple) {
                CardHeader(
                    "Active Timeout",
                    "Set how long Ultron stays active listening for your voice (10 to 60 seconds)",
                    HeroPurple,
                )
                var timeout by remember { mutableStateOf(prefs.maxActiveTimeout) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Active Duration", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("$timeout seconds", color = HeroPurple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = timeout.toFloat(),
                    onValueChange = { timeout = it.toInt() },
                    valueRange = 10f..60f,
                    steps = 50,
                )
                SaveRow("Save Timeout") { prefs.maxActiveTimeout = timeout }
                Text(
                    "⏱️ Wake word trigger hone ke baad agar aap chup rehte hain, toh Ultron itne seconds baad automatic close ho jayega.",
                    color = HeroPurple.copy(0.65f),
                    fontSize = 11.sp,
                )
            }
        }

        // --- Tool Permissions ---
        item {
            SectionLabel("Tool Permissions")
            SettingsCard(HeroOrange) {
                Text("CONTROL TOOLS", color = HeroOrange.copy(0.75f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text("Jo features chahiye wahi ON rakho", color = SubText, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                ToolToggles(prefs)
            }
        }

        // --- API Configuration ---
        item {
            SectionLabel("API Configuration")
            SettingsCard(HeroPurple) {
                CardHeader("Gemini API Key", "Required to power Ultron's AI responses", HeroPurple)
                var apiKey by remember { mutableStateOf(prefs.googleApiKey) }
                var showKey by remember { mutableStateOf(false) }
                ApiKeyField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "AIza...",
                    show = showKey,
                    onToggleShow = { showKey = !showKey },
                )
                SaveRow("Save API Key") { if (apiKey.isNotBlank()) prefs.googleApiKey = apiKey.trim() }
                KeyHint("Don't have a key?", "aistudio.google.com", HeroPurple)

                Spacer(Modifier.height(8.dp))
                CardHeader("OpenRouter API Key", "Required for screen and photo analysis", HeroOrange)
                var visionKey by remember { mutableStateOf(prefs.llamaVisionApiKey) }
                var showVisionKey by remember { mutableStateOf(false) }
                ApiKeyField(
                    value = visionKey,
                    onValueChange = { visionKey = it },
                    placeholder = "sk-or-...",
                    show = showVisionKey,
                    onToggleShow = { showVisionKey = !showVisionKey },
                )
                SaveRow("Save OpenRouter Key") { if (visionKey.isNotBlank()) prefs.llamaVisionApiKey = visionKey.trim() }
                KeyHint("Don't have a key?", "openrouter.ai", HeroOrange)
            }
        }

        // --- About / footer ---
        item {
            SectionLabel("About")
            SettingsCard(UltronCrimson) {
                Text("Ultron Voice Assistant", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Powered by FlexFly", color = SubText, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("VERSION", color = SubText, fontSize = 11.sp)
                    Text("v3.0", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** All 18 tool toggles from the decompiled "CONTROL TOOLS" card, each bound to its prefs flag. */
@Composable
private fun ToolToggles(prefs: AppPreferences) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var callAnnounce by remember { mutableStateOf(prefs.toolCallAnnounce) }
        SettingToggleRow("📞", "Call Announce", "Incoming calls ka naam bolna", callAnnounce, HeroGreen) { callAnnounce = it; prefs.toolCallAnnounce = it }

        var notifications by remember { mutableStateOf(prefs.toolNotifications) }
        SettingToggleRow("🔔", "Notifications", "App notifications bolna", notifications, UltronCrimson) { notifications = it; prefs.toolNotifications = it }

        var camera by remember { mutableStateOf(prefs.toolCamera) }
        SettingToggleRow("📸", "Camera", "Camera control & photo", camera, HeroCyan) { camera = it; prefs.toolCamera = it }

        var flashlight by remember { mutableStateOf(prefs.toolFlashlight) }
        SettingToggleRow("🔦", "Flashlight", "Torch on/off", flashlight, HeroYellow) { flashlight = it; prefs.toolFlashlight = it }

        var whatsApp by remember { mutableStateOf(prefs.toolWhatsApp) }
        SettingToggleRow("💬", "WhatsApp", "Messages & calls", whatsApp, HeroGreen) { whatsApp = it; prefs.toolWhatsApp = it }

        var sms by remember { mutableStateOf(prefs.toolSMS) }
        SettingToggleRow("💌", "SMS", "Text messages bhejna", sms, HeroCyan) { sms = it; prefs.toolSMS = it }

        var maps by remember { mutableStateOf(prefs.toolMaps) }
        SettingToggleRow("🗺️", "Maps", "Navigation & places", maps, UltronCrimson) { maps = it; prefs.toolMaps = it }

        var youTube by remember { mutableStateOf(prefs.toolYouTube) }
        SettingToggleRow("▶️", "YouTube", "Video search & play", youTube, Color(0xFFFF0000)) { youTube = it; prefs.toolYouTube = it }

        var volume by remember { mutableStateOf(prefs.toolVolumeControl) }
        SettingToggleRow("🔊", "Volume Control", "Volume up/down/mute", volume, HeroPurple) { volume = it; prefs.toolVolumeControl = it }

        var brightness by remember { mutableStateOf(prefs.toolBrightnessControl) }
        SettingToggleRow("☀️", "Brightness", "Screen brightness adjust", brightness, HeroYellow) { brightness = it; prefs.toolBrightnessControl = it }

        var network by remember { mutableStateOf(prefs.toolNetworkToggles) }
        SettingToggleRow("📡", "Network Toggles", "WiFi, Data, Bluetooth, etc.", network, HeroCyan) { network = it; prefs.toolNetworkToggles = it }

        var reminders by remember { mutableStateOf(prefs.toolReminders) }
        SettingToggleRow("⏰", "Reminders", "Set time-based reminders", reminders, HeroGreen) { reminders = it; prefs.toolReminders = it }

        var spotify by remember { mutableStateOf(prefs.toolSpotify) }
        SettingToggleRow("🎵", "Spotify", "Music playback control", spotify, Color(0xFF1DB954)) { spotify = it; prefs.toolSpotify = it }

        var phoneCalls by remember { mutableStateOf(prefs.toolPhoneCalls) }
        SettingToggleRow("📞", "Phone Calls", "Outgoing calls via contacts", phoneCalls, UltronCrimson) { phoneCalls = it; prefs.toolPhoneCalls = it }

        var systemInfo by remember { mutableStateOf(prefs.toolSystemInfo) }
        SettingToggleRow("📊", "System Info", "Time, Date, Battery, etc.", systemInfo, HeroYellow) { systemInfo = it; prefs.toolSystemInfo = it }

        var weather by remember { mutableStateOf(prefs.toolWeather) }
        SettingToggleRow("🌤️", "Weather", "Current weather info", weather, HeroCyan) { weather = it; prefs.toolWeather = it }

        var location by remember { mutableStateOf(prefs.toolLocation) }
        SettingToggleRow("📍", "Location", "GPS location check", location, HeroPurple) { location = it; prefs.toolLocation = it }

        var appLauncher by remember { mutableStateOf(prefs.toolAppLauncher) }
        SettingToggleRow("📱", "App Launcher", "Open any installed app", appLauncher, HeroGreen) { appLauncher = it; prefs.toolAppLauncher = it }

        var webSearch by remember { mutableStateOf(prefs.toolWebSearch) }
        SettingToggleRow("🌐", "Web Search", "Live web results for latest info", webSearch, HeroCyan) { webSearch = it; prefs.toolWebSearch = it }
    }
}

// --- Reusable rows / chrome (recovered from the file-private composables) ---

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = SubText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/** Recovered from `SettingsCard` — a bordered card tinted by an accent color. */
@Composable
private fun SettingsCard(accent: Color, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(20.dp))
            .border(0.5.dp, accent.copy(0.15f), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = { content() },
    )
}

/** Recovered from `CardHeader`: accent title + subtitle. */
@Composable
private fun CardHeader(title: String, subtitle: String, accent: Color) {
    Column {
        Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = SubText, fontSize = 12.sp)
    }
}

/** Recovered from `ToolToggleRow`: emoji icon + label/description + Switch. */
@Composable
private fun SettingToggleRow(
    emoji: String,
    label: String,
    description: String,
    checked: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, fontSize = 18.sp)
            Column {
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = SubText, fontSize = 11.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent.copy(0.7f),
            ),
        )
    }
}

/** Recovered from `CallControlToggleRow` — the label/emoji flip with [checked]. */
@Composable
private fun CallControlToggleRow(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val title = if (checked) "Receive / Reject Mode" else "Announce Only"
    val subtitle = if (checked) "Ultron poochega aur jawab pe action lega" else "Ultron sirf naam bolega, action aap loge"
    SettingToggleRow(
        emoji = if (checked) "📞" else "🔔",
        label = title,
        description = subtitle,
        checked = checked,
        accent = HeroGreen,
        onToggle = onToggle,
    )
}

/** Recovered from `GlassTextField`. */
@Composable
private fun SettingTextRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = SubText) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailing,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = InputBg,
            unfocusedContainerColor = InputBg,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
    )
}

/** Password-style API key field with a show/hide (👁️/🙈) toggle. */
@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    show: Boolean,
    onToggleShow: () -> Unit,
) {
    SettingTextRow(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailing = {
            Text(
                text = if (show) "🙈" else "👁️",
                modifier = Modifier.clickable { onToggleShow() }.padding(12.dp),
            )
        },
    )
}

/** The "Save X" / "✓ Saved" button. Simplified: single label (SaveButton in the original
 *  cross-faded to a saved label after tapping). */
@Composable
private fun SaveRow(label: String, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UltronRed.copy(0.15f), RoundedCornerShape(12.dp))
            .clickable { onSave() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, color = UltronRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The "Don't have a key? <site> →" hint row under each API field. */
@Composable
private fun KeyHint(text: String, site: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = SubText, fontSize = 12.sp)
        Row(
            modifier = Modifier
                .background(accent.copy(0.08f), RoundedCornerShape(100.dp))
                .border(0.5.dp, accent.copy(0.18f), RoundedCornerShape(100.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(site, color = accent.copy(0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text("→", color = accent.copy(0.6f), fontSize = 11.sp)
        }
    }
}

/** Recovered from `FavoriteContactRow`: ⭐ + name/number•label + × remove. */
@Composable
private fun FavoriteContactRow(contact: AppPreferences.FavoriteContact, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(0.02f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⭐", fontSize = 16.sp)
            Column {
                Text(contact.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val sub = contact.number + if (contact.label.isNotBlank()) " • ${contact.label}" else ""
                Text(sub, color = SubText, fontSize = 10.sp)
            }
        }
        Text(
            text = "×",
            color = RemoveRed.copy(0.85f),
            fontSize = 18.sp,
            modifier = Modifier
                .background(RemoveRed.copy(0.12f), RoundedCornerShape(50))
                .border(0.5.dp, RemoveRed.copy(0.25f), RoundedCornerShape(50))
                .clickable { onRemove() }
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}
@Composable
fun VoiceEnrollmentDialog(
    service: WakeWordService?,
    onDismiss: () -> Unit,
) {
    val debugInfo by service?.getSpeakerDebugInfo()?.collectAsState(initial = null) ?: mutableStateOf(null)
    
    val progress = debugInfo?.enrollmentProgress ?: 0
    val isComplete = progress >= 3

    androidx.compose.ui.window.Dialog(onDismissRequest = { 
        if (!isComplete) {
            service?.cancelVoiceEnrollment()
        }
        onDismiss() 
    }) {
        Column(
            modifier = Modifier
                .background(CardBg, RoundedCornerShape(20.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Voice Enrollment", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            if (isComplete) {
                Text("?", fontSize = 48.sp)
                Spacer(Modifier.height(16.dp))
                Text("Voice profile created successfully!", color = HeroGreen, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HeroGreen.copy(0.14f), RoundedCornerShape(14.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text("Done", color = HeroGreen, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("??", fontSize = 48.sp)
                Spacer(Modifier.height(16.dp))
                Text("Say 'Ultron'", color = HeroCyan, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("Progress: ${progress}/3", color = SubText, fontSize = 16.sp)
                
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RemoveRed.copy(0.14f), RoundedCornerShape(14.dp))
                        .clickable { 
                            service?.cancelVoiceEnrollment()
                            onDismiss() 
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text("Cancel", color = RemoveRed, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
