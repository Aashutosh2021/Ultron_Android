package com.flexfly.ultron.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.flexfly.ultron.voice.VoiceService

/**
 * Broadcast receiver that watches `ACTION_BATTERY_CHANGED` and pushes voice alerts
 * to [WakeWordService] when the battery hits 100% (while charging) or drops to 20%.
 *
 * Recovered from `Batterymonitor.kt`. Kept as a process-wide singleton via
 * [getInstance] so registration is idempotent.
 *
 * RECOVERY NOTE: jadx flagged [onReceive] as "decompiled incorrectly" with a
 * duplicated region — the second copy had lost the charging/alert-fired guards.
 * The reconstruction below uses the fully-guarded logic from the first (intact)
 * region, which is the only self-consistent reading of the alert/reset flow.
 */
class BatteryMonitor private constructor(private val context: Context) : BroadcastReceiver() {

    private var isRegistered = false
    private var has100AlertFired = false
    private var has20AlertFired = false
    private var lastBatteryLevel = -1

    fun startMonitoring() {
        if (isRegistered) {
            Log.d(TAG, "⚠️ Already monitoring")
            return
        }
        try {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(this, filter)
            isRegistered = true
            Log.d(TAG, "✅ Battery monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start monitoring: ${e.message}")
        }
    }

    fun stopMonitoring() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(this)
                isRegistered = false
                Log.d(TAG, "✅ Battery monitoring stopped")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to stop monitoring: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) {
            return
        }
        try {
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            val status = intent.getIntExtra("status", -1)
            if (level < 0 || scale < 0) {
                return
            }
            val percent = (level * 100) / scale
            val isCharging = status == 2 || status == 5

            if (percent != lastBatteryLevel) {
                Log.d(TAG, "🔋 Battery: $percent% (${if (isCharging) "Charging" else "Not charging"})")
                lastBatteryLevel = percent
            }

            if (percent == 100 && isCharging && !has100AlertFired) {
                Log.d(TAG, "🎉 Battery reached 100%!")
                alert100Percent()
                has100AlertFired = true
            }

            if (percent == 20 && !isCharging && !has20AlertFired) {
                Log.d(TAG, "⚠️ Battery at 20%!")
                alert20Percent()
                has20AlertFired = true
            }

            if (percent < 100 || !isCharging) {
                has100AlertFired = false
            }

            if (percent > 20 || isCharging) {
                has20AlertFired = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing battery update: ${e.message}")
        }
    }

    private fun alert100Percent() {
        Log.d(TAG, "🔔 Sending 100% alert to Ultron")
        VoiceService.getActiveInstance()
            ?.injectReminderMessage("Boss, battery 100 percent ho gaya! Charger se nikal do!")
    }

    private fun alert20Percent() {
        Log.d(TAG, "🔔 Sending 20% low battery alert to Ultron")
        VoiceService.getActiveInstance()
            ?.injectReminderMessage("Boss, battery sirf 20 percent reh gaya hai! Charging laga do!")
    }

    fun getCurrentBatteryLevel(): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra("level", -1) ?: -1
        val scale = batteryStatus?.getIntExtra("scale", -1) ?: -1
        if (level < 0 || scale <= 0) {
            return -1
        }
        return (level * 100) / scale
    }

    fun isCharging(): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra("status", -1) ?: -1
        return status == 2 || status == 5
    }

    companion object {
        private const val TAG = "BatteryMonitor"

        @Volatile
        private var instance: BatteryMonitor? = null

        fun getInstance(context: Context): BatteryMonitor {
            return instance ?: synchronized(this) {
                instance ?: BatteryMonitor(context).also { instance = it }
            }
        }
    }
}
