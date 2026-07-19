package com.flexfly.ultron.comms

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.flexfly.ultron.data.AppPreferences
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * AI-level contact resolution: fuzzy name matching (with Devanagari transliteration
 * and relationship aliases), favourite/recency/frequency scoring, de-duplication, and
 * call/SMS helpers.
 *
 * Recovered from `ContactManager.kt` (folding in the `loadAllContacts`,
 * `searchContactsAsync`, `searchContacts`, `resolveAndCall`, and `resolveAndCallAsync`
 * coroutine state machines).
 *
 * CROSS-FILE REFS TO VERIFY:
 *  - [AppPreferences] / `AppPreferences.FavoriteContact` → `com.flexfly.ultron.data`
 *
 * RECOVERY NOTE: several regex/constant strings were substituted by the decompiler:
 *   - `TranslateLanguage.THAI` == "th" (used as a transliteration/normalisation token)
 *   - the 24-hour divisor `CalendarModelKt.MillisecondsIn24Hours` == 86_400_000L
 *   - cursor projection columns (`display_name`, `data1`, `data2`, `starred`,
 *     `times_contacted`, `last_time_contacted`) are the real ContactsContract keys.
 *   - `calculateFinalScore`'s body was lost to a decompiler restructure failure and is
 *     rebuilt from the `JADX WARN ... code lost` fragments; see inline notes.
 */
object ContactManager {

    private const val TAG = "ContactManager"
    private const val CACHE_VALIDITY_MS = 30000L

    // RECOVERY NOTE: androidx.compose.material3.internal.CalendarModelKt.MillisecondsIn24Hours
    private const val MILLISECONDS_IN_24_HOURS = 86_400_000L

    private var cachedContacts: List<RawContact>? = null
    private var cacheTimestamp: Long = 0L

    private val relationshipMap: Map<String, List<String>> = mapOf(
        "papa" to listOf("papa", "dad", "father", "pita", "pitaji", "babu", "baba"),
        "mummy" to listOf("mummy", "mom", "mother", "maa", "mata", "amma", "mumma"),
        "bhai" to listOf("bhai", "brother", "bhaiya", "bro", "bhaiyya"),
        "behen" to listOf("behen", "sister", "didi", "sis", "behna"),
        "wife" to listOf("wife", "patni", "ghar", "gharwali", "jaan"),
        "husband" to listOf("husband", "pati", "hubby"),
        "dada" to listOf("dada", "grandfather", "grandpa", "dadaji"),
        "dadi" to listOf("dadi", "grandmother", "grandma", "dadiji"),
        "nana" to listOf("nana", "nanaji"),
        "nani" to listOf("nani", "naniji"),
        "chacha" to listOf("chacha", "uncle", "kaka", "chachaji"),
        "chachi" to listOf("chachi", "aunty", "aunt", "chachiji"),
        "mausa" to listOf("mausa", "mausaji", "mama"),
        "mausi" to listOf("mausi", "mausiji", "mami"),
        "beta" to listOf("beta", "son", "puttar"),
        "beti" to listOf("beti", "daughter", "bitti"),
    )

    /** A resolved contact surfaced to callers (name + number are the load-bearing fields). */
    data class Contact(
        val name: String,
        val number: String,
        val label: String = "",
        val isFavorite: Boolean = false,
        val timesContacted: Int = 0,
        val lastContactTime: Long = 0L,
        val bestFuzzyScore: Double = 0.0,
    )

    /** A raw ContactsContract row before scoring/de-duplication. */
    private data class RawContact(
        val name: String,
        val number: String,
        val label: String,
        val starred: Boolean,
        val timesContacted: Int,
        val lastContactTime: Long,
    )

    /** Outcome of [resolveAndCall] / [resolveAndCallAsync]. */
    sealed class CallResult {
        data class Calling(val contact: Contact) : CallResult()
        data class Ambiguous(val contacts: List<Contact>) : CallResult()
        data class NotFound(val query: String) : CallResult()
    }

