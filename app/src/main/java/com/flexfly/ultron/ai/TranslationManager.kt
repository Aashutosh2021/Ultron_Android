package com.flexfly.ultron.ai

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * On-the-fly text translation for the assistant.
 *
 * Recovered from `TranslationManager.kt`. Uses the free Google Translate web
 * endpoint (`translate_a/single`) with a small retry loop, then falls back to
 * the original text if every attempt fails. [getLanguageCode] maps the many
 * Indian language/dialect names the app supports onto the closest ML Kit
 * [TranslateLanguage] code (defaulting to Hindi).
 */
object TranslationManager {

    private const val TAG = "TranslationManager"

    /**
     * Translate [text] from [sourceLang] to [targetLang], retrying up to 3 times
     * with a linear backoff. Returns the original [text] when the source and
     * target languages match, the text is blank, or all attempts fail.
     */
    suspend fun translate(
        text: String,
        sourceLang: String = "auto",
        targetLang: String = TranslateLanguage.ENGLISH,
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank() || sourceLang == targetLang) {
            return@withContext text
        }
        val maxAttempts = 3
        var attempt = 0
        while (attempt < maxAttempts) {
            try {
                val result = attemptTranslation(text, sourceLang, targetLang)
                if (result != null) {
                    Log.d(
                        TAG,
                        "✅ Translated ($sourceLang→$targetLang): '${text.take(50)}...' → '${result.take(50)}...'"
                    )
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Translation attempt ${attempt + 1} failed: ${e.message}")
            }
            attempt++
            if (attempt < maxAttempts) {
                delay(attempt * 500L)
            }
        }
        Log.w(TAG, "⚠️ All translation attempts failed — returning original")
        text
    }

    private fun attemptTranslation(text: String, sourceLang: String, targetLang: String): String? {
        try {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=" +
                    URLEncoder.encode(text, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            conn.setRequestProperty("Accept", "*/*")
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    } ?: "No error body"
                } catch (e: Exception) {
                    "Could not read error: ${e.message}"
                }
                Log.e(TAG, "❌ Translation HTTP $responseCode: $errorBody")
                return null
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val translated = extractTranslatedText(body)
            if (!translated.isBlank()) {
                return translated
            }
            Log.w(TAG, "Empty translation result")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}")
            e.printStackTrace()
        }
        return null
    }

    private fun extractTranslatedText(jsonResponse: String): String {
        return try {
            val start = jsonResponse.indexOf("[[\"")
            if (start == -1) return ""
            val afterStart = jsonResponse.substring(start + 3)
            val end = afterStart.indexOf("\",\"")
            if (end == -1) return ""
            unescapeUnicode(afterStart.substring(0, end))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse translation response: ${e.message}")
            ""
        }
    }

    private fun unescapeUnicode(text: String): String =
        Regex("\\\\u([0-9a-fA-F]{4})").replace(text) { matchResult ->
            matchResult.groupValues[1].toInt(16).toChar().toString()
        }

    /**
     * Maps a human language/dialect name onto the closest ML Kit translate code.
     * Every unrecognised or dialect name falls back to Hindi. Codes that ML Kit
     * does not model (e.g. Nepali "ne", Punjabi "pa") are returned as raw ISO codes.
     */
    fun getLanguageCode(language: String): String {
        return when (language.lowercase(Locale.ROOT).trim()) {
            "english" -> TranslateLanguage.ENGLISH
            "gujarati", "bhili" -> TranslateLanguage.GUJARATI
            "kodava", "kannada", "tulu" -> TranslateLanguage.KANNADA
            "kokani", "marathi" -> TranslateLanguage.MARATHI
            "nepali" -> "ne"
            "telugu" -> TranslateLanguage.TELUGU
            "konkani" -> "gom"
            "assamese", "bodo" -> "as"
            "punjabi" -> "pa"
            "bengali", "kokborok" -> TranslateLanguage.BENGALI
            "maithili" -> "mai"
            "mzo", "mizo" -> "lus"
            "garo" -> TranslateLanguage.ENGLISH
            "odia" -> "or"
            "urdu" -> TranslateLanguage.URDU
            "tamil" -> TranslateLanguage.TAMIL
            "kashmiri" -> "ks"
            "santali" -> "sat"
            "malayalam" -> "ml"
            else -> TranslateLanguage.HINDI
        }
    }

    /** Translate a Hinglish/Hindi source string into the user's chosen language. */
    suspend fun translateFromHinglish(text: String, userLanguage: String): String {
        val languageCode = getLanguageCode(userLanguage)
        return if (languageCode == TranslateLanguage.HINDI) text
        else translate(text, TranslateLanguage.HINDI, languageCode)
    }

    /** Alias used by the live session to localise system/assistant messages. */
    suspend fun translateToUserLanguage(text: String, userLanguage: String): String =
        translateFromHinglish(text, userLanguage)
}
