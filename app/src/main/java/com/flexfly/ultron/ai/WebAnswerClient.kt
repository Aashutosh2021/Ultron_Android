package com.flexfly.ultron.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live web-search helper. Answers "search this" / "what's the latest on X" questions
 * with real-time Google Search grounding, over a plain HTTPS `generateContent` call —
 * deliberately NOT the Live WebSocket. This is a second, independent network call that
 * runs off the audio dispatcher so the ongoing voice conversation is never blocked.
 *
 * Why REST and not a Live-native grounding config: server-side Live grounding behaviour
 * has been reported as inconsistent, so this routes through the stable `generateContent`
 * endpoint with the `google_search` tool, which returns `groundingMetadata` citations.
 *
 * Mirrors [CameraVisionManager]'s shape: an `object` singleton, work launched on an IO
 * [scope], results/errors delivered through `onResult`/`onError` callbacks, every network
 * path wrapped so a failure returns a spoken-friendly message instead of throwing.
 *
 * MODEL NOTE: [MODEL] is the text `generateContent` model, NOT the Live audio model in
 * GeminiLiveSession. The `google_search` tool is only served by text models; the Live
 * native-audio preview model rejects a plain generateContent+google_search request.
 * `gemini-2.5-flash` bills per prompt (Gemini 3.x bills per executed search query) — the
 * per-prompt model is the safer default under the project's hard cost cap. Verified
 * against ai.google.dev/gemini-api/docs/google-search (generateContent variant).
 * Change [MODEL] in one place if Google shifts the current fast model name.
 */
object WebAnswerClient {

    private const val TAG = "WebAnswerClient"

    // Text model that serves the google_search grounding tool over generateContent.
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Runs a grounded web search for [query] using [apiKey] (the same GEMINI key as the
     * Live session). Delivers a short spoken-friendly answer to [onResult], or a plain
     * failure line to [onError] — never throws into the caller's coroutine scope.
     */
    fun search(
        apiKey: String,
        query: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (apiKey.isBlank()) {
            onError("Boss, Gemini API key missing hai! Settings mein jaakar setup karo.")
            return
        }
        if (query.isBlank()) {
            onError("Boss, kya search karna hai batao.")
            return
        }
        scope.launch {
            try {
                val answer = callSearchApi(apiKey, query)
                if (answer.isNullOrBlank()) {
                    onError("Boss, search se kuch clear jawaab nahi mila. Dobara try karun?")
                } else {
                    onResult(answer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Web search failed: ${e.message}", e)
                onError("Boss, web search fail ho gayi: ${e.message ?: "network error"}.")
            }
        }
    }

    /**
     * POSTs [query] to the generateContent endpoint with the google_search tool enabled,
     * returns the answer text. Citations from `groundingMetadata` are logged (and NOT
     * spoken) so Ultron doesn't read URLs aloud. Throws on HTTP / parse failure; the
     * caller in [search] converts that into an onError message.
     */
    private fun callSearchApi(apiKey: String, query: String): String? {
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put(
                            "text",
                            "Answer this using current web information, concisely and " +
                                "conversationally for a spoken reply. No markdown, no bullet " +
                                "points, no URLs in the answer. Question: $query",
                        )
                    }))
                })
            })
            // Enable Google Search grounding (current models use the `google_search` tool).
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            // Docs recommend temperature 1.0 for ideal grounded results.
            put("generationConfig", JSONObject().put("temperature", 1.0))
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            Log.d(TAG, "generateContent → HTTP ${response.code}")
            if (!response.isSuccessful) {
                Log.e(TAG, "Search error body: $bodyStr")
                val reason = when (response.code) {
                    400 -> "Bad request (model/tool mismatch?)"
                    401, 403 -> "Invalid API key ya access nahi hai"
                    404 -> "Model not found"
                    429 -> "Rate limited — thodi der baad try karo"
                    500, 502, 503 -> "Google server down"
                    else -> "HTTP ${response.code}"
                }
                throw Exception(reason)
            }

            val json = JSONObject(bodyStr)
            if (json.has("error")) {
                throw Exception(json.getJSONObject("error").optString("message", "API error"))
            }

            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val answer = candidate
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.let { parts ->
                    buildString {
                        for (i in 0 until parts.length()) {
                            val t = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                            if (t.isNotBlank()) append(t)
                        }
                    }.trim()
                }

            logCitations(candidate?.optJSONObject("groundingMetadata"))

            return answer?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Extracts source citations from `groundingMetadata.groundingChunks[].web` and logs
     * them. Intentionally NOT surfaced to the spoken reply — logged/stored only, per spec.
     */
    private fun logCitations(groundingMetadata: JSONObject?) {
        if (groundingMetadata == null) {
            Log.d(TAG, "No groundingMetadata (answer may be ungrounded)")
            return
        }
        val queries = groundingMetadata.optJSONArray("webSearchQueries")
        if (queries != null && queries.length() > 0) {
            Log.d(TAG, "🔎 Search queries: ${queries.join(", ")}")
        }
        val chunks = groundingMetadata.optJSONArray("groundingChunks") ?: return
        for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val title = web.optString("title", "?")
            val uri = web.optString("uri", "?")
            Log.d(TAG, "📎 Source ${i + 1}: $title — $uri")
        }
    }
}
