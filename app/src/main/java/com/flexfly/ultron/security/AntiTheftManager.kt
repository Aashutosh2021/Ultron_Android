package com.flexfly.ultron.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.flexfly.ultron.data.AppPreferences
import com.flexfly.ultron.ai.CameraVisionManager
import com.flexfly.ultron.service.WakeWordService
import kotlin.math.sqrt

object AntiTheftManager : SensorEventListener {

    private const val DEBOUNCE_MS = 3000L
    private const val MOTION_THRESHOLD = 1.2f
    private const val TAG = "AntiTheftManager"

    private var accelerometer: Sensor? = null
    private var applicationContext: Context? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var isAlarmTriggered = false
    private var isRegistered = false
    private var lastTriggerTime = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var sensorManager: SensorManager? = null
    private var isFirstSample = true

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.intent.action.USER_PRESENT") {
                Log.d(TAG, "?? User present (phone unlocked) — disabling touch guard")
                disable()
                WakeWordService.getActiveInstance()
                    ?.injectReminderMessage("Phone unlock ho gaya Boss! Touch guard off kar diya.")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    fun enable(context: Context): String {
        applicationContext = context.applicationContext
        if (isRegistered) {
            return "Touch guard pehle se hi active hai Boss!"
        }
        val sm = context.getSystemService("sensor") as SensorManager
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer = sensor
        if (sensor == null) {
            Log.e(TAG, "Accelerometer not supported on this device")
            return "Phone mein accelerometer support nahi hai Boss!"
        }
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        isRegistered = true
        isAlarmTriggered = false
        isFirstSample = true
        try {
            val powerManager = context.getSystemService("power") as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Ultron:AntiTheftCpuWakeLock"
            )
            wakeLock.acquire()
            cpuWakeLock = wakeLock
            Log.d(TAG, "?? CPU Partial WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire CPU wake lock: ${e.message}")
        }
        try {
            context.registerReceiver(unlockReceiver, IntentFilter("android.intent.action.USER_PRESENT"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register unlock receiver: ${e.message}")
        }
        Log.d(TAG, "??? Touch guard enabled")
        return "Touch guard active kar diya hai Boss! Phone ab safe hai."
    }

    fun disable(): String {
        if (!isRegistered) {
            stopAlarm()
            return "Touch guard active nahi tha Boss!"
        }
        try {
            sensorManager?.unregisterListener(this)
            applicationContext?.unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering resources: ${e.message}")
        }
        try {
            val wakeLock = cpuWakeLock
            if (wakeLock != null && wakeLock.isHeld) {
                wakeLock.release()
            }
            cpuWakeLock = null
            Log.d(TAG, "?? CPU Partial WakeLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release CPU wake lock: ${e.message}")
        }
        sensorManager = null
        accelerometer = null
        isRegistered = false
        stopAlarm()
        Log.d(TAG, "??? Touch guard disabled")
        return "Touch guard off kar diya Boss!"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRegistered || isAlarmTriggered ||
            event.sensor.type != Sensor.TYPE_ACCELEROMETER
        ) {
            return
        }
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (isFirstSample) {
            gravityX = x
            gravityY = y
            gravityZ = z
            isFirstSample = false
            return
        }
        gravityX = (gravityX * 0.95f) + (0.050000012f * x)
        gravityY = (gravityY * 0.95f) + (0.050000012f * y)
        gravityZ = (gravityZ * 0.95f) + (0.050000012f * z)
        val dx = x - gravityX
        val dy = y - gravityY
        val dz = z - gravityZ
        if (sqrt((dx * dx) + (dy * dy) + (dz * dz).toDouble()).toFloat() > MOTION_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime > DEBOUNCE_MS) {
                lastTriggerTime = now
                triggerAlarm()
            }
        }
    }

    private fun triggerAlarm() {
        Log.d(TAG, "?? MOTION DETECTED! Triggering alarm.")
        isAlarmTriggered = true
        val context = applicationContext ?: return
        try {
            val powerManager = context.getSystemService("power") as PowerManager
            // RECOVERY NOTE: 268435466 = FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE.
            powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "Ultron:AntiTheftWakeLock"
            ).acquire(8000L)
            Log.d(TAG, "?? Screen woken up")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake up screen: ${e.message}")
        }
        try {
            val audioManager = context.getSystemService("audio") as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            Log.d(TAG, "?? Alarm volume set to MAX ($maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm volume: ${e.message}")
        }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val player = MediaPlayer()
            player.setDataSource(context, uri)
            player.setAudioStreamType(AudioManager.STREAM_ALARM)
            player.isLooping = true
            player.prepare()
            player.start()
            mediaPlayer = player
            Log.d(TAG, "?? Looping alarm started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm media player: ${e.message}")
        }
        WakeWordService.getActiveInstance()
            ?.injectReminderMessage("Alert! Mera phone chhua kisne?! Maine photo le li hai!")
        Handler(Looper.getMainLooper()).postDelayed({
            CameraVisionManager.analyzeScene(
                context,
                "Describe the person who just picked up the phone. Mention facial characteristics or clothing if visible.",
                true,
                { description ->
                    Log.d(TAG, "Intruder analysis: $description")
                    try {
                        AppPreferences(context).lastIntruderDescription = description
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save intruder description: ${e.message}")
                    }
                    WakeWordService.getActiveInstance()
                        ?.injectReminderMessage("Maine ek photo click ki hai: $description")
                },
                { err ->
                    Log.e(TAG, "Failed to analyze scene: $err")
                }
            )
        }, 1500L)
    }

    private fun stopAlarm() {
        isAlarmTriggered = false
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop alarm: ${e.message}")
        }
        mediaPlayer = null
    }
}
