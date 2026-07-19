package com.flexfly.ultron.comms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Listens for `android.intent.action.PHONE_STATE` broadcasts and surfaces incoming
 * calls (with a resolved contact name) plus call-ended events via companion callbacks.
 *
 * Recovered from `Callreceiver.kt`. The mutable state and the [onIncomingCall] /
 * [onCallEnded] callbacks live in the [Companion] so other components (e.g. the
 * wake-word service) can wire themselves in.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != "android.intent.action.PHONE_STATE") return
        val state = intent.getStringExtra("state") ?: return
        val incomingNumber = intent.getStringExtra("incoming_number") ?: ""

        Log.d(TAG, "📞 State=$state number='$incomingNumber' lastState=$lastState")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                lastState = 1
                if (incomingNumber.isNotBlank() && pendingCallNumber != incomingNumber) {
                    val contactName = getContactName(context, incomingNumber)
                        ?: formatNumber(incomingNumber)
                    pendingCallNumber = incomingNumber
                    pendingCallName = contactName
                    Log.d(TAG, "📞 Incoming (with number): '$contactName' ($incomingNumber)")
                    onIncomingCall?.invoke(contactName, incomingNumber)
                    return
                }
                if (callHandled || incomingNumber.isNotBlank()) return
                callHandled = true
                Log.d(TAG, "📞 Ringing (waiting for number)...")
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                lastState = 2
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState == 0) return
                Log.d(TAG, "📞 Call ended")
                lastState = 0
                onCallEnded?.invoke()
                clearPending()
            }
        }
    }

    private fun getContactName(context: Context, number: String): String? {
        return try {
            context.contentResolver.query(
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
                ),
                arrayOf("display_name"), // RECOVERY NOTE: PhoneLookup.DISPLAY_NAME == "display_name"
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed: ${e.message}")
            null
        }
    }

    private fun formatNumber(number: String): String {
        if (number.length < 10) return number
        val last10 = number.takeLast(10)
        return last10.take(5) + " " + last10.drop(5)
    }

    companion object {
        private const val TAG = "CallReceiver"

        private var lastState: Int = 0
        private var callHandled: Boolean = false

        var pendingCallNumber: String? = null
        var pendingCallName: String? = null

        /** Invoked with (contactName, number) when a fresh incoming call is detected. */
        var onIncomingCall: ((String, String) -> Unit)? = null

        /** Invoked when the active call transitions to idle. */
        var onCallEnded: (() -> Unit)? = null

        fun clearPending() {
            pendingCallName = null
            pendingCallNumber = null
            callHandled = false
        }
    }
}
