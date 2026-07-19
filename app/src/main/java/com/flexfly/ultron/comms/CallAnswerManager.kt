package com.flexfly.ultron.comms

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Accepts / rejects the currently-ringing call and reports whether one is ringing.
 *
 * Recovered from `Callanswermanager.kt`. Uses [TelecomManager] where available and
 * falls back to reflection / [AudioManager] on older or restricted devices.
 *
 * RECOVERY NOTE: the decompiler substituted constants whose values equal the real
 * literals — `HintConstants.AUTOFILL_HINT_PHONE` == "phone" and
 * `AudioManager.MODE_IN_CALL` == 2. Restored to the intended literals below.
 */
object CallAnswerManager {

    private const val TAG = "CallAnswerManager"

    fun acceptCall(context: Context): Boolean {
        Log.d(TAG, "📞 Accepting call...")
        return try {
            val telecom = context.getSystemService("telecom") as TelecomManager
            telecom.acceptRingingCall()
            Log.d(TAG, "✅ Call accepted via TelecomManager")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ TelecomManager accept failed: ${e.message}")
            try {
                val audio = context.getSystemService("audio") as AudioManager
                audio.mode = AudioManager.MODE_IN_CALL // RECOVERY NOTE: setMode(2)
                Log.d(TAG, "✅ Call accepted via AudioManager fallback")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "❌ AudioManager fallback failed: ${e2.message}")
                false
            }
        }
    }

    fun rejectCall(context: Context): Boolean {
        Log.d(TAG, "📵 Rejecting call...")
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val telecom = context.getSystemService("telecom") as TelecomManager
                val ended = telecom.endCall()
                Log.d(TAG, "✅ Call rejected via TelecomManager: $ended")
                return ended
            } catch (e: Exception) {
                Log.e(TAG, "❌ TelecomManager endCall failed: ${e.message}")
            }
        }
        return try {
            val telephony = context.getSystemService("phone") as TelephonyManager // RECOVERY NOTE: AUTOFILL_HINT_PHONE == "phone"
            val endCall = telephony.javaClass.getDeclaredMethod("endCall")
            endCall.isAccessible = true
            endCall.invoke(telephony)
            Log.d(TAG, "✅ Call rejected via reflection")
            true
        } catch (e2: Exception) {
            Log.e(TAG, "❌ Reflection reject failed: ${e2.message}")
            false
        }
    }

    fun isCallRinging(context: Context): Boolean {
        // RECOVERY NOTE: in the decompiled bytecode the getSystemService cast is guarded
        // by try/catch that only swallows the exception, but the getCallState() read sits
        // AFTER the catch. Preserving that: the service lookup effectively can't fail here
        // (a phone always has TelephonyManager), so the read runs unconditionally.
        val telephony = try {
            context.getSystemService("phone") as TelephonyManager // RECOVERY NOTE: AUTOFILL_HINT_PHONE == "phone"
        } catch (ignored: Exception) {
            null
        }
        return telephony!!.callState == TelephonyManager.CALL_STATE_RINGING // RECOVERY NOTE: getCallState() == 1
    }
}
