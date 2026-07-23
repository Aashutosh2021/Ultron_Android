package com.flexfly.ultron.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.flexfly.ultron.voice.VoiceService

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) {
            return
        }
        val message = intent?.getStringExtra("reminder_message") ?: "Reminder Boss!"
        Log.d("ReminderReceiver", "? Reminder fired: $message")
        showReminderNotification(context, message)
        val injected = "Boss, aapka reminder hai: $message"
        val activeInstance = VoiceService.getActiveInstance()
        if (activeInstance != null && activeInstance.isGeminiActive()) {
            Log.d("ReminderReceiver", "?? Injecting reminder to active session")
            VoiceService.getActiveInstance()?.injectReminderMessage(injected)
            return
        }
        Log.d("ReminderReceiver", "?? Starting one-shot session for reminder")
        val instance = VoiceService.getActiveInstance()
        if (instance != null) {
            instance.startReminderSession(injected)
        } else {
            Log.w("ReminderReceiver", "?? WakeWordService not running — only notification shown")
        }
    }

    private fun showReminderNotification(context: Context, message: String) {
        val notificationManager =
            context.getSystemService("notification") as NotificationManager
        val notificationChannel =
            NotificationChannel("reminder_channel", "Ultron Reminders", NotificationManager.IMPORTANCE_HIGH)
        notificationChannel.description = "Ultron ke reminders"
        notificationChannel.enableVibration(true)
        notificationManager.createNotificationChannel(notificationChannel)
        val build = NotificationCompat.Builder(context, "reminder_channel")
            .setContentTitle("? Ultron Reminder")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), build)
    }
}
