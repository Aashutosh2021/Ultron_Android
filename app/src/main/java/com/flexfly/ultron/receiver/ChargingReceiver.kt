package com.flexfly.ultron.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ChargingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "android.intent.action.ACTION_POWER_DISCONNECTED" -> {
                Log.d(TAG, "🔋 Charger disconnected")
                onChargerDisconnected?.invoke()
            }
            "android.intent.action.ACTION_POWER_CONNECTED" -> {
                Log.d(TAG, "🔌 Charger connected")
                onChargerConnected?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "ChargingReceiver"
        var onChargerConnected: (() -> Unit)? = null
        var onChargerDisconnected: (() -> Unit)? = null
    }
}
