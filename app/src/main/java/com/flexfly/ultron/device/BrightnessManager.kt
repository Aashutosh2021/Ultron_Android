package com.flexfly.ultron.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Screen-brightness control via `Settings.System`.
 *
 * Recovered from `Brightnessmanager.kt`. All user-facing strings are Hinglish.
 * Requires the WRITE_SETTINGS permission; [permissionMessage] deep-links the user
 * to grant it when missing.
 */
object BrightnessManager {

    private const val TAG = "BrightnessManager"

    fun hasPermission(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    fun setAutoMode(context: Context): String {
        if (!hasPermission(context)) {
            return permissionMessage(context)
        }
        Settings.System.putInt(context.contentResolver, "screen_brightness_mode", 1)
        Log.d(TAG, "☀️ Auto brightness on")
        return "Auto brightness on kar diya Boss!"
    }

    fun setBrightness(context: Context, percent: Int): String {
        if (!hasPermission(context)) {
            return permissionMessage(context)
        }
        Settings.System.putInt(context.contentResolver, "screen_brightness_mode", 0)
        val value = (percent.coerceIn(1, 100) * 255) / 100
        Settings.System.putInt(context.contentResolver, "screen_brightness", value)
        Log.d(TAG, "💡 Brightness set to $percent% → $value")
        return "Brightness $percent percent kar diya Boss!"
    }

    fun brightnessUp(context: Context): String {
        return if (!hasPermission(context)) {
            permissionMessage(context)
        } else {
            setBrightness(context, min(getCurrentPercent(context) + 15, 100))
        }
    }

    fun brightnessDown(context: Context): String {
        return if (!hasPermission(context)) {
            permissionMessage(context)
        } else {
            setBrightness(context, max(getCurrentPercent(context) - 15, 5))
        }
    }

    fun setMaxBrightness(context: Context): String {
        return setBrightness(context, 100)
    }

    fun setMinBrightness(context: Context): String {
        return setBrightness(context, 5)
    }

    fun getCurrentPercent(context: Context): Int {
        return try {
            ((Settings.System.getInt(context.contentResolver, "screen_brightness") * 100) / 255).coerceIn(0, 100)
        } catch (e: Exception) {
            50
        }
    }

    fun getBrightnessStatus(context: Context): String {
        val currentPercent = getCurrentPercent(context)
        try {
            if (Settings.System.getInt(context.contentResolver, "screen_brightness_mode") == 1) {
                return "Auto brightness on hai Boss!"
            }
        } catch (e: Exception) {
        }
        return "Screen brightness abhi $currentPercent percent hai Boss."
    }

    private fun permissionMessage(context: Context): String {
        val intent = Intent("android.settings.action.MANAGE_WRITE_SETTINGS")
        intent.data = Uri.parse("package:${context.packageName}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Boss, ek baar permission do — settings khol diya!"
    }
}
