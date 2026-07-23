package com.flexfly.ultron

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.flexfly.ultron.ai.ToolHandler
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.data.DatabaseHelper
import com.flexfly.ultron.model.Message
import com.flexfly.ultron.service.UltronNotificationListener
import com.flexfly.ultron.ui.AppRoot
import com.flexfly.ultron.ui.theme.UltronTheme

/**
 * Host activity for the Ultron assistant.
 *
 * Recovered from `MainActivity.kt`. Drives a 14-step runtime permission chain
 * ([nextPermStep]) that gates starting the foreground service, plus the
 * Compose UI root ([AppRoot]). The permission SEQUENCE and every permission string
 * are preserved exactly from the original.
 */
class MainActivity : ComponentActivity() {

    private lateinit var db: DatabaseHelper
    lateinit var prefs: AppPreferences

    private val messagesState = mutableStateListOf<Message>()
    private val isRunningState = mutableStateOf(false)

    private val permPrefs: SharedPreferences by lazy {
        getSharedPreferences("max_permissions", Context.MODE_PRIVATE)
    }

    private var permStep = 0

    // --- Permission launchers (one nextPermStep() advance per result) ---

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextPermStep() }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextPermStep() }

    private val callPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextPermStep() }

    private val contactPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nextPermStep() }

    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextPermStep() }

    private val writeSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextPermStep() }

    private val exactAlarmLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextPermStep() }

    private val smsPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextPermStep() }

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nextPermStep() }

    private val phoneStatePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nextPermStep() }

    private val bluetoothPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nextPermStep() }

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextPermStep() }

    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nextPermStep() }

    private fun checkNotificationListenerPermission() {
        val componentName = android.content.ComponentName(this, UltronNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (enabled != null && enabled.contains(componentName.flattenToString())) {
            return
        }
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        // RECOVERY NOTE: addFlags(268435456) == Intent.FLAG_ACTIVITY_NEW_TASK.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun requestBatteryOptimizationExemption() {
        // RECOVERY NOTE: getSystemService("power") == Context.POWER_SERVICE.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName) ||
            permPrefs.getBoolean(KEY_BATTERY_ASKED, false)
        ) {
            return
        }
        runCatching {
            val intent = Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            permPrefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
        }
    }

    private fun startPermissionChain() {
        permStep = 0
        nextPermStep()
    }

    private fun nextPermStep() {
        val i = permStep
        permStep = i + 1
        when (i) {
            0 -> {
                if (ContextCompat.checkSelfPermission(this, "android.permission.RECORD_AUDIO") != 0) {
                    audioPermLauncher.launch("android.permission.RECORD_AUDIO")
                } else nextPermStep()
            }

            1 -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != 0
                ) {
                    notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
                } else nextPermStep()
            }

            2 -> {
                if (ContextCompat.checkSelfPermission(this, "android.permission.CALL_PHONE") != 0) {
                    callPermLauncher.launch("android.permission.CALL_PHONE")
                } else nextPermStep()
            }

            3 -> {
                val needed = listOf(
                    "android.permission.READ_CONTACTS",
                    "android.permission.WRITE_CONTACTS",
                ).filter { ContextCompat.checkSelfPermission(this, it) != 0 }
                if (needed.isNotEmpty()) {
                    contactPermLauncher.launch(needed.toTypedArray())
                } else nextPermStep()
            }

            4 -> {
                if (!Settings.canDrawOverlays(this)) {
                    overlayPermLauncher.launch(
                        Intent(
                            "android.settings.action.MANAGE_OVERLAY_PERMISSION",
                            Uri.parse("package:$packageName"),
                        )
                    )
                } else nextPermStep()
            }

            5 -> {
                if (!Settings.System.canWrite(this)) {
                    writeSettingsLauncher.launch(
                        Intent(
                            "android.settings.action.MANAGE_WRITE_SETTINGS",
                            Uri.parse("package:$packageName"),
                        )
                    )
                } else nextPermStep()
            }

            6 -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    // RECOVERY NOTE: getSystemService("alarm") == Context.ALARM_SERVICE
                    // (decompiler substituted NotificationCompat.CATEGORY_ALARM, whose value is "alarm").
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    if (!alarmManager.canScheduleExactAlarms()) {
                        val intent = Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                        intent.data = Uri.parse("package:$packageName")
                        exactAlarmLauncher.launch(intent)
                    } else nextPermStep()
                } else nextPermStep()
            }

            7 -> {
                if (ContextCompat.checkSelfPermission(this, "android.permission.SEND_SMS") != 0) {
                    smsPermLauncher.launch("android.permission.SEND_SMS")
                } else nextPermStep()
            }

            8 -> {
                val needed = listOf(
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                ).filter { ContextCompat.checkSelfPermission(this, it) != 0 }
                if (needed.isNotEmpty()) {
                    locationPermLauncher.launch(needed.toTypedArray())
                } else nextPermStep()
            }

            9 -> nextPermStep()

            10 -> {
                val needed = buildList {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.READ_PHONE_STATE") != 0) {
                        add("android.permission.READ_PHONE_STATE")
                    }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.ANSWER_PHONE_CALLS") != 0) {
                        add("android.permission.ANSWER_PHONE_CALLS")
                    }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.READ_CALL_LOG") != 0) {
                        add("android.permission.READ_CALL_LOG")
                    }
                }
                if (needed.isNotEmpty()) {
                    phoneStatePermLauncher.launch(needed.toTypedArray())
                } else nextPermStep()
            }

            11 -> {
                val needed = buildList {
                    if (Build.VERSION.SDK_INT >= 31 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.BLUETOOTH_CONNECT") != 0
                    ) {
                        add("android.permission.BLUETOOTH_CONNECT")
                    }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.BLUETOOTH_ADMIN") != 0) {
                        add("android.permission.BLUETOOTH_ADMIN")
                    }
                }
                if (needed.isNotEmpty()) {
                    bluetoothPermLauncher.launch(needed.toTypedArray())
                } else nextPermStep()
            }

            12 -> {
                if (ContextCompat.checkSelfPermission(this, "android.permission.CAMERA") != 0) {
                    cameraPermLauncher.launch("android.permission.CAMERA")
                } else nextPermStep()
            }

            13 -> {
                val perms = if (Build.VERSION.SDK_INT >= 33) {
                    listOf(
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO",
                    )
                } else {
                    listOf(
                        "android.permission.WRITE_EXTERNAL_STORAGE",
                        "android.permission.READ_EXTERNAL_STORAGE",
                    )
                }
                val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != 0 }
                if (needed.isNotEmpty()) {
                    storagePermLauncher.launch(needed.toTypedArray())
                } else nextPermStep()
            }

            else -> autoStartServiceIfNeeded()
        }
    }

    private fun autoStartServiceIfNeeded() {
        Log.d("MainActivity", "Auto-starting VoiceService for debugging")
        startWakeWord()
    }

    private fun handleStartStop() {
        if (isRunningState.value) {
            stopSelectedRuntime()
            isRunningState.value = false
            prefs.isServiceEnabled = false
        } else {
            // Access-key verification removed for testing: start directly.
            if (allCorePermsGranted()) {
                startWakeWord()
            } else {
                startPermissionChain()
            }
        }
    }

    private fun allCorePermsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, "android.permission.RECORD_AUDIO") == 0 &&
            Settings.canDrawOverlays(this)

    /**
     * Starts the production voice runtime WakeWordService.
     */
    private fun startWakeWord() {
        com.flexfly.ultron.service.WakeWordService.startService(this)
        isRunningState.value = true
        prefs.isServiceEnabled = true
    }

    private fun stopSelectedRuntime() {
        com.flexfly.ultron.service.WakeWordService.stopService(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ToolHandler.initialize(this)
        db = DatabaseHelper(this)
        prefs = AppPreferences(this)
        messagesState.addAll(db.getRecentMessages(50))

        isRunningState.value = prefs.isServiceEnabled

        setContent {
            UltronTheme {
                AppRoot(
                    messages = messagesState,
                    isRunning = isRunningState.value,
                    prefs = prefs,
                    onStartStop = { handleStartStop() },
                    onClearHistory = {
                        db.clearAll()
                        messagesState.clear()
                    },
                )
            }
        }

        requestBatteryOptimizationExemption()
        checkNotificationListenerPermission()
        startPermissionChain()
    }

    private val liveMessageListener = object : DatabaseHelper.MessageListener {
        override fun onMessageAdded(message: Message) {
            runOnUiThread {
                messagesState.add(message)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DatabaseHelper.registerListener(liveMessageListener)
    }

    override fun onStop() {
        super.onStop()
        DatabaseHelper.unregisterListener(liveMessageListener)
    }

    override fun onResume() {
        super.onResume()
        messagesState.clear()
        messagesState.addAll(db.getRecentMessages(50))
    }

    companion object {
        private const val KEY_BATTERY_ASKED = "battery_asked"
        private const val KEY_DND_ASKED = "dnd_asked"
    }
}
