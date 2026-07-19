package com.flexfly.ultron.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.flexfly.ultron.receiver.ReminderReceiver
import java.util.Calendar
import java.util.Locale

object ReminderManager {

    private const val TAG = "ReminderManager"

    fun setReminder(context: Context, timeStr: String, message: String): String {
        val triggerMs = parseTime(timeStr)
            ?: return "Boss, yeh time samajh nahi aaya: \"$timeStr\""
        val now = System.currentTimeMillis()
        if (triggerMs <= now) {
            return "Boss, yeh time toh nikal gaya. Aage ka time bolo!"
        }
        val diff = triggerMs - now
        val minutes = diff / 60000
        val seconds = diff / 1000
        scheduleAlarm(context, triggerMs, message)
        setSystemClockAlarmOrTimer(context, timeStr, triggerMs, message)
        if (seconds < 60) {
            return "Theek hai Boss! $seconds second mein yaad dilaungi — \"$message\""
        }
        if (minutes < 60) {
            return "Theek hai Boss! $minutes minute mein yaad dilaungi — \"$message\""
        }
        val hours = minutes / 60
        val remMinutes = minutes % 60
        val durationText = if (remMinutes > 0) {
            "$hours ghante $remMinutes minute"
        } else {
            "$hours ghante"
        }
        return "Theek hai Boss! $durationText mein yaad dilaungi — \"$message\""
    }

    private fun setSystemClockAlarmOrTimer(
        context: Context,
        timeStr: String,
        triggerMs: Long,
        message: String
    ) {
        try {
            val seconds = ((triggerMs - System.currentTimeMillis()) / 1000).toInt()
            val lowerCase = timeStr.lowercase(Locale.getDefault())
            val containsMatchIn =
                Regex("(\\d+)\\s*(minute|min|second|sec|ghante|ghanta|hour|hrs?)").containsMatchIn(lowerCase)
            if ((lowerCase.contains("timer") ||
                    lowerCase.contains("sec") ||
                    lowerCase.contains("second") ||
                    (containsMatchIn && seconds in 1 until 3600)) && seconds > 0
            ) {
                val intent = Intent("android.intent.action.SET_TIMER")
                intent.putExtra("android.intent.extra.alarm.LENGTH", seconds)
                intent.putExtra("android.intent.extra.alarm.MESSAGE", message)
                intent.putExtra("android.intent.extra.alarm.SKIP_UI", true)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "⏱️ System Timer set for $seconds seconds: $message")
                return
            }
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = triggerMs
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            val intent = Intent("android.intent.action.SET_ALARM")
            intent.putExtra("android.intent.extra.alarm.HOUR", hour)
            intent.putExtra("android.intent.extra.alarm.MINUTES", minute)
            intent.putExtra("android.intent.extra.alarm.MESSAGE", message)
            intent.putExtra("android.intent.extra.alarm.SKIP_UI", true)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "⏰ System Alarm set for $hour:$minute: $message")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set system clock alarm/timer: ${e.message}", e)
        }
    }

    private fun scheduleAlarm(context: Context, triggerMs: Long, message: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        intent.putExtra("reminder_message", message)
        // RECOVERY NOTE: request code masks trigger time into an Int. Decompiler substituted
        // SieveCacheKt.NodeLinkMask (0x7FFFFFFF) as the modulo — restored to Int.MAX_VALUE.
        val broadcast = PendingIntent.getBroadcast(
            context,
            (triggerMs % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= 31) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, broadcast)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, broadcast)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, broadcast)
        }
        Log.d(TAG, "⏰ Alarm set for: $triggerMs (msg: $message)")
    }

    private fun parseTime(input: String): Long? {
        val lowerCase = input.lowercase(Locale.getDefault())
        val text = lowerCase.trim()
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        val durationMatch =
            Regex("(\\d+)\\s*(minute|min|second|sec|ghante|ghanta|hour|hrs?)").find(text)
        if (durationMatch != null) {
            val amount = durationMatch.groupValues[1].toLongOrNull()
            if (amount != null) {
                val unit = durationMatch.groupValues[2]
                val multiplier = when {
                    unit.startsWith("sec") -> 1000L
                    unit.startsWith("min") -> 60000L
                    unit.startsWith("ghant") || unit.startsWith("hour") || unit.startsWith("hr") -> 3600000L
                    else -> 0L
                }
                return now + (amount * multiplier)
            }
        }

        val clockMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*baj").find(text) ?: return null
        val hour = clockMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = clockMatch.groupValues[2].toIntOrNull() ?: 0
        val isPm = (text.contains("raat") && hour < 6) ||
            text.contains("raat") ||
            text.contains("shaam") ||
            if (!text.contains("dopahar")) {
                !(!text.contains("subah") && hour < 7)
            } else {
                hour >= 5
            }
        var resolvedHour = if (isPm && hour < 12) hour + 12 else hour
        if (!isPm && hour == 12) {
            resolvedHour = 0
        }
        calendar.set(Calendar.HOUR_OF_DAY, resolvedHour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (text.contains("kal")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (calendar.timeInMillis <= now && !text.contains("kal")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }
}
