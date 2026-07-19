package com.flexfly.ultron.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.flexfly.ultron.BuildConfig
import com.flexfly.ultron.model.KeyVerifyState
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Access-key / license verification against the Ultron license server.
 *
 * [verify] performs the online check (with one-shot migration of legacy keys found in
 * `assets/old_keys.json`); [ensureDeviceIdStored] and [checkServiceStatus] are the
 * fire-and-forget re-validation paths. Every request is signed with the app's
 * SHA-256 signing certificate ([getAppSignatureSHA256]) so a repackaged APK is rejected.
 *
 * Recovered from `AccessKeyVerifier` (originally declared in Onboardingscreen.kt).
 */
object AccessKeyVerifier {

    // RECOVERY NOTE: externalized to BuildConfig (was Base64-obfuscated
    // "aHR0cDovLzg5LjExNi4xMzQuMjQ4OjgwMDA=" → "http://89.116.134.248:8000")
    private val SERVER_URL: String = BuildConfig.LICENSE_SERVER_URL

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Verifies [key] for [deviceId]. If the key exists in `assets/old_keys.json`
     * a migration request is sent (server returns a freshly-assigned key); otherwise
     * a plain verification is performed.
     */
    suspend fun verify(context: Context, key: String, deviceId: String): KeyVerifyState {
        val trimmedKey = key.trim()
        if (trimmedKey.isBlank()) {
            return KeyVerifyState.Error("Access key cannot be empty.")
        }

        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            try {
                val oldKeyData = getOldKeyData(context, trimmedKey)
                if (oldKeyData != null) {
                    Log.d(TAG, "Key found in old_keys.json, requesting migration from server...")
                    val payload = JSONObject().apply {
                        put("key", trimmedKey)
                        put("deviceId", deviceId)
                        put("product", "max")
                        put("migrate", true)
                        put("appSignature", getAppSignatureSHA256(context))
                    }
                    val request = Request.Builder()
                        .url("$SERVER_URL/api/verify")
                        .addHeader("ngrok-skip-browser-warning", "true")
                        .post(payload.toString().toRequestBody(JSON))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val json = safeParseJson(response.body?.string() ?: "")
                        if (!response.isSuccessful) {
                            return@withContext KeyVerifyState.Error(
                                json.optString("message", "Migration failed.")
                            )
                        }
                        val newKey = if (json.has("key") && !json.isNull("key")) {
                            json.getString("key")
                        } else {
                            null
                        }
                        return@withContext if (newKey != null) {
                            Log.d(TAG, "Successfully migrated key $trimmedKey. Assigned key: $newKey")
                            KeyVerifyState.Success(newKey)
                        } else {
                            KeyVerifyState.Error("Server did not return a valid key for migration.")
                        }
                    }
                } else {
                    val payload = JSONObject().apply {
                        put("key", trimmedKey)
                        put("deviceId", deviceId)
                        put("product", "max")
                        put("migrate", false)
                        put("appSignature", getAppSignatureSHA256(context))
                    }
                    val request = Request.Builder()
                        .url("$SERVER_URL/api/verify")
                        .addHeader("ngrok-skip-browser-warning", "true")
                        .post(payload.toString().toRequestBody(JSON))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val json = safeParseJson(response.body?.string() ?: "")
                        // RECOVERY NOTE: the decompiled state machine inverts this branch
                        // (returns Error when isSuccessful). Preserved verbatim.
                        return@withContext if (response.isSuccessful) {
                            KeyVerifyState.Error(
                                json.optString("message", "Invalid access key. Please check and try again.")
                            )
                        } else {
                            KeyVerifyState.Success()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error in verify: ${e.message}", e)
                KeyVerifyState.Error("Network error. Please check your connection.")
            }
        }
    }

    /**
     * Fire-and-forget: re-registers [deviceId] for [key] with the server. Invokes
     * [onAccessDenied] if the server rejects the request (e.g. key revoked or bound
     * to another device).
     */
    fun ensureDeviceIdStored(
        context: Context,
        key: String,
        deviceId: String,
        onAccessDenied: (() -> Unit)? = null,
    ) {
        val trimmedKey = key.trim()
        if (trimmedKey.isBlank() || deviceId.isBlank()) return

        val client = OkHttpClient()
        val payload = JSONObject().apply {
            put("key", trimmedKey)
            put("deviceId", deviceId)
            put("product", "max")
            put("migrate", false)
            put("appSignature", getAppSignatureSHA256(context))
        }
        val request = Request.Builder()
            .url("$SERVER_URL/api/verify")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "ensureDeviceIdStored network failure: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "ensureDeviceIdStored Access Denied: response code ${response.code}")
                        onAccessDenied?.invoke()
                    } else {
                        Log.d(TAG, "ensureDeviceIdStored verification success for device $deviceId")
                    }
                }
            }
        })
    }

    /**
     * Fire-and-forget service-status probe. Invokes [onResult] with (ok, message);
     * message is null on success or a server-supplied reason on failure.
     */
    fun checkServiceStatus(
        context: Context,
        key: String,
        deviceId: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val trimmedKey = key.trim()
        if (trimmedKey.isBlank()) {
            onResult(false, "Invalid access key.")
            return
        }

        val client = OkHttpClient()
        val payload = JSONObject().apply {
            put("key", trimmedKey)
            put("deviceId", deviceId)
            put("product", "max")
            put("migrate", false)
            put("appSignature", getAppSignatureSHA256(context))
        }
        val request = Request.Builder()
            .url("$SERVER_URL/api/verify")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, "Network error. Please check your connection.")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val json = safeParseJson(response.body?.string() ?: "")
                    if (response.isSuccessful) {
                        onResult(true, null)
                    } else {
                        onResult(false, json.optString("message", "Service check failed."))
                    }
                }
            }
        })
    }

    /**
     * Reads `assets/old_keys.json` and returns the metadata map for [key] under the
     * `Max` product, or null if the file/key is absent.
     */
    private fun getOldKeyData(context: Context, key: String): Map<String, Any>? {
        return try {
            val jsonText = context.assets.open("old_keys.json").use { input ->
                String(input.readBytes(), Charsets.UTF_8)
            }
            val keyObj = JSONObject(jsonText).optJSONObject("Max")?.optJSONObject(key)
                ?: return null
            val result = LinkedHashMap<String, Any>()
            for (name in keyObj.keys()) {
                val value = keyObj.get(name)
                if (value != JSONObject.NULL) {
                    result[name] = value
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error reading old_keys.json: ${e.message}", e)
            null
        }
    }

    /** Parses [bodyStr] into a [JSONObject], returning an empty object on any error. */
    private fun safeParseJson(bodyStr: String): JSONObject = try {
        if (bodyStr.isNotBlank()) JSONObject(bodyStr) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    /** Uppercase hex SHA-256 of the app's first signing certificate, or "" on error. */
    private fun getAppSignatureSHA256(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                )
            }
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            if (signatures.isNullOrEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
            val sb = StringBuilder()
            for (b in digest) {
                val hex = Integer.toHexString(b.toInt() and 0xFF)
                if (hex.length == 1) sb.append("0")
                sb.append(hex)
            }
            sb.toString().uppercase(Locale.US)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app signature: ${e.message}", e)
            ""
        }
    }

    private const val TAG = "AccessKeyVerifier"
}
