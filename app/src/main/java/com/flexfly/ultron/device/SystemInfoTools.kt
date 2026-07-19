package com.flexfly.ultron.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Calendar
import java.util.Locale

/**
 * Stateless helpers for reading the current time, date, day and battery state.
 *
 * Recovered from `Systeminfotools.kt`.
 *
 * RECOVERY NOTE: the decompiler substituted constants whose values equal the real
 * strings — `TranslateLanguage.HINDI` == "hi", `NotificationCompat.CATEGORY_STATUS`
 * == "status". The original references are kept where the source used them.
 */
object SystemInfoTools {

    fun getTime(): String {
        val calendar = Calendar.getInstance()
        var hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val amPm = if (hour < 12) "AM" else "PM"
        if (hour == 0) {
            hour = 12
        } else if (hour > 12) {
            hour -= 12
        }
        val minuteStr = if (minute < 10) "0$minute" else minute.toString()
        return "$hour:$minuteStr $amPm"
    }

    fun getDate(): String {
        val calendar = Calendar.getInstance()
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale(TranslateLanguage.HINDI))
            ?: calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.ENGLISH)
            ?: ""
        return "$day $month ${calendar.get(Calendar.YEAR)}"
    }

    fun getDay(): String {
        val calendar = Calendar.getInstance()
        return calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale(TranslateLanguage.HINDI))
            ?: calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)
            ?: ""
    }

    fun getBatteryPercentage(context: Context): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra("level", -1) ?: -1
        val scale = batteryStatus?.getIntExtra("scale", -1) ?: -1
        if (level < 0 || scale <= 0) {
            return -1
        }
        return (level * 100) / scale
    }

    fun isCharging(context: Context): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra("status", -1) ?: -1
        return status == 2 || status == 5
    }
}
