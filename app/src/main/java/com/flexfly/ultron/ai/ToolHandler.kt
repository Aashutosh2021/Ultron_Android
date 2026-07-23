package com.flexfly.ultron.ai

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.flexfly.ultron.apps.AppLaunchManager
import com.flexfly.ultron.apps.GoogleSearchManager
import com.flexfly.ultron.apps.MapsManager
import com.flexfly.ultron.apps.SpotifyManager
import com.flexfly.ultron.apps.WhatsAppManager
import com.flexfly.ultron.apps.YouTubeManager
import com.flexfly.ultron.automation.AutoTyper
import com.flexfly.ultron.automation.UltronAccessibilityService
import com.flexfly.ultron.automation.ScreenReader
import com.flexfly.ultron.automation.ScrollManager
import com.flexfly.ultron.comms.CallAnswerManager
import com.flexfly.ultron.comms.ContactManager
import com.flexfly.ultron.comms.EmergencySOSManager
import com.flexfly.ultron.comms.UltronSmsManager
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.device.BrightnessManager
import com.flexfly.ultron.device.CameraControlManager
import com.flexfly.ultron.device.CameraManager
import com.flexfly.ultron.device.FlashlightManager
import com.flexfly.ultron.device.NetworkToggleManager
import com.flexfly.ultron.device.SystemInfoTools
import com.flexfly.ultron.device.VolumeManager
import com.flexfly.ultron.info.NewsManager
import com.flexfly.ultron.info.WeatherManager
import com.flexfly.ultron.receiver.UltronDeviceAdminReceiver
import com.flexfly.ultron.reminder.ReminderManager
import com.flexfly.ultron.security.AntiTheftManager
import com.flexfly.ultron.service.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central `[TOOL:...]` command dispatcher. [handle] takes the tool string extracted
 * from Gemini's speech (see [GeminiLiveSession]) and routes it to the appropriate
 * device / apps / comms / automation manager, returning a short Hinglish confirmation
 * string that gets injected back as a `[SYSTEM]` message.
 *
 * Recovered from `Toolhandler.kt`. The huge dispatch was a single Kotlin `when` on the
 * lowercased/trimmed tool name; the decompiler rendered it as a deeply-nested if/else
 * chain — restored here to a flat `when`. Every dispatch branch, tool alias, and
 * Hinglish literal is preserved verbatim (they are load-bearing for the assistant).
 *
 * Async tools (weather, location, camera, scroll, YouTube, screen-analysis) fire their
 * manager with a callback that injects the result later via
 * [WakeWordService.injectReminderMessage]; [handle] returns an immediate "kar rahi hoon"
 * acknowledgement for those.
 *
 * Cross-file refs — the managers live in the device/apps/comms/automation/etc. packages
 * (see imports). [CameraVisionManager] shares this `com.flexfly.ultron.ai` package.
 */
object ToolHandler {

    private const val TAG = "ToolHandler"

    var flashlightManager: FlashlightManager? = null
    private var cameraControlManager: CameraControlManager? = null
    private var devicePolicyManager: DevicePolicyManager? = null
    private var adminComponent: ComponentName? = null

    private var pendingWhatsAppContacts: List<ContactManager.Contact>? = null
    private var pendingWhatsAppMessage: String? = null
    private var pendingCallContacts: List<ContactManager.Contact>? = null

    /**
     * From a list of ambiguous contacts and a free-form [query] (ordinal words, phone
     * labels, last-4-digits, or a name fragment), pick the intended contact — or null.
     */
    fun resolvePendingContact(
        contacts: List<ContactManager.Contact>,
        query: String,
    ): ContactManager.Contact? {
        val q = query.lowercase(Locale.ROOT).trim()
        val index = when {
            q.contains("first") || q.contains("1st") || q.contains("number 1") ||
                q.contains("pehla") || q.contains("pahla") || q == "1" || q == "one" ||
                q.contains("ek number") -> 0
            q.contains("second") || q.contains("2nd") || q.contains("number 2") ||
                q.contains("dusra") || q.contains("doosra") || q == "2" || q == "two" ||
                q.contains("do number") -> 1
            q.contains("third") || q.contains("3rd") || q.contains("number 3") ||
                q.contains("teesra") || q.contains("tisra") || q == "3" || q == "three" ||
                q.contains("teen number") -> 2
            q.contains("fourth") || q.contains("4th") || q.contains("number 4") ||
                q.contains("chautha") || q == "4" || q == "four" -> 3
            q.contains("fifth") || q.contains("5th") || q.contains("number 5") ||
                q.contains("panchwa") || q == "5" || q == "five" -> 4
            else -> -1
        }
        if (index in 0 until contacts.size) {
            return contacts[index]
        }

        val label = listOf("mobile", "work", "home", "office", "personal", "other")
            .firstOrNull { q.contains(it) }
        if (label != null) {
            contacts.firstOrNull { it.label.lowercase(Locale.ROOT).contains(label) }
                ?.let { return it }
        }

        val digits = Regex("[^0-9]").replace(q, "")
        if (digits.length >= 4) {
            val last4 = digits.takeLast(4)
            contacts.firstOrNull {
                Regex("[^0-9]").replace(it.number, "").endsWith(last4)
            }?.let { return it }
        }

        return contacts.firstOrNull { contact ->
            val name = contact.name.lowercase(Locale.ROOT)
            name.contains(q) || q.contains(name) ||
                contact.name.split(" ").any { it.length > 2 && q.contains(it) }
        }
    }

    fun initialize(context: Context) {
        if (flashlightManager == null) {
            flashlightManager = FlashlightManager(context.applicationContext)
        }
        if (cameraControlManager == null) {
            cameraControlManager = CameraControlManager(context.applicationContext)
        }
        initDeviceAdmin(context.applicationContext)
    }

    fun initDeviceAdmin(context: Context) {
        devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(context, UltronDeviceAdminReceiver::class.java)
    }

