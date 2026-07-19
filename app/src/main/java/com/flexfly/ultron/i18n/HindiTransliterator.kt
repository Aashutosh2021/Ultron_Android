package com.flexfly.ultron.i18n

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Devanagari → Latin transliteration for Hindi text.
 *
 * Prefers ML Kit on-device Hindi→English translation once its model is downloaded
 * ([initialize]); falls back to a hand-written phonetic [manualTransliterate] table
 * when the model is unavailable or fails.
 *
 * Recovered from `HindiTransliterator.kt`.
 */
object HindiTransliterator {

    private const val TAG = "HindiTransliterator"

    private var translator: Translator? = null
    private var isModelDownloaded = false

    /** Downloads the ML Kit Hindi→English model if needed. Returns true on success. */
    suspend fun initialize(context: Context): Boolean {
        if (isModelDownloaded) return true

        return suspendCancellableCoroutine { cont ->
            try {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.HINDI)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
                translator = Translation.getClient(options)
                translator?.downloadModelIfNeeded()
                    ?.addOnSuccessListener {
                        Log.d(TAG, "✅ ML Kit model downloaded")
                        isModelDownloaded = true
                        cont.resume(true)
                    }
                    ?.addOnFailureListener { e ->
                        Log.e(TAG, "❌ Model download failed: ${e.message}")
                        cont.resume(false)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Initialization error: ${e.message}")
                cont.resume(false)
            }
        }
    }

    /**
     * Transliterates [hindiText]. Returns the input unchanged if it contains no
     * Devanagari; otherwise uses ML Kit when ready, falling back to [manualTransliterate].
     */
    suspend fun transliterate(hindiText: String): String {
        if (hindiText.isBlank()) return hindiText

        for (c in hindiText) {
            if (c in 'ऀ'..'ॿ') {
                if (!isModelDownloaded || translator == null) {
                    return manualTransliterate(hindiText)
                }
                return suspendCancellableCoroutine { cont ->
                    translator?.translate(hindiText)
                        ?.addOnSuccessListener { result ->
                            Log.d(TAG, "✅ ML Kit: '$hindiText' → '$result'")
                            cont.resume(result)
                        }
                        ?.addOnFailureListener { e ->
                            Log.w(TAG, "ML Kit failed, using fallback: ${e.message}")
                            cont.resume(manualTransliterate(hindiText))
                        }
                }
            }
        }
        return hindiText
    }

    /** Deterministic phonetic fallback: maps Devanagari code points to Latin syllables. */
    fun manualTransliterate(text: String): String {
        // RECOVERY NOTE: several values were decompiled as TranslateLanguage.THAI,
        // whose value is the literal "th" — restored below.
        val consonants = mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh",
            'ङ' to "ng", 'च' to "ch", 'छ' to "chh", 'ज' to "j",
            'झ' to "jh", 'ञ' to "ny", 'ट' to "t", 'ठ' to "th",
            'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
            'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh",
            'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l",
            'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s",
            'ह' to "h",
        )
        val nuktaConsonants = mapOf(
            'क़' to "q", 'ख़' to "kh", 'ग़' to "gh", 'ज़' to "z",
            'ड़' to "d", 'ढ़' to "dh", 'फ़' to "f",
        )
        val independentVowels = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee",
            'उ' to "u", 'ऊ' to "oo", 'ए' to "e", 'ऐ' to "ai",
            'ओ' to "o", 'औ' to "au", 'ऋ' to "ri",
        )
        val matras = mapOf(
            'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u",
            'ू' to "oo", 'े' to "e", 'ै' to "ai", 'ो' to "o",
            'ौ' to "au", 'ं' to "n", 'ः' to "h", '्' to "",
        )

        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                nuktaConsonants.containsKey(c) -> {
                    sb.append(nuktaConsonants[c])
                    val next = i + 1
                    if (next < text.length && matras.containsKey(text[next]) && text[next] != '्') {
                        sb.append(matras[text[next]])
                        i = next
                    }
                }

                consonants.containsKey(c) -> {
                    val base = consonants[c]!!
                    val next = i + 1
                    if (next < text.length && matras.containsKey(text[next])) {
                        val matra = text[next]
                        if (matra == '्') {
                            sb.append(base)
                        } else {
                            sb.append(base).append(matras[matra])
                        }
                        i = next
                    } else {
                        sb.append(base)
                        if (!base.endsWith("h")) {
                            sb.append("a")
                        }
                    }
                }

                independentVowels.containsKey(c) -> sb.append(independentVowels[c])
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Releases the ML Kit translator and resets download state. */
    fun close() {
        translator?.close()
        translator = null
        isModelDownloaded = false
    }
}
