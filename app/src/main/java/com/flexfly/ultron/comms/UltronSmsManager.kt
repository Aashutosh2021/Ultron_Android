package com.flexfly.ultron.comms

import android.content.Context
import android.telephony.SmsManager
import android.util.Log

/**
 * Resolves a contact by name and sends an SMS, or reports why it couldn't.
 *
 * Recovered from `UltronSmsManager.kt`. [sendSms] disambiguates via [ContactManager];
 * [sendSmsToNumber] does the raw send with multipart handling.
 */
object UltronSmsManager {

    private const val TAG = "UltronSmsManager"

    /** Outcome of a name-based SMS send. */
    sealed class SmsResult {
        data class Success(val name: String) : SmsResult()
        data class MultipleContacts(val contacts: List<ContactManager.Contact>) : SmsResult()
        data class NotFound(val query: String) : SmsResult()
        data class Error(val reason: String) : SmsResult()
    }

    fun sendSms(context: Context, contactName: String, message: String): SmsResult {
        Log.d(TAG, "📩 sendSms: contact='$contactName'")
        val contacts = ContactManager.searchContacts(context, contactName)
        Log.d(TAG, "📞 Contacts found: ${contacts.size}")
        if (contacts.isEmpty()) {
            return SmsResult.NotFound(contactName)
        }
        if (contacts.size > 1) {
            return SmsResult.MultipleContacts(contacts)
        }
        val contact = contacts[0]
        return if (sendSmsToNumber(contact.number, message)) {
            Log.d(TAG, "✅ SMS sent to ${contact.name}")
            SmsResult.Success(contact.name)
        } else {
            SmsResult.Error("SMS nahi bheja ja saka")
        }
    }

    fun sendSmsToNumber(number: String, message: String): Boolean {
        return try {
            val clean = Regex("[^+\\d]").replace(number, "")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(clean, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(clean, null, message, null, null)
            }
            Log.d(TAG, "✅ SMS sent to $clean")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ SMS failed: ${e.message}")
            false
        }
    }
}