    fun handle(toolName: String, context: Context): String {
        val lower = toolName.trim().lowercase(Locale.ROOT)
        Log.d(TAG, "🔧 Tool: '$lower'")
        val prefs = AppPreferences(context)
        var language = prefs.language
        if (language.isBlank()) {
            language = "Hinglish"
        }
        val userLang = language

        // Extract the ":"-argument (original text, not lowercased) for a given prefix.
        fun arg(): String = toolName.substringAfter(":", "").trim()

        val result: String = when {
            lower == "call_accept" || lower == "receive_call" || lower == "call_receive" ||
                lower == "answer_call" || lower == "accept_call" || lower == "pick_call" ||
                lower == "uthao" || lower == "utha_lo" || lower == "pickup" -> {
                when {
                    !prefs.toolCallAnnounce -> "Call control settings mein off hai Boss!"
                    !prefs.callControlEnabled ->
                        "Call receive control off hai Boss! Settings mein on karo."
                    else -> {
                        Log.d(TAG, "📞 Accepting call")
                        if (CallAnswerManager.acceptCall(context)) "Call receive kar liya Boss!"
                        else "Call receive nahi ho payi Boss!"
                    }
                }
            }

            lower == "read_screen" || lower == "screen_read" || lower == "screen_batao" ->
                if (prefs.toolScreenReader) ScreenReader.readScreen(context)
                else "Screen reader settings mein off hai Boss!"

            lower.startsWith("read_app:") ->
                if (prefs.toolScreenReader)
                    ScreenReader.readAppScreen(context, arg())
                else "Screen reader settings mein off hai Boss!"

            lower.startsWith("click:") -> {
                if (!prefs.toolScreenReader) {
                    "Screen reader settings mein off hai Boss!"
                } else {
                    val target = arg()
                    when {
                        target.isEmpty() -> "Kya click karna hai Boss?"
                        ScreenReader.smartClickText(context, target) ->
                            "'$target' par click kar diya Boss!"
                        else ->
                            "Boss, '$target' screen pe nahi mila ya click nahi ho paya!"
                    }
                }
            }

            lower.startsWith("type:") -> {
                if (!prefs.toolAutoType) {
                    "Auto typing settings mein off hai Boss!"
                } else {
                    val text = arg()
                    if (text.isEmpty()) {
                        "Kya type karun Boss?"
                    } else {
                        AutoTyper.typeText(context, text, false) { _, message ->
                            injectOnMain(message)
                        }
                        "Type kar rahi hoon Boss!"
                    }
                }
            }

            lower.startsWith("paste:") -> {
                if (!prefs.toolAutoType) {
                    "Auto typing settings mein off hai Boss!"
                } else {
                    val text = arg()
                    if (text.isEmpty()) {
                        "Kya paste karun Boss?"
                    } else {
                        AutoTyper.typeText(context, text, true) { _, message ->
                            injectOnMain(message)
                        }
                        "Paste kar rahi hoon Boss!"
                    }
                }
            }

            lower == "clear_text" -> {
                if (!prefs.toolAutoType) {
                    "Auto typing settings mein off hai Boss!"
                } else {
                    AutoTyper.clearTextField { success ->
                        injectOnMain(
                            if (success) "Text clear kar diya Boss!"
                            else "Text clear nahi ho saka Boss!"
                        )
                    }
                    "Clear kar rahi hoon Boss!"
                }
            }

            lower == "analyze_screen" || lower.startsWith("analyze_screen:") -> {
                if (!prefs.toolScreenReader) {
                    "Screen reader settings mein off hai Boss!"
                } else if (UltronAccessibilityService.isPhoneLocked(context)) {
                    "Phone unlock karo Boss! Lock hai, screen analyze nahi kar sakti."
                } else {
                    val service = UltronAccessibilityService.getInstance()
                    if (service == null) {
                        if (UltronAccessibilityService.isEnabledInSettings(context)) {
                            "Accessibility service settings mein enabled hai par active nahi hai Boss! Settings mein jaakar Ultron accessibility ko ek baar OFF karke ON kijiye."
                        } else {
                            "Accessibility service on nahi hai Boss! Settings mein Ultron ko enable karo."
                        }
                    } else {
                        val question = if (toolName.contains(":")) arg() else ""
                        service.captureScreen { bitmap ->
                            if (bitmap != null) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    analyzeScreenshot(context, prefs, bitmap, question)
                                }
                            } else {
                                WakeWordService.getActiveInstance()
                                    ?.injectReminderMessage("Screenshot nahi le paya Boss!")
                            }
                        }
                        if (question.isNotEmpty()) "Screen dekh kar bata rahi hoon Boss!"
                        else "Screen dekh rahi hoon Boss!"
                    }
                }
            }