    private fun cleanName(name: String): String {
        return Regex("[\\p{So}\\p{Sk}\\p{Cn}]").replace(name, "")
            .let { Regex("[^\\p{L}\\p{N}\\s\\u0900-\\u097F]").replace(it, "") }
            .let { Regex("\\s+").replace(it, " ") }
            .trim()
    }

    private fun transliterateToEnglish(text: String): String {
        val consonants = mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
            'ष' to "sh", 'स' to "s", 'ह' to "h",
        )
        val vowels = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u",
            'ऊ' to "oo", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        )
        val matras = mapOf(
            'ा' to "a", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
            'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
            'ं' to "n", 'ः' to "h", '्' to "",
        )
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                consonants.containsKey(c) -> {
                    sb.append(consonants[c])
                    val next = i + 1
                    if (next < text.length && matras.containsKey(text[next])) {
                        sb.append(matras[text[next]])
                        i = next
                    }
                }
                vowels.containsKey(c) -> sb.append(vowels[c])
                c.isLetterOrDigit() || c.isWhitespace() -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun normalize(text: String): String {
        var result = Regex("\\s+").replace(text.lowercase(Locale.ROOT), "")
        for (c in result) {
            if (c.code in 2304..2431) { // RECOVERY NOTE: 2304 <= c < 2432 (Devanagari block)
                result = transliterateToEnglish(result)
                break
            }
        }
        return result
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("kh", "k")
            .replace("ch", "k")
            .replace("th", "t") // RECOVERY NOTE: TranslateLanguage.THAI == "th"
            .replace("ee", "i")
            .replace("ea", "i")
            .replace("ey", "i")
            .replace("ei", "i")
            .replace("ie", "i")
            .replace("oo", "u")
            .replace("ou", "u")
            .replace("aa", "a")
            .let { Regex("([a-z])\\1+").replace(it, "$1") }
            .let { Regex("[^a-z0-9]").replace(it, "") }
    }

    private fun normalizeNumber(number: String): String {
        val cleaned = Regex("[^0-9+]").replace(number, "")
        return when {
            cleaned.startsWith("+91") -> cleaned.substring(1)
            cleaned.startsWith("0") && cleaned.length == 11 -> "91" + cleaned.substring(1)
            cleaned.length == 10 -> "91$cleaned"
            cleaned.startsWith("91") && cleaned.length == 12 -> cleaned
            else -> cleaned.removePrefix("+")
        }
    }

    private fun fuzzyMatch(query: String, target: String, forceful: Boolean = false): Double {
        val q = normalize(query)
        val t = normalize(target)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0

        val ratio = q.length.toDouble() / t.length.toDouble()
        if (t.contains(q)) {
            return when {
                ratio >= 0.7 -> 0.98
                ratio >= 0.5 -> 0.92
                ratio >= 0.4 -> 0.85
                else -> 0.72
            }
        }
        if (t.startsWith(q)) {
            return when {
                ratio >= 0.7 -> 0.95
                ratio >= 0.5 -> 0.88
                else -> 0.78
            }
        }
        for (word in Regex("\\s+").split(target.lowercase(Locale.ROOT))) {
            if (normalize(word).startsWith(q)) {
                return if (q.length.toDouble() / normalize(word).length.toDouble() >= 0.6) 0.85 else 0.72
            }
        }
        val similarity = 1.0 - (levenshteinDistance(q, t).toDouble() / maxOf(q.length, t.length).toDouble())
        val threshold = if (forceful) 0.5 else 0.75
        return if (similarity >= threshold) similarity else 0.0
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    minOf(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost),
                )
            }
        }
        return dp[len1][len2]
    }

    private fun matchRelationship(query: String, contactName: String): Double {
        val q = query.lowercase(Locale.ROOT)
        val name = contactName.lowercase(Locale.ROOT)
        for ((_, aliases) in relationshipMap) {
            if (aliases.any { q.contains(it) } && aliases.any { name.contains(it) }) {
                return 0.95
            }
        }
        return 0.0
    }

    private suspend fun loadAllContacts(context: Context): List<RawContact> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = cachedContacts
            if (cached != null && now - cacheTimestamp < CACHE_VALIDITY_MS) {
                Log.d(TAG, "📦 Using cached contacts (${cached.size})")
                return@withContext cached
            }
            val result = ArrayList<RawContact>()
            try {
                val cursor: Cursor? = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        "display_name",         // RECOVERY NOTE: Phone.DISPLAY_NAME
                        "data1",                // RECOVERY NOTE: Phone.NUMBER
                        "data2",                // RECOVERY NOTE: Phone.TYPE
                        "starred",              // RECOVERY NOTE: Contacts.STARRED
                        "times_contacted",      // RECOVERY NOTE: Contacts.TIMES_CONTACTED
                        "last_time_contacted",  // RECOVERY NOTE: Contacts.LAST_TIME_CONTACTED
                    ),
                    null, null, null,
                )
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val rawName = c.getString(0) ?: continue
                        val number = c.getString(1) ?: continue
                        val type = c.getInt(2)
                        val starred = c.getInt(3) == 1
                        val timesContacted = c.getInt(4)
                        val lastContactTime = c.getLong(5)
                        val name = cleanName(rawName)
                        if (name.isEmpty()) continue
                        val label = when (type) {
                            1 -> "Home"   // RECOVERY NOTE: Phone.TYPE_HOME
                            2 -> "Mobile" // RECOVERY NOTE: Phone.TYPE_MOBILE
                            3 -> "Work"   // RECOVERY NOTE: Phone.TYPE_WORK
                            else -> "Other"
                        }
                        result.add(RawContact(name, number, label, starred, timesContacted, lastContactTime))
                    }
                }
                cachedContacts = result
                cacheTimestamp = now
                Log.d(TAG, "✅ Loaded ${result.size} contacts (cached)")
                result
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading contacts: ${e.message}")
                result
            }
        }

    /**
     * RECOVERY NOTE: body rebuilt from the `JADX WARN ... code lost` fragments — the
     * decompiler dropped the method body but preserved the arithmetic in comments.
     * Scoring: base*100, +relationship*100, +50 if favourite-matched, +50 if starred,
     * a recency bonus (30/20/10 by days since last contact), a frequency bonus
     * (25/15/10/5 by times contacted), +5 for a "Mobile" label, and +name.length*0.3.
     */
    private fun calculateFinalScore(
        baseScore: Double,
        contact: RawContact,
        favorites: List<AppPreferences.FavoriteContact>,
        relationshipBonus: Double,
    ): Double {
        var d = baseScore * 100.0
        if (relationshipBonus > 0.0) {
            d += relationshipBonus * 100.0
        }
        if (favorites.any { normalizeNumber(it.number) == normalizeNumber(contact.number) }) {
            d += 50.0
        }
        if (contact.starred) {
            d += 50.0
        }
        val daysSince = (System.currentTimeMillis() - contact.lastContactTime) / MILLISECONDS_IN_24_HOURS
        d += when {
            daysSince < 1 -> 30.0
            daysSince < 3 -> 20.0
            daysSince < 7 -> 10.0
            else -> 0.0
        }
        d += when {
            contact.timesContacted > 100 -> 25.0
            contact.timesContacted > 50 -> 15.0
            contact.timesContacted > 20 -> 10.0
            contact.timesContacted > 10 -> 5.0
            else -> 0.0
        }
        if (contact.label == "Mobile") {
            d += 5.0
        }
        return d + (contact.name.length * 0.3)
    }

    private fun deduplicateContacts(
        contacts: List<Pair<Contact, Double>>,
    ): List<Pair<Contact, Double>> {
        if (contacts.isEmpty()) return emptyList()
        val byNumber = LinkedHashMap<String, MutableList<Pair<Contact, Double>>>()
        for (entry in contacts) {
            val key = normalizeNumber(entry.first.number)
            byNumber.getOrPut(key) { ArrayList() }.add(entry)
        }
        // Keep, per number, the entry with the highest (score + favourite bonus +
        // name.length*0.5 + timesContacted*0.1) tiebreaker.
        return byNumber.values.map { group ->
            group.maxByOrNull { (contact, score) ->
                var s = score
                if (contact.isFavorite) s += 50.0
                s + (contact.name.length * 0.5) + (contact.timesContacted * 0.1)
            }!!
        }
    }

    suspend fun searchContactsAsync(context: Context, query: String): List<Contact> =
        withContext(Dispatchers.Default) {
            val normalResults = searchContactsInternal(context, query, forceful = false)
            if (normalResults.isNotEmpty()) {
                return@withContext normalResults
            }
            Log.d(TAG, "🔍 Standard search returned empty, trying FORCEFUL fallback...")
            searchContactsInternal(context, query, forceful = true)
        }

    private suspend fun searchContactsInternal(
        context: Context,
        query: String,
        forceful: Boolean
    ): List<Contact> =
        withContext(Dispatchers.Default) {
            val prefs = AppPreferences(context)
            val favorites = prefs.getFavoriteContacts()
            val cleanedQuery = cleanName(query.trim())
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d(TAG, "🤖 AI-LEVEL SEARCH (forceful=$forceful)")
            Log.d(TAG, "Query: '$query' → '$cleanedQuery'")
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            if (cleanedQuery.isEmpty()) return@withContext emptyList()

            val scored = ArrayList<Pair<Contact, Double>>()
            for (raw in loadAllContacts(context)) {
                val fullNameScore = fuzzyMatch(cleanedQuery, raw.name, forceful)
                val firstName = raw.name.split(" ").firstOrNull() ?: raw.name
                val firstNameScore = fuzzyMatch(cleanedQuery, firstName, forceful)
                val relationshipScore = matchRelationship(cleanedQuery, raw.name)

                // Nickname match against favorites
                val matchedFavorite = favorites.firstOrNull { 
                    normalizeNumber(it.number) == normalizeNumber(raw.number) && 
                    it.label.isNotBlank() 
                }
                val nicknameScore = if (matchedFavorite != null) {
                    fuzzyMatch(cleanedQuery, matchedFavorite.label, forceful)
                } else {
                     0.0
                }

                val bestScore = maxOf(fullNameScore, maxOf(firstNameScore * 0.95, maxOf(relationshipScore, nicknameScore * 1.5)))
                if (bestScore > 0.0) {
                    val isFavorite = favorites.any {
                        normalizeNumber(it.number) == normalizeNumber(raw.number)
                    } || raw.starred
                    val contact = Contact(
                        raw.name, raw.number, raw.label, isFavorite,
                        raw.timesContacted, raw.lastContactTime,
                        bestFuzzyScore = bestScore
                    )
                    var finalScore = calculateFinalScore(bestScore, raw, favorites, relationshipScore)
                    if (nicknameScore >= 0.8) {
                        finalScore += 150.0 // Give massive priority to matching nickname
                    }
                    scored.add(contact to finalScore)
                }
            }

            val sorted = deduplicateContacts(scored).sortedByDescending { it.second }
            val results: List<Contact> = if (sorted.isNotEmpty()) {
                val top = sorted[0].first
                val topScore = sorted[0].second
                Log.d(TAG, "👑 TOP: '${top.name}' | score=${String.format("%.2f", topScore)} | fuzzyScore=${top.bestFuzzyScore}")
                if (sorted.size == 1) {
                    listOf(top)
                } else {
                    val second = sorted[1].first
                    val gap = topScore - sorted[1].second
                    val topExact = normalize(top.name) == normalize(cleanedQuery)
                    val secondExact = normalize(second.name) == normalize(cleanedQuery)
                    if (!top.name.equals(second.name, ignoreCase = true) &&
                        ((topExact && !secondExact && gap >= 5.0) || gap >= 15.0)
                    ) {
                        Log.d(TAG, "✅ CLEAR WINNER: '${top.name}'")
                        listOf(top)
                    } else {
                        val ambiguous = sorted
                            .filter { it.second >= topScore - 15.0 }
                            .map { it.first }
                        Log.d(TAG, "⚠️ AMBIGUOUS MATCHES: ${ambiguous.size}")
                        ambiguous.take(5)
                    }
                }
            } else {
                emptyList()
            }

            Log.d(TAG, "━━━ RESULTS: ${results.size} ━━━")
            results.forEachIndexed { index, contact ->
                Log.d(
                    TAG,
                    "${index + 1}. ${if (contact.isFavorite) "⭐" else "  "} '${contact.name}' | ${contact.timesContacted} calls",
                )
            }
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            results
        }

    fun searchContacts(context: Context, query: String): List<Contact> = runBlocking {
        searchContactsAsync(context, query)
    }

    fun makeCall(context: Context, number: String) {
        try {
            val intent = Intent("android.intent.action.CALL")
            intent.data = Uri.parse("tel:" + Regex("[^0-9+]").replace(number, ""))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // RECOVERY NOTE: addFlags(268435456)
            context.startActivity(intent)
            Log.d(TAG, "📞 Calling: $number")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Call error: ${e.message}")
        }
    }

    suspend fun resolveAndCallAsync(context: Context, query: String): CallResult {
        val results = searchContactsAsync(context, query)
        // RECOVERY NOTE: the decompiled `if (!list.isEmpty()) return NotFound` was an
        // inverted-condition artifact. Correct flow: empty → NotFound, single → Calling,
        // otherwise → Ambiguous.
        return when {
            results.isEmpty() -> CallResult.NotFound(query)
            results.size == 1 -> {
                makeCall(context, results[0].number)
                CallResult.Calling(results[0])
            }
            else -> CallResult.Ambiguous(results)
        }
    }

    fun resolveAndCall(context: Context, query: String): CallResult = runBlocking {
        resolveAndCallAsync(context, query)
    }

    fun formatContactsForSpeech(contacts: List<Contact>): String {
        val byName = contacts.groupBy { it.name.lowercase(Locale.ROOT) }
        val phrases = contacts.map { contact ->
            val sameName = byName[contact.name.lowercase(Locale.ROOT)] ?: emptyList()
            if (sameName.size > 1) {
                if (contact.label.isNotBlank()) {
                    val labels = sameName.map { it.label.lowercase(Locale.ROOT) }
                    if (labels.distinct().size == sameName.size) {
                        return@map "${contact.name} ${contact.label}"
                    }
                }
                val last4 = Regex("[^0-9]").replace(contact.number, "").takeLast(4)
                if (last4.length == 4) {
                    "${contact.name} jiska number ${last4.chunked(1).joinToString(" ")} pe khatam hota hai"
                } else {
                    "${contact.name} ${contact.label}"
                }
            } else {
                contact.name
            }
        }
        return when (phrases.size) {
            0 -> ""
            1 -> phrases[0]
            2 -> "${phrases[0]} aur ${phrases[1]}"
            3 -> "${phrases[0]}, ${phrases[1]}, aur ${phrases[2]}"
            else -> "${phrases.take(3).joinToString(", ")} — aur ${phrases.size - 3} aur"
        }
    }

    fun formatAmbiguousForSpeech(contacts: List<Contact>): String {
        val options = contacts.take(3).mapIndexed { index, contact ->
            "${index + 1} number pe ${if (contact.isFavorite) "⭐ " else ""}${contact.name}"
        }.joinToString(", ")
        return if (contacts.size > 3) {
            "Kaun sa? $options, ya aur koi"
        } else {
            "Kaun sa? $options"
        }
    }

    fun clearCache() {
        cachedContacts = null
        cacheTimestamp = 0L
        Log.d(TAG, "🗑️ Cache cleared")
    }
}
