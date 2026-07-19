package com.flexfly.ultron.device

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import kotlin.math.abs

/**
 * System-wide volume, mute and ringer-mode control.
 *
 * Recovered from `Volumemanager.kt`. Adjusts every controllable stream at once
 * ([ALL_CONTROLLABLE_STREAMS]) for reliability across OEMs, with SecurityException
 * fallbacks. Ringer-mode changes need DND (notification-policy) access.
 */
object VolumeManager {

    private const val TAG = "VolumeManager"

    // STREAM_MUSIC, STREAM_VOICE_CALL, STREAM_RING, STREAM_NOTIFICATION
    private val ALL_CONTROLLABLE_STREAMS = listOf(3, 0, 2, 5)

    private fun am(context: Context): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun hasDndAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun requestDndAccess(context: Context) {
        try {
            val intent = Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "DND settings nahi khul saki: ${e.message}")
        }
    }

    fun volumeUp(context: Context, steps: Int = 2): String {
        val am = am(context)
        val uiFlag = safeUiFlag(context)
        Log.d(TAG, "volumeUp(steps=$steps) | audio.mode=${am.mode}")
        for (i in 0 until steps) {
            try {
                am.adjustVolume(AudioManager.ADJUST_RAISE, uiFlag)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException volumeUp: ${e.message}")
                fallbackAdjust(am, 1, uiFlag)
            } catch (e: Exception) {
                Log.w(TAG, "adjustVolume failed: ${e.message}")
                fallbackAdjust(am, 1, uiFlag)
            }
        }
        for (stream in ALL_CONTROLLABLE_STREAMS) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
            } catch (e: Exception) {
            }
        }
        val percent = getActiveStreamPercent(context)
        Log.d(TAG, "Volume up -> $percent%")
        return "Volume badha diya Boss! Abhi $percent percent hai."
    }

    fun volumeDown(context: Context, steps: Int = 2): String {
        val am = am(context)
        val uiFlag = safeUiFlag(context)
        Log.d(TAG, "volumeDown(steps=$steps) | audio.mode=${am.mode}")
        for (i in 0 until steps) {
            try {
                am.adjustVolume(AudioManager.ADJUST_LOWER, uiFlag)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException volumeDown: ${e.message}")
                fallbackAdjust(am, -1, uiFlag)
            } catch (e: Exception) {
                Log.w(TAG, "adjustVolume failed: ${e.message}")
                fallbackAdjust(am, -1, uiFlag)
            }
        }
        for (stream in ALL_CONTROLLABLE_STREAMS) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
            } catch (e: Exception) {
            }
        }
        val percent = getActiveStreamPercent(context)
        Log.d(TAG, "Volume down -> $percent%")
        return "Volume kam kar diya Boss! Abhi $percent percent hai."
    }

    fun setMaxVolume(context: Context): String {
        val am = am(context)
        val uiFlag = safeUiFlag(context)
        Log.d(TAG, "setMaxVolume called | audio.mode=${am.mode} (0=NORMAL, 1=RINGTONE, 2=IN_CALL, 3=IN_COMMUNICATION)")
        var anySucceeded = false
        for (stream in ALL_CONTROLLABLE_STREAMS) {
            try {
                val before = am.getStreamVolume(stream)
                if (am.isStreamMute(stream)) {
                    am.adjustStreamVolume(stream, 100, 0)
                }
                val maxVolume = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, maxVolume, if (stream == 3) uiFlag else 0)
                Log.d(TAG, "Stream $stream: $before -> ${am.getStreamVolume(stream)} (max=$maxVolume)")
                anySucceeded = true
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException stream $stream: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "setMaxVolume stream $stream failed: ${e.message}")
            }
        }
        for (i in 0 until 20) {
            try {
                am.adjustVolume(AudioManager.ADJUST_RAISE, 0)
            } catch (e: Exception) {
                Log.w(TAG, "adjustVolume raise failed: ${e.message}")
            }
        }
        Log.d(TAG, "adjustVolume RAISE x20 done")
        val percent = getActiveStreamPercent(context)
        Log.d(TAG, "Final active stream percent: $percent%")
        return if (anySucceeded) {
            "Full volume kar diya Boss! Abhi $percent percent pe hai."
        } else {
            "Boss, volume max nahi ho saka — system ne block kiya."
        }
    }

    fun setVolume(context: Context, percent: Int): String {
        val am = am(context)
        val uiFlag = safeUiFlag(context)
        Log.d(TAG, "setVolume($percent%) called | audio.mode=${am.mode}")
        var anySucceeded = false
        for (stream in ALL_CONTROLLABLE_STREAMS) {
            try {
                val maxVolume = am.getStreamMaxVolume(stream)
                val target = (percent.coerceIn(0, 100) * maxVolume) / 100
                val before = am.getStreamVolume(stream)
                if (target > 0 && am.isStreamMute(stream)) {
                    am.adjustStreamVolume(stream, 100, 0)
                }
                am.setStreamVolume(stream, target, if (stream == 3) uiFlag else 0)
                Log.d(TAG, "Stream $stream: $before -> ${am.getStreamVolume(stream)} (target=$target/$maxVolume)")
                anySucceeded = true
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException stream $stream: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "setVolume stream $stream failed: ${e.message}")
            }
        }
        val diff = percent - getCurrentPercent(context, getActiveStream(context))
        if (diff != 0) {
            val direction = if (diff > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            val times = (abs(diff) / 7) + 1
            for (i in 0 until times) {
                try {
                    am.adjustVolume(direction, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "adjustVolume failed: ${e.message}")
                }
            }
            Log.d(TAG, "adjustVolume $direction x$times done (diff=$diff%)")
        }
        val activePercent = getActiveStreamPercent(context)
        Log.d(TAG, "Final active stream percent: $activePercent%")
        return if (anySucceeded) {
            "Volume $percent percent kar diya Boss! Abhi $activePercent pe hai."
        } else {
            "Boss, volume set nahi ho saka — system ne block kiya."
        }
    }

    fun mute(context: Context): String {
        val am = am(context)
        safeUiFlag(context)
        for (stream in ALL_CONTROLLABLE_STREAMS) {
            try {
                am.adjustStreamVolume(stream, -100, 0)
                Log.d(TAG, "Stream $stream muted")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException muting stream $stream: ${e.message}")
                try {
                    am.setStreamVolume(stream, 0, 0)
                } catch (e2: Exception) {
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mute stream $stream failed: ${e.message}")
            }
        }
        setRingerSafe(context, 0)
        return "Phone mute kar diya Boss!"
    }

    fun unmute(context: Context): String {
        val am = am(context)
        for (stream in ALL_CONTROLLABLE_STREAMS) {
            try {
                am.adjustStreamVolume(stream, 100, 0)
                if (am.getStreamVolume(stream) == 0) {
                    am.setStreamVolume(stream, ((am.getStreamMaxVolume(stream) * 0.5).toInt()).coerceAtLeast(1), 0)
                }
                Log.d(TAG, "Stream $stream unmuted")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException unmuting stream $stream: ${e.message}")
                try {
                    am.setStreamVolume(stream, ((am.getStreamMaxVolume(stream) * 0.5).toInt()).coerceAtLeast(1), 0)
                } catch (e2: Exception) {
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unmute stream $stream failed: ${e.message}")
            }
        }
        setRingerSafe(context, 2)
        return "Phone unmute kar diya Boss!"
    }

    fun setSilentMode(context: Context): String {
        if (!hasDndAccess(context)) {
            requestDndAccess(context)
            return "Boss, pehle DND permission do — Settings khol rahi hoon!"
        }
        return if (setRingerSafe(context, 0)) {
            "Phone silent mode pe Boss!"
        } else {
            "Boss, silent mode set nahi ho saka."
        }
    }

    fun setVibrateMode(context: Context): String {
        if (!hasDndAccess(context)) {
            requestDndAccess(context)
            return "Boss, pehle DND permission do — Settings khol rahi hoon!"
        }
        return if (setRingerSafe(context, 1)) {
            "Vibrate mode on kar diya Boss!"
        } else {
            "Boss, vibrate mode set nahi ho saka."
        }
    }

    fun setNormalMode(context: Context): String {
        if (!hasDndAccess(context)) {
            requestDndAccess(context)
            return "Boss, pehle DND permission do — Settings khol rahi hoon!"
        }
        return if (setRingerSafe(context, 2)) {
            "Normal mode on kar diya Boss!"
        } else {
            "Boss, normal mode set nahi ho saka."
        }
    }

    fun getVolumeStatus(context: Context): String {
        val am = am(context)
        val activeStream = getActiveStream(context)
        val currentPercent = getCurrentPercent(context, activeStream)
        val isMuted = am.isStreamMute(activeStream)
        val ringer = when (am.ringerMode) {
            0 -> "silent"
            1 -> "vibrate"
            else -> "normal"
        }
        val mutedSuffix = if (isMuted) ", aur muted bhi hai" else ""
        return "Volume abhi $currentPercent percent hai Boss$mutedSuffix, ringer $ringer mode mein hai."
    }

    fun getCurrentVolume(context: Context): Int {
        return getActiveStreamPercent(context)
    }

    fun isStreamMuted(context: Context, stream: Int = 3): Boolean {
        return am(context).isStreamMute(stream)
    }

    private fun getActiveStream(context: Context): Int {
        val am = am(context)
        return when (am.mode) {
            AudioManager.MODE_RINGTONE -> 2
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> 0
            else -> {
                am.isMusicActive
                3
            }
        }
    }

    private fun getActiveStreamPercent(context: Context): Int {
        return getCurrentPercent(context, getActiveStream(context))
    }

    private fun getCurrentPercent(context: Context, stream: Int): Int {
        val am = am(context)
        val volume = am.getStreamVolume(stream)
        val maxVolume = am.getStreamMaxVolume(stream)
        return if (maxVolume > 0) (volume * 100) / maxVolume else 0
    }

    private fun safeUiFlag(context: Context): Int {
        return try {
            if (context !is Activity || context.isFinishing) {
                0
            } else if (!context.isDestroyed) {
                AudioManager.FLAG_SHOW_UI
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun setRingerSafe(context: Context, mode: Int): Boolean {
        if (!hasDndAccess(context)) {
            Log.w(TAG, "No DND access — cannot change ringer mode")
            return false
        }
        return try {
            am(context).ringerMode = mode
            Log.d(TAG, "Ringer mode set to $mode")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException - setRingerMode: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "setRingerMode failed: ${e.message}")
            false
        }
    }

    private fun fallbackAdjust(audio: AudioManager, direction: Int, uiFlag: Int) {
        try {
            val volume = audio.getStreamVolume(3)
            val maxVolume = audio.getStreamMaxVolume(3)
            val target = if (direction == 1) (volume + 1).coerceAtMost(maxVolume) else (volume - 1).coerceAtLeast(0)
            audio.setStreamVolume(3, target, uiFlag)
            Log.d(TAG, "Fallback adjust -> $target/$maxVolume")
        } catch (e: Exception) {
            Log.e(TAG, "fallbackAdjust failed: ${e.message}")
        }
    }
}