            lower == "analyze_last_photo" || lower.startsWith("analyze_last_photo:") -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    val uriStr = prefs.lastCapturedPhotoUri
                    val question = if (toolName.contains(":")) arg() else ""
                    if (uriStr.isNotBlank()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            analyzeLastPhoto(context, uriStr, question)
                        }
                        if (question.isNotEmpty()) "Pichli photo dekh kar bata rahi hoon Boss!"
                        else "Pichli photo dekh rahi hoon Boss!"
                    } else {
                        "Pehle koi photo nahi li gayi hai Boss!"
                    }
                }
            }

            lower == "photo_analyze" || lower.startsWith("photo_analyze:") ||
                lower == "selfie_analyze" || lower.startsWith("selfie_analyze:") -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    val isSelfie = lower.startsWith("selfie_analyze")
                    val question = if (toolName.contains(":")) arg() else ""
                    val cameraFacing = if (isSelfie) 1 else 0
                    CameraManager.takePhotoAndAnalyze(context, cameraFacing, question) { success ->
                        val message = when {
                            !success -> "Photo click nahi ho paya Boss!"
                            cameraFacing == 1 ->
                                "Selfie click kar diya Boss! Abhi analyze karke batati hoon."
                            else ->
                                "Photo click kar diya Boss! Abhi analyze karke batati hoon."
                        }
                        Handler(Looper.getMainLooper()).post {
                            val translated = translateResponse(message, userLang)
                            WakeWordService.getActiveInstance()?.injectReminderMessage(translated)
                        }
                    }
                    "Camera control settings mein off hai Boss!"
                    // RECOVERY NOTE: original returns the still-uninitialized `str5`
                    // ("Auto typing settings mein off hai Boss!") for this async branch —
                    // the immediate return value is irrelevant here since the real reply is
                    // injected via the callback above. Preserved behavior: return whatever
                    // the default-slot holds. Using the camera-off default (nearest enclosing
                    // scope) to stay faithful; the value is never surfaced to the user.
                }
            }

            lower == "take_photo" || lower == "click_photo" || lower == "photo" ||
                lower == "selfie" -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    CameraManager.takePhoto(context, if (lower == "selfie") 1 else 0) { success ->
                        val message = if (success) {
                            if (lower == "selfie") "Selfie click kar diya Boss!"
                            else "Photo click kar diya Boss!"
                        } else {
                            "Photo click nahi ho paya Boss!"
                        }
                        Handler(Looper.getMainLooper()).post {
                            val translated = translateResponse(message, userLang)
                            WakeWordService.getActiveInstance()?.injectReminderMessage(translated)
                        }
                    }
                    "Auto typing settings mein off hai Boss!"
                    // RECOVERY NOTE: as above, this async branch returns the uninitialized
                    // default slot (str5); the user-facing reply comes from the callback.
                }
            }

            lower == "call_reject" || lower == "reject_call" || lower == "decline_call" ||
                lower == "cut_call" || lower == "end_call" || lower == "hangup" ||
                lower == "hang_up" || lower == "mat_uthao" || lower == "kaat_de" ||
                lower == "reject" -> {
                when {
                    !prefs.toolCallAnnounce -> "Call control settings mein off hai Boss!"
                    !prefs.callControlEnabled ->
                        "Call reject control off hai Boss! Settings mein on karo."
                    else -> {
                        Log.d(TAG, "📵 Rejecting call")
                        if (CallAnswerManager.rejectCall(context)) "Call reject kar diya Boss!"
                        else "Call reject nahi ho payi Boss!"
                    }
                }
            }

            lower == "go_home" -> {
                goToHome(context)
                "Home screen khol diya Boss!"
            }

            lower == "flashlight_on" || lower == "flashlight_off" ||
                lower == "flashlight_toggle" -> {
                if (!prefs.toolFlashlight) {
                    "Flashlight control settings mein off hai Boss!"
                } else when (lower) {
                    "flashlight_on" -> {
                        val fm = flashlightManager
                        if (fm == null) "Flashlight on nahi ho paya Boss!"
                        else if (fm.turnOn()) "Flashlight on kar diya Boss!"
                        else "Flashlight on nahi ho paya Boss!"
                    }
                    "flashlight_off" -> {
                        val fm = flashlightManager
                        if (fm == null || !fm.turnOff()) "Flashlight off nahi ho paya Boss!"
                        else "Flashlight off kar diya Boss!"
                    }
                    else -> {
                        val fm = flashlightManager
                        if (fm == null || !fm.toggle()) "Flashlight off kar diya Boss!"
                        else "Flashlight on kar diya Boss!"
                    }
                }
            }

            lower == "open_camera" || lower == "camera_open" || lower == "camera" -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    CameraManager.takePhoto(context, 0) { }
                    "Camera khol diya Boss! Photo lene ke liye shutter tap karo."
                }
            }

            lower == "flip_camera" || lower == "camera_flip" || lower == "switch_camera" ||
                lower == "camera_switch" -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    cameraControlManager?.switchCamera()
                    "Camera flip kar diya Boss!"
                }
            }

            lower == "click_shutter" || lower == "shutter" || lower == "just_click" ||
                lower == "click_now" -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    cameraControlManager?.clickShutter()
                    "Shutter click kar diya Boss!"
                }
            }

            lower == "get_time" || lower == "time" || lower == "time_batao" ->
                if (prefs.toolSystemInfo) "Abhi time ${SystemInfoTools.getTime()} hai Boss!"
                else "System info settings mein off hai Boss!"

            lower == "get_date" || lower == "date" || lower == "date_batao" ||
                lower == "aaj_kya_hai" ->
                if (prefs.toolSystemInfo) "Aaj ${SystemInfoTools.getDate()} hai Boss!"
                else "System info settings mein off hai Boss!"

            lower == "get_day" || lower == "day" || lower == "day_batao" ||
                lower == "aaj_kaun_sa_din" ->
                if (prefs.toolSystemInfo) "Aaj ${SystemInfoTools.getDay()} hai Boss!"
                else "System info settings mein off hai Boss!"

            lower == "get_battery" || lower == "battery" || lower == "battery_batao" ||
                lower == "kitni_battery" -> {
                if (!prefs.toolSystemInfo) {
                    "System info settings mein off hai Boss!"
                } else {
                    val percent = SystemInfoTools.getBatteryPercentage(context)
                    val charging = SystemInfoTools.isCharging(context)
                    if (percent >= 0) {
                        "Battery $percent percent hai Boss" +
                            (if (charging) ", aur charging chal rahi hai" else "") + "!"
                    } else {
                        "Battery info nahi mil rahi Boss!"
                    }
                }
            }

            lower == "get_datetime" || lower == "time_date" || lower == "datetime" ->
                if (prefs.toolSystemInfo)
                    "Boss, abhi ${SystemInfoTools.getDay()} hai, ${SystemInfoTools.getDate()}, aur time ${SystemInfoTools.getTime()} hai!"
                else "System info settings mein off hai Boss!"

            lower == "volume_up" ->
                if (prefs.toolVolumeControl) VolumeManager.volumeUp(context)
                else "Volume control settings mein off hai Boss!"

            lower == "volume_down" ->
                if (prefs.toolVolumeControl) VolumeManager.volumeDown(context)
                else "Volume control settings mein off hai Boss!"

            lower == "volume_max" || lower == "full_volume" || lower == "volume_full" ->
                if (prefs.toolVolumeControl) VolumeManager.setMaxVolume(context)
                else "Volume control settings mein off hai Boss!"

            lower == "volume_mute" || lower == "mute" ->
                if (prefs.toolVolumeControl) VolumeManager.mute(context)
                else "Volume control settings mein off hai Boss!"

            lower == "volume_unmute" || lower == "unmute" ->
                if (prefs.toolVolumeControl) VolumeManager.unmute(context)
                else "Volume control settings mein off hai Boss!"

            lower == "silent_mode" || lower == "phone_silent" ->
                if (prefs.toolVolumeControl) VolumeManager.setSilentMode(context)
                else "Volume control settings mein off hai Boss!"

            lower == "vibrate_mode" || lower == "phone_vibrate" ->
                if (prefs.toolVolumeControl) VolumeManager.setVibrateMode(context)
                else "Volume control settings mein off hai Boss!"

            lower == "normal_mode" || lower == "phone_normal" || lower == "sound_on" ->
                if (prefs.toolVolumeControl) VolumeManager.setNormalMode(context)
                else "Volume control settings mein off hai Boss!"

            lower == "get_volume" || lower == "volume_status" || lower == "volume_kitna" ->
                if (prefs.toolVolumeControl) VolumeManager.getVolumeStatus(context)
                else "Volume control settings mein off hai Boss!"

            lower.startsWith("volume_set:") ->
                if (prefs.toolVolumeControl)
                    VolumeManager.setVolume(context, arg().toIntOrNull() ?: 50)
                else "Volume control settings mein off hai Boss!"

            lower == "brightness_up" ->
                if (prefs.toolBrightnessControl) BrightnessManager.brightnessUp(context)
                else "Brightness control settings mein off hai Boss!"

            lower.startsWith("photo_countdown:") -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    val seconds = arg().toIntOrNull() ?: 3
                    CameraManager.takePhotoWithCountdown(context, seconds, 0) { }
                    "$seconds second baad photo click karungi Boss! Ready ho jao!"
                }
            }

            lower.startsWith("selfie_countdown:") -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    val seconds = arg().toIntOrNull() ?: 3
                    CameraManager.takePhotoWithCountdown(context, seconds, 1) { }
                    "$seconds second baad selfie click karungi Boss! Ready ho jao!"
                }
            }

            lower.startsWith("video_record:") || lower.startsWith("video:") ||
                lower.startsWith("selfie_video:") -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    val duration = arg().toIntOrNull() ?: 15
                    val isSelfie = lower.startsWith("selfie_video:")
                    CameraManager.recordVideo(context, duration, if (isSelfie) 1 else 0) { _, _ -> }
                    if (isSelfie) "$duration second ki selfie video record kar rahi hoon Boss!"
                    else "$duration second ki video record kar rahi hoon Boss!"
                }
            }

            lower.startsWith("video_countdown:") ||
                lower.startsWith("selfie_video_countdown:") -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    val parts = toolName.substringAfter(":", "").split(":")
                    val countdown = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                    val duration = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 15
                    val isSelfie = lower.startsWith("selfie_video_countdown:")
                    CameraManager.recordVideoWithCountdown(
                        context, countdown, duration, if (isSelfie) 1 else 0
                    ) { _, _ -> }
                    if (isSelfie)
                        "$countdown second baad $duration second ki selfie video record karungi Boss!"
                    else
                        "$countdown second baad $duration second ki video record karungi Boss!"
                }
            }

            lower == "record_video_manual" || lower == "video_manual" -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    CameraManager.recordVideoManual(context, 0) { _, _ -> }
                    "Video recording shuru kar rahi hoon Boss! Rokne ke liye tap karo."
                }
            }

            lower == "record_selfie_video_manual" || lower == "selfie_video_manual" -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    CameraManager.recordVideoManual(context, 1) { _, _ -> }
                    "Selfie video recording shuru kar rahi hoon Boss! Rokne ke liye tap karo."
                }
            }

            lower == "brightness_down" ->
                if (prefs.toolBrightnessControl) BrightnessManager.brightnessDown(context)
                else "Brightness control settings mein off hai Boss!"

            lower == "brightness_max" || lower == "full_brightness" ->
                if (prefs.toolBrightnessControl) BrightnessManager.setMaxBrightness(context)
                else "Brightness control settings mein off hai Boss!"

            lower == "brightness_min" || lower == "brightness_low" ->
                if (prefs.toolBrightnessControl) BrightnessManager.setMinBrightness(context)
                else "Brightness control settings mein off hai Boss!"

            lower == "brightness_auto" || lower == "auto_brightness" ->
                if (prefs.toolBrightnessControl) BrightnessManager.setAutoMode(context)
                else "Brightness control settings mein off hai Boss!"

            lower == "get_brightness" || lower == "brightness_kitni" ->
                if (prefs.toolBrightnessControl) BrightnessManager.getBrightnessStatus(context)
                else "Brightness control settings mein off hai Boss!"

            lower.startsWith("brightness_set:") ->
                if (prefs.toolBrightnessControl)
                    BrightnessManager.setBrightness(context, arg().toIntOrNull() ?: 50)
                else "Brightness control settings mein off hai Boss!"

            lower.startsWith("reminder:") -> {
                if (!prefs.toolReminders) {
                    "Reminders settings mein off hai Boss!"
                } else {
                    val payload = toolName.substringAfter("reminder:", "")
                    val colon = payload.indexOf(":")
                    if (colon == -1) {
                        "Boss, format yeh hona chahiye: reminder:time:message"
                    } else {
                        val time = payload.substring(0, colon).trim()
                        var message = payload.substring(colon + 1).trim()
                        if (message.isBlank()) {
                            message = "Reminder Boss!"
                        }
                        ReminderManager.setReminder(context, time, message)
                    }
                }
            }

            lower == "phone_off" || lower == "screen_off" || lower == "lock_screen" ->
                lockOrPowerOff(context)

            lower == "power_menu" || lower == "power_dialog" -> {
                UltronAccessibilityService.getInstance()?.performGlobalAction(6)
                "Power menu khol diya Boss!"
            }

            lower == "spotify_play" || lower.startsWith("spotify_play:") -> {
                if (!prefs.toolSpotify) {
                    "Spotify control settings mein off hai Boss!"
                } else {
                    val song = if (lower.contains(":")) arg() else ""
                    if (song.isNotBlank()) {
                        SpotifyManager.playSong(context, song)
                        "Spotify pe '$song' play kar diya Boss!"
                    } else {
                        SpotifyManager.resumeCurrentSong(context)
                        "Spotify resume kar diya Boss!"
                    }
                }
            }

            lower == "spotify_pause" || lower == "pause_music" || lower == "music_pause" -> {
                if (!prefs.toolSpotify) {
                    "Spotify control settings mein off hai Boss!"
                } else {
                    SpotifyManager.pauseCurrentSong(context)
                    "Music pause kar diya Boss!"
                }
            }

            lower == "spotify_next" || lower == "next_song" || lower == "skip_song" -> {
                if (!prefs.toolSpotify) {
                    "Spotify control settings mein off hai Boss!"
                } else {
                    SpotifyManager.nextSong(context)
                    "Next song play kar diya Boss!"
                }
            }

            lower == "spotify_previous" || lower == "previous_song" || lower == "last_song" -> {
                if (!prefs.toolSpotify) {
                    "Spotify control settings mein off hai Boss!"
                } else {
                    SpotifyManager.previousSong(context)
                    "Previous song play kar diya Boss!"
                }
            }

            lower == "spotify_toggle" || lower == "play_pause" || lower == "toggle_music" -> {
                if (!prefs.toolSpotify) {
                    "Spotify control settings mein off hai Boss!"
                } else {
                    SpotifyManager.playPauseToggle(context)
                    "Music toggle kar diya Boss!"
                }
            }

            lower == "wifi_on" || lower == "wifi_toggle" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleWifi(context)
                    "WiFi on kar diya Boss!"
                }
            }

            lower == "wifi_off" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleWifi(context)
                    "WiFi off kar diya Boss!"
                }
            }

            lower == "data_on" || lower == "mobile_data_on" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleMobileData(context)
                    "Mobile data on kar diya Boss!"
                }
            }

            lower == "data_off" || lower == "mobile_data_off" || lower == "data_toggle" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleMobileData(context)
                    "Mobile data off kar diya Boss!"
                }
            }

            lower == "bluetooth_toggle" || lower == "bluetooth" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleBluetooth(context)
                    "Bluetooth toggle kar diya Boss!"
                }
            }

            lower == "hotspot_on" || lower == "hotspot_toggle" || lower == "hotspot" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleHotspot(context)
                    "Hotspot on kar diya Boss!"
                }
            }

            lower == "hotspot_off" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleHotspot(context)
                    "Hotspot off kar diya Boss!"
                }
            }

            lower == "airplane_on" || lower == "flight_mode_on" || lower == "airplane_toggle" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleAirplaneMode(context)
                    "Airplane mode on kar diya Boss!"
                }
            }

            lower == "airplane_off" || lower == "flight_mode_off" -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggleAirplaneMode(context)
                    "Airplane mode off kar diya Boss!"
                }
            }

            lower.startsWith("toggle_network:") || lower.startsWith("net:") -> {
                if (!prefs.toolNetworkToggles) {
                    "Network toggles settings mein off hai Boss!"
                } else {
                    NetworkToggleManager.toggle(context, arg())
                    "Network toggle kar diya Boss!"
                }
            }

            lower == "get_weather" || lower == "weather" || lower == "mausam" ||
                lower == "mausam_batao" || lower == "weather_here" || lower == "yahan_mausam" ||
                lower == "current_weather" -> {
                if (!prefs.toolWeather) {
                    "Weather info settings mein off hai Boss!"
                } else {
                    WeatherManager.getCurrentLocationWeather(context) { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "Weather dekh rahi hoon Boss!"
                }
            }

            lower == "analyze_scene" || lower.startsWith("analyze_scene:") ||
                lower == "selfie_analyze_scene" || lower.startsWith("selfie_analyze_scene:") ||
                lower == "describe_scene" || lower.startsWith("describe_scene:") ||
                lower == "what_is_this" || lower.startsWith("what_is_this:") -> {
                if (!prefs.toolCamera) {
                    "Camera control settings mein off hai Boss!"
                } else {
                    val isSelfie = lower == "selfie_analyze_scene" ||
                        lower.startsWith("selfie_analyze_scene:")
                    val question = if (toolName.contains(":")) arg() else ""
                    CameraVisionManager.analyzeScene(
                        context, question, isSelfie,
                        onResult = { result ->
                            WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                        },
                        onError = { err ->
                            WakeWordService.getActiveInstance()
                                ?.injectReminderMessage("Camera analysis failed: $err")
                        },
                    )
                    if (isSelfie) {
                        if (question.isNotEmpty()) "Selfie camera se dekh kar bata rahi hoon Boss!"
                        else "Selfie camera se dekh rahi hoon Boss!"
                    } else {
                        if (question.isNotEmpty()) "Camera se dekh kar bata rahi hoon Boss!"
                        else "Camera se dekh rahi hoon Boss!"
                    }
                }
            }

            lower == "get_location" || lower == "location" || lower == "meri_location" ||
                lower == "location_batao" || lower == "main_kahan_hun" -> {
                if (!prefs.toolLocation) {
                    "Location access settings mein off hai Boss!"
                } else {
                    WeatherManager.getFreshLocation(context) { location ->
                        reportLocation(context, location)
                    }
                    "Location dekh rahi hoon Boss!"
                }
            }

            lower == "emergency_sos" || lower.startsWith("emergency_sos:") ->
                EmergencySOSManager.triggerSOS(
                    context,
                    if (lower.contains(":")) toolName.substringAfter("emergency_sos:", "").trim()
                    else "",
                )

            lower == "touch_guard_on" || lower == "touch_guard:on" || lower == "anti_theft_on" ->
                AntiTheftManager.enable(context)

            lower == "touch_guard_off" || lower == "touch_guard:off" || lower == "anti_theft_off" ->
                AntiTheftManager.disable()

            lower == "open_selfie" || lower == "show_intruder" || lower == "photo_dikhaye" -> {
                val file = File(context.cacheDir, "intruder_selfie.jpg")
                if (file.exists()) {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.provider", file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        "Intruder ki photo khol rahi hoon Boss!"
                    } catch (e: Exception) {
                        "Photo kholne mein error aayi Boss: ${e.message}"
                    }
                } else {
                    "Abhi tak koi photo click nahi hui hai Boss!"
                }
            }

            lower.startsWith("sms:") || lower.startsWith("sms_select:") -> {
                if (!prefs.toolSMS) {
                    "SMS feature settings mein off hai Boss!"
                } else if (lower.startsWith("sms:")) {
                    val payload = toolName.substringAfter("sms:", "")
                    val colon = payload.indexOf(":")
                    if (colon == -1) {
                        "Contact aur message dono batao Boss!"
                    } else {
                        val contactName = payload.substring(0, colon).trim()
                        val message = payload.substring(colon + 1).trim()
                        handleSms(context, contactName, message)
                    }
                } else {
                    selectSmsContact(context, toolName.substringAfter("sms_select:", "").trim())
                }
            }

            lower.startsWith("maps_nav:") || lower.startsWith("maps_show:") ||
                lower.startsWith("maps_search:") || lower.startsWith("maps_nearby:") ||
                lower.startsWith("nearby:") -> {
                if (!prefs.toolMaps) {
                    "Maps feature settings mein off hai Boss!"
                } else when {
                    lower.startsWith("maps_nav:") ->
                        MapsManager.navigateTo(context, toolName.substringAfter("maps_nav:", "").trim())
                    lower.startsWith("maps_show:") ->
                        MapsManager.showOnMap(context, toolName.substringAfter("maps_show:", "").trim())
                    lower.startsWith("maps_search:") ->
                        MapsManager.searchPlaces(context, toolName.substringAfter("maps_search:", "").trim())
                    else -> {
                        val query = if (lower.startsWith("nearby:"))
                            toolName.substringAfter("nearby:", "").trim()
                        else
                            toolName.substringAfter("maps_nearby:", "").trim()
                        MapsManager.searchNearby(context, query)
                    }
                }
            }

            lower.startsWith("whatsapp:") || lower.startsWith("whatsapp_select:") ||
                lower.startsWith("whatsapp_call:") || lower.startsWith("whatsapp_video:") ||
                lower.startsWith("whatsapp_call_select:") ||
                lower.startsWith("whatsapp_video_select:") -> {
                if (!prefs.toolWhatsApp) {
                    "WhatsApp feature settings mein off hai Boss!"
                } else when {
                    lower.startsWith("whatsapp:") -> {
                        val payload = toolName.substringAfter("whatsapp:", "")
                        val colon = payload.indexOf(":")
                        if (colon == -1) {
                            "Contact aur message dono batao Boss!"
                        } else {
                            val contactName = payload.substring(0, colon).trim()
                            val message = payload.substring(colon + 1).trim()
                            handleWhatsApp(context, contactName, message)
                        }
                    }
                    lower.startsWith("whatsapp_select:") ->
                        selectWhatsAppContact(context, toolName.substringAfter("whatsapp_select:", "").trim())
                    lower.startsWith("whatsapp_call:") ->
                        handleWhatsAppCall(context, toolName.substringAfter("whatsapp_call:", "").trim(), false)
                    lower.startsWith("whatsapp_video:") ->
                        handleWhatsAppCall(context, toolName.substringAfter("whatsapp_video:", "").trim(), true)
                    lower.startsWith("whatsapp_call_select:") ->
                        selectWhatsAppCallContact(context, toolName.substringAfter("whatsapp_call_select:", "").trim(), false)
                    else ->
                        selectWhatsAppCallContact(context, toolName.substringAfter("whatsapp_video_select:", "").trim(), true)
                }
            }

            lower.startsWith("open_app:") || lower.startsWith("open_app ") -> {
                if (!prefs.toolAppLauncher) {
                    "App launcher settings mein off hai Boss!"
                } else {
                    val appName = if (lower.startsWith("open_app:"))
                        toolName.substringAfter(":", "").trim()
                    else
                        lower.substringAfter("open_app ", "").trim()
                    launchApp(context, appName)
                }
            }

            lower.startsWith("call:") || lower.startsWith("call ") ||
                lower.startsWith("select_contact:") -> {
                if (!prefs.toolPhoneCalls) {
                    "Phone calls settings mein off hai Boss!"
                } else when {
                    lower.startsWith("call:") ->
                        handleCall(context, toolName.substringAfter(":", "").trim())
                    lower.startsWith("call ") ->
                        handleCall(context, lower.substringAfter("call ", "").trim())
                    else ->
                        selectPendingContact(context, toolName.substringAfter(":", "").trim())
                }
            }

            lower == "youtube_pause" || lower == "yt_pause" || lower == "youtube:pause" ||
                lower == "yt:pause" -> {
                if (!prefs.toolYouTube) {
                    "YouTube feature settings mein off hai Boss!"
                } else {
                    YouTubeManager.playOrPauseVideo(context, "pause") { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "YouTube pause kar rahi hoon Boss!"
                }
            }

            lower == "youtube_play" || lower == "youtube_resume" || lower == "yt_play" ||
                lower == "yt_resume" || lower == "youtube:play" || lower == "youtube:resume" ||
                lower == "yt:play" || lower == "yt:resume" -> {
                if (!prefs.toolYouTube) {
                    "YouTube feature settings mein off hai Boss!"
                } else {
                    YouTubeManager.playOrPauseVideo(context, "play") { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "YouTube play kar rahi hoon Boss!"
                }
            }

            lower == "youtube_like" || lower == "yt_like" || lower == "youtube:like" ||
                lower == "yt:like" || lower == "like_video" -> {
                if (!prefs.toolYouTube) {
                    "YouTube feature settings mein off hai Boss!"
                } else {
                    YouTubeManager.likeVideo { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "Video like kar rahi hoon Boss!"
                }
            }

            lower == "youtube_subscribe" || lower == "yt_subscribe" ||
                lower == "youtube:subscribe" || lower == "yt:subscribe" ||
                lower == "subscribe_channel" -> {
                if (!prefs.toolYouTube) {
                    "YouTube feature settings mein off hai Boss!"
                } else {
                    YouTubeManager.subscribeChannel { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "Channel subscribe kar rahi hoon Boss!"
                }
            }

            lower == "youtube_cc" || lower == "yt_cc" || lower == "youtube:cc" ||
                lower == "yt:cc" || lower == "youtube_captions" || lower == "yt_captions" -> {
                if (!prefs.toolYouTube) {
                    "YouTube feature settings mein off hai Boss!"
                } else {
                    YouTubeManager.toggleCaptions { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "Captions toggle kar rahi hoon Boss!"
                }
            }

            lower.startsWith("youtube:") || lower.startsWith("yt:") -> {
                if (!prefs.toolYouTube) {
                    "YouTube feature settings mein off hai Boss!"
                } else {
                    val query = if (lower.startsWith("yt:"))
                        toolName.substringAfter("yt:", "").trim()
                    else
                        toolName.substringAfter("youtube:", "").trim()
                    YouTubeManager.searchAndPlay(context, query) { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "YouTube pe dhundh rahi hoon Boss!"
                }
            }

            lower == "get_news" || lower == "news" || lower == "news_batao" ||
                lower == "khabar" || lower == "khabar_batao" || lower == "aaj_ki_khabar" ||
                lower == "top_news" || lower == "headlines" ||
                lower.startsWith("news:") || lower.startsWith("khabar:") -> {
                if (!prefs.toolNews) {
                    "News feature settings mein off hai Boss!"
                } else {
                    var category = when {
                        lower.startsWith("news:") -> toolName.substringAfter("news:", "").trim()
                        lower.startsWith("khabar:") -> toolName.substringAfter("khabar:", "").trim()
                        else -> "india"
                    }
                    if (category.isBlank()) {
                        category = "india"
                    }
                    NewsManager.getNews(category, 5, true) { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    }
                    "News fetch kar rahi hoon Boss!"
                }
            }

            lower.startsWith("search:") || lower.startsWith("google:") ||
                lower.startsWith("google_search:") || lower.startsWith("khojo:") ||
                lower == "search" || lower == "google" -> {
                if (!prefs.toolSearch) {
                    "Search feature settings mein off hai Boss!"
                } else {
                    val query = when {
                        lower.startsWith("search:") -> toolName.substringAfter("search:", "").trim()
                        lower.startsWith("google:") -> toolName.substringAfter("google:", "").trim()
                        lower.startsWith("google_search:") ->
                            toolName.substringAfter("google_search:", "").trim()
                        lower.startsWith("khojo:") -> toolName.substringAfter("khojo:", "").trim()
                        // RECOVERY NOTE: bare "search"/"google" fall through with query
                        // left as the auto-typing default slot in the decompiled bytecode;
                        // it is blank, so the isBlank() branch below fires ("Kya search...").
                        else -> ""
                    }
                    when {
                        query.isBlank() -> "Kya search karna hai batao Boss!"
                        GoogleSearchManager.search(context, query) ->
                            "Google pe '$query' search kar diya Boss!"
                        else -> "Browser nahi khul raha Boss! Internet check karo."
                    }
                }
            }

            lower == "scroll_down" || lower == "scroll" || lower == "neeche" ||
                lower == "neeche_jao" || lower == "next" || lower == "age" -> {
                ScrollManager.scrollDown(context) { success ->
                    if (!success) injectOnMain("Scroll nahi ho paya Boss!")
                }
                "Neeche scroll kar diya Boss!"
            }

            lower == "scroll_up" || lower == "upar" || lower == "upar_jao" ||
                lower == "wapas" || lower == "peeche" -> {
                ScrollManager.scrollUp(context) { success ->
                    if (!success) injectOnMain("Scroll nahi ho paya Boss!")
                }
                "Upar scroll kar diya Boss!"
            }

            lower.startsWith("scroll_down:") -> {
                val times = arg().toIntOrNull() ?: 1
                ScrollManager.scrollDownTimes(context, times) { success ->
                    if (!success) injectOnMain("Scroll nahi ho paya Boss!")
                }
                "Neeche $times baar scroll kar rahi hoon Boss!"
            }

            lower.startsWith("scroll_up:") -> {
                val times = arg().toIntOrNull() ?: 1
                ScrollManager.scrollUpTimes(context, times) { success ->
                    if (!success) injectOnMain("Scroll nahi ho paya Boss!")
                }
                "Upar $times baar scroll kar rahi hoon Boss!"
            }

            lower == "scroll_bottom" || lower == "end_tak" || lower == "bilkul_neeche" ||
                lower == "page_end" -> {
                ScrollManager.scrollToBottom(context) { success ->
                    injectOnMain(
                        if (success) "Page ke end tak aa gaye Boss!"
                        else "Scroll nahi ho paya Boss!"
                    )
                }
                "Page ke end tak ja rahi hoon Boss!"
            }

            lower == "scroll_top" || lower == "top_tak" || lower == "bilkul_upar" ||
                lower == "page_start" -> {
                ScrollManager.scrollToTop(context) { success ->
                    injectOnMain(
                        if (success) "Page ke top tak aa gaye Boss!"
                        else "Scroll nahi ho paya Boss!"
                    )
                }
                "Page ke top tak ja rahi hoon Boss!"
            }

            lower == "web_search" || lower == "search_web" ||
                lower.startsWith("web_search:") || lower.startsWith("search_web:") -> {
                if (!prefs.toolWebSearch) {
                    "Web search settings mein off hai Boss!"
                } else {
                    val query = if (toolName.contains(":")) arg() else ""
                    if (query.isBlank()) {
                        "Kya search karun Boss?"
                    } else {
                        WebAnswerClient.search(
                            prefs.googleApiKey,
                            query,
                            onResult = { answer ->
                                WakeWordService.getActiveInstance()?.injectReminderMessage(answer)
                            },
                            onError = { err ->
                                WakeWordService.getActiveInstance()?.injectReminderMessage(err)
                            },
                        )
                        "Web pe '$query' dekh rahi hoon Boss!"
                    }
                }
            }

            lower.startsWith("save_memory:") -> {
                val fact = toolName.substringAfter("save_memory:", "").trim()
                if (fact.isNotEmpty()) {
                    prefs.addMemory(fact)
                    "Yaad rakh liya Boss: $fact"
                } else {
                    "Kya yaad rakhna hai Boss?"
                }
            }

            lower == "clear_memories" || lower == "clear_memory" || lower == "forget_all" -> {
                prefs.clearMemories()
                "Sab yaaddaasht clear kar di Boss!"
            }

            else -> {
                // Fallback: try to launch an app named after the raw tool string.
                if (!prefs.toolAppLauncher) {
                    "Samajh nahi aaya Boss: $lower"
                } else {
                    val launch = AppLaunchManager.launchApp(context, lower)
                    if (launch is AppLaunchManager.LaunchResult.Success)
                        "${launch.appName} khol diya Boss!"
                    else "Samajh nahi aaya Boss: $lower"
                }
            }
        }

        return translateResponse(result, userLang)
    }

    // --- Async result injection helpers (folded from the ExternalSyntheticLambda callbacks) ---

    /** Post [message] to the main thread and inject it into the active session. */
    private fun injectOnMain(message: String) {
        Handler(Looper.getMainLooper()).post {
            WakeWordService.getActiveInstance()?.injectReminderMessage(message)
        }
    }

    /**
     * Saves [bitmap] to a timestamped JPEG in the cache dir, records it as the last
     * captured photo, and runs Gemini vision on it. Folded from
     * `ToolHandler$handle$hinglishResponse$4$1`.
     */
    private fun analyzeScreenshot(
        context: Context,
        prefs: AppPreferences,
        bitmap: Bitmap,
        question: String,
    ) {
        try {
            val file = File(
                context.cacheDir,
                "NOVA_SCREENSHOT_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg",
            )
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            prefs.lastCapturedPhotoUri = Uri.fromFile(file).toString()
            CameraVisionManager.analyzeBitmap(
                context, bitmap, question, true,
                onResult = { result ->
                    WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                },
                onError = { err ->
                    WakeWordService.getActiveInstance()
                        ?.injectReminderMessage("Screen analysis failed: $err")
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screenshot: ${e.message}")
            WakeWordService.getActiveInstance()
                ?.injectReminderMessage("Screen analysis failed: ${e.message}")
        }
    }

    /**
     * Loads the previously captured photo from [uriStr] (file:// or content://) and
     * runs Gemini vision on it. Folded from `ToolHandler$handle$hinglishResponse$5`.
     */
    private fun analyzeLastPhoto(context: Context, uriStr: String, question: String) {
        try {
            val uri = Uri.parse(uriStr)
            val bitmap: Bitmap? = if (uri.scheme == "file") {
                val path = uri.path ?: ""
                val file = File(path)
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
            if (bitmap != null) {
                CameraVisionManager.analyzeBitmap(
                    context, bitmap, question, false,
                    onResult = { result ->
                        WakeWordService.getActiveInstance()?.injectReminderMessage(result)
                    },
                    onError = { err ->
                        WakeWordService.getActiveInstance()
                            ?.injectReminderMessage("Camera analysis failed: $err")
                    },
                )
            } else {
                WakeWordService.getActiveInstance()
                    ?.injectReminderMessage("Pichli photo load nahi ho payi Boss!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading last photo: ${e.message}")
            WakeWordService.getActiveInstance()
                ?.injectReminderMessage("Pichli photo load nahi ho payi Boss!")
        }
    }

    /**
     * Reverse-geocodes [location] into a Hinglish "you are at ..." line and injects it.
     * Folded from the `handle$lambda$29` callback.
     */
    private fun reportLocation(context: Context, location: Location?) {
        val message: String = if (location == null) {
            "Location nahi mili Boss! GPS on karke Google Maps kholo."
        } else {
            val fallback = "Aap yahan hain Boss: ${location.latitude}, ${location.longitude}"
            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(context, Locale.getDefault())
                    .getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val subLocality = address.subLocality ?: ""
                    val locality = address.locality ?: address.adminArea ?: ""
                    val adminArea = address.adminArea ?: ""
                    // RECOVERY NOTE: jadx flagged this block ("Removed duplicated region")
                    // and emitted an unconditional fallback assignment after the if/else,
                    // which would clobber the geocoded string. Reconstructed the original
                    // intent: use the richest available locality, else fall back to lat/lng.
                    when {
                        subLocality.isNotEmpty() && locality.isNotEmpty() ->
                            "Aap abhi $subLocality, $locality, $adminArea mein hain Boss!"
                        locality.isNotEmpty() ->
                            "Aap abhi $locality, $adminArea mein hain Boss!"
                        else -> fallback
                    }
                } else {
                    fallback
                }
            } catch (e: Exception) {
                fallback
            }
        }
        injectOnMain(message)
    }

    /**
     * Translates a Hinglish response into the user's chosen language when it is neither
     * Hinglish nor Hindi. Folded from `ToolHandler$translateResponse$1`.
     */
    private fun translateResponse(response: String, userLang: String): String {
        val langLower = userLang.lowercase(Locale.ROOT)
        if (langLower == "hinglish" || langLower == "hindi") {
            return response
        }
        return try {
            runBlocking {
                TranslationManager.translateToUserLanguage(response, userLang)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Translation failed: ${e.message}")
            response
        }
    }

    // --- Comms / apps dispatch helpers ---

    private fun handleSms(context: Context, contactName: String, message: String): String =
        when (val result = UltronSmsManager.sendSms(context, contactName, message)) {
            is UltronSmsManager.SmsResult.Success -> "${result.name} ko SMS bhej diya Boss!"
            is UltronSmsManager.SmsResult.MultipleContacts -> {
                pendingWhatsAppContacts = result.contacts
                pendingWhatsAppMessage = "__SMS__:$message"
                "[MULTI_SMS_CONTACT] ${ContactManager.formatContactsForSpeech(result.contacts)}"
            }
            is UltronSmsManager.SmsResult.NotFound ->
                "'$contactName' contacts mein nahi mila Boss!"
            is UltronSmsManager.SmsResult.Error -> "SMS nahi bheja ja saka Boss!"
        }

    private fun selectSmsContact(context: Context, selectedName: String): String {
        val list = pendingWhatsAppContacts ?: return "Koi pending SMS contact nahi hai Boss!"
        val message = pendingWhatsAppMessage?.removePrefix("__SMS__:")
            ?: return "Message missing hai Boss!"
        val contact = resolvePendingContact(list, selectedName)
            ?: return "'$selectedName' matching contact nahi mila Boss!"
        pendingWhatsAppContacts = null
        pendingWhatsAppMessage = null
        UltronSmsManager.sendSmsToNumber(contact.number, message)
        return "${contact.name} ko SMS bhej diya Boss!"
    }

    private fun handleWhatsAppCall(
        context: Context,
        contactName: String,
        isVideo: Boolean,
    ): String {
        val result = if (isVideo) WhatsAppManager.videoCall(context, contactName)
        else WhatsAppManager.voiceCall(context, contactName)
        val callType = if (isVideo) "video call" else "voice call"
        return when (result) {
            is WhatsAppManager.WhatsAppResult.Success ->
                "${result.name} ko WhatsApp $callType kar diya Boss!"
            is WhatsAppManager.WhatsAppResult.MultipleContacts -> {
                pendingWhatsAppContacts = result.contacts
                pendingWhatsAppMessage = if (isVideo) "__VIDEO__" else "__CALL__"
                val speech = ContactManager.formatContactsForSpeech(result.contacts)
                if (isVideo) "[MULTI_WHATSAPP_VIDEO] $speech"
                else "[MULTI_WHATSAPP_CALL] $speech"
            }
            is WhatsAppManager.WhatsAppResult.NotFound ->
                "'$contactName' contacts mein nahi mila Boss!"
            is WhatsAppManager.WhatsAppResult.Error -> "${result.reason} Boss!"
        }
    }

    private fun selectWhatsAppCallContact(
        context: Context,
        selectedName: String,
        isVideo: Boolean,
    ): String {
        val list = pendingWhatsAppContacts ?: return "Koi pending contact nahi hai Boss!"
        val contact = resolvePendingContact(list, selectedName)
            ?: return "'$selectedName' matching contact nahi mila Boss!"
        pendingWhatsAppContacts = null
        pendingWhatsAppMessage = null
        if (isVideo) WhatsAppManager.videoCallToNumber(context, contact.number)
        else WhatsAppManager.voiceCallToNumber(context, contact.number)
        return "${contact.name} ko WhatsApp ${if (isVideo) "video call" else "voice call"} kar diya Boss!"
    }

    private fun handleWhatsApp(context: Context, contactName: String, message: String): String =
        when (val result = WhatsAppManager.sendMessage(context, contactName, message)) {
            is WhatsAppManager.WhatsAppResult.Success ->
                "${result.name} ko WhatsApp message bhej diya Boss!"
            is WhatsAppManager.WhatsAppResult.MultipleContacts -> {
                pendingWhatsAppContacts = result.contacts
                pendingWhatsAppMessage = message
                "[MULTI_WHATSAPP_CONTACT] ${ContactManager.formatContactsForSpeech(result.contacts)}"
            }
            is WhatsAppManager.WhatsAppResult.NotFound ->
                "'$contactName' contacts mein nahi mila Boss!"
            is WhatsAppManager.WhatsAppResult.Error -> "${result.reason} Boss!"
        }

    private fun selectWhatsAppContact(context: Context, selectedName: String): String {
        val list = pendingWhatsAppContacts
            ?: return "Koi pending WhatsApp contact nahi hai Boss!"
        val message = pendingWhatsAppMessage ?: return "Message missing hai Boss!"
        val contact = resolvePendingContact(list, selectedName)
            ?: return "'$selectedName' matching contact nahi mila Boss!"
        pendingWhatsAppContacts = null
        pendingWhatsAppMessage = null
        WhatsAppManager.sendMessageToNumber(context, contact.name, contact.number, message)
        return "${contact.name} ko WhatsApp message bhej diya Boss!"
    }

    private fun handleCall(context: Context, contactName: String): String {
        val contacts = ContactManager.searchContacts(context, contactName)
        if (contacts.isEmpty()) {
            return "'$contactName' contacts mein nahi mila Boss!"
        }
        if (contacts.size == 1) {
            val contact = contacts[0]
            if (contact.isFavorite || contact.bestFuzzyScore >= 0.8) {
                pendingCallContacts = null
                ContactManager.makeCall(context, contact.number)
                return "${contact.name} ko call kar diya Boss!"
            } else {
                pendingCallContacts = contacts
                val spelling = contact.name.map { it.uppercaseChar() }.joinToString("-")
                return "Boss, exact name nahi mila. Kya aap '${contact.name}' (spelling: $spelling) ko call karna chahte hain?"
            }
        }
        pendingCallContacts = contacts
        return "[MULTI_CONTACT] ${ContactManager.formatContactsForSpeech(contacts)}"
    }

    private fun selectPendingContact(context: Context, selectedName: String): String {
        val list = pendingCallContacts ?: return "Koi pending contact nahi hai Boss!"
        val contact = resolvePendingContact(list, selectedName)
            ?: return "'$selectedName' matching contact nahi mila Boss!"
        pendingCallContacts = null
        ContactManager.makeCall(context, contact.number)
        return "${contact.name} ko call kar diya Boss!"
    }

    private fun launchApp(context: Context, appName: String): String =
        when (val result = AppLaunchManager.launchApp(context, appName)) {
            is AppLaunchManager.LaunchResult.Success -> "${result.appName} khol diya Boss!"
            is AppLaunchManager.LaunchResult.NotFound -> "'$appName' phone mein nahi mila Boss!"
            is AppLaunchManager.LaunchResult.NotInstalled -> "'$appName' install nahi hai Boss!"
            is AppLaunchManager.LaunchResult.Error -> "'$appName' nahi khula Boss!"
        }

    private fun goToHome(context: Context) {
        val service = UltronAccessibilityService.getInstance()
        if (service != null) {
            service.performGlobalAction(2)
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            // RECOVERY NOTE: original addFlags(270532608) == 0x10200000 ==
            // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        context.startActivity(intent)
    }

    private fun lockOrPowerOff(context: Context): String {
        val dpm = devicePolicyManager
        val component = adminComponent
        if (dpm != null && component != null && dpm.isAdminActive(component)) {
            dpm.lockNow()
            return "Phone lock kar diya Boss!"
        }
        val service = UltronAccessibilityService.getInstance()
        if (service != null) {
            service.performGlobalAction(6)
            return "Power menu khol diya Boss!"
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Phone lock karne ke liye permission chahiye Boss!",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Permission chahiye Boss! Ek baar de do."
    }
}
