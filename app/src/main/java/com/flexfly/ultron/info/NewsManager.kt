package com.flexfly.ultron.info

import android.util.Log
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Fetches and formats news headlines from public RSS/Atom feeds.
 *
 * [getNews] fans out across the feeds for a category (concurrently when [getNews]'s
 * `multiSource` is set), parses each with [parseRss], de-duplicates by title, and
 * delivers a formatted Hinglish report on the main thread.
 *
 * Recovered from `NewsManager.kt`.
 */
object NewsManager {

    private const val TAG = "NewsManager"
    private const val MAX_TITLE_LEN = 120
    private const val MAX_DESC_LEN = 180

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // RECOVERY NOTE: the "uk" key was decompiled as TranslateLanguage.UKRAINIAN
    // (whose value is "uk") — restored to the literal.
    private val RSS_FEEDS: Map<String, List<String>> = mapOf(
        "india" to listOf(
            "https://feeds.bbci.co.uk/news/world/asia/india/rss.xml",
            "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",
            "https://www.thehindu.com/news/national/feeder/default.rss",
        ),
        "usa" to listOf(
            "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml",
            "https://rss.nytimes.com/services/xml/rss/nyt/US.xml",
            "https://feeds.npr.org/1001/rss.xml",
        ),
        "uk" to listOf(
            "https://feeds.bbci.co.uk/news/uk/rss.xml",
            "https://www.theguardian.com/uk/rss",
        ),
        "australia" to listOf(
            "https://feeds.bbci.co.uk/news/world/australia/rss.xml",
            "https://www.abc.net.au/news/feed/51120/rss.xml",
        ),
        "canada" to listOf(
            "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml",
            "https://www.cbc.ca/cmlink/rss-topstories",
        ),
        "world" to listOf(
            "https://feeds.bbci.co.uk/news/world/rss.xml",
            "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
        ),
        "technology" to listOf(
            "https://feeds.bbci.co.uk/news/technology/rss.xml",
            "https://feeds.feedburner.com/TechCrunch",
            "https://www.wired.com/feed/rss",
        ),
        "business" to listOf(
            "https://feeds.bbci.co.uk/news/business/rss.xml",
            "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml",
        ),
        "sports" to listOf(
            "https://feeds.bbci.co.uk/sport/rss.xml",
            "https://www.espn.com/espn/rss/news",
        ),
        "entertainment" to listOf(
            "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml",
            "https://variety.com/feed/",
        ),
        "science" to listOf(
            "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
            "https://www.sciencedaily.com/rss/top/science.xml",
        ),
        "health" to listOf(
            "https://feeds.bbci.co.uk/news/health/rss.xml",
            "https://rss.nytimes.com/services/xml/rss/nyt/Health.xml",
        ),
        "crypto" to listOf(
            "https://cointelegraph.com/rss",
            "https://coindesk.com/arc/outboundfeeds/rss/",
        ),
    )

    /** A single parsed news article. */
    data class NewsItem(
        val title: String,
        val description: String,
        val link: String,
        val source: String,
        val date: String,
    )

    /**
     * Fetches up to [topN] headlines for [category] and delivers a formatted report to
     * [callback] on the main thread. When [multiSource] is false only the first feed is used.
     */
    fun getNews(
        category: String = "india",
        topN: Int = 5,
        multiSource: Boolean = true,
        callback: (String) -> Unit,
    ) {
        val cat = category.lowercase(Locale.ROOT).trim()
        if (!RSS_FEEDS.containsKey(cat)) {
            callback(
                "Boss, '$category' category nahi mila. Available: " +
                    RSS_FEEDS.keys.sorted().joinToString(", ")
            )
            return
        }
        val clampedN = topN.coerceIn(1, 15)
        var feeds = RSS_FEEDS.getValue(cat)
        if (!multiSource) {
            feeds = listOf(feeds.first())
        }

        CoroutineScope(Dispatchers.IO).launch {
            val startMs = System.currentTimeMillis()
            val results = feeds.map { url ->
                async { fetchFeed(url, clampedN + 5) }
            }.awaitAll()
            val fetchMs = System.currentTimeMillis() - startMs

            val allItems = ArrayList<NewsItem>()
            var sourcesHit = 0
            for (list in results) {
                if (list.isNotEmpty()) {
                    allItems.addAll(list)
                    sourcesHit++
                }
            }
            val unique = deduplicate(allItems)
            // RECOVERY NOTE: "ms" suffix was decompiled as TranslateLanguage.MALAY
            // (value "ms") — restored to the literal.
            Log.d(TAG, "✅ ${unique.size} unique articles from $sourcesHit source(s) in ${fetchMs}ms")
            val report = formatReport(cat, unique, clampedN, fetchMs, sourcesHit)
            withContext(Dispatchers.Main) {
                callback(report)
            }
        }
    }

    /** GETs [url] and parses it into news items, or an empty list on any failure. */
    private fun fetchFeed(url: String, topN: Int): List<NewsItem> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 Ultron-Android-RSS")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            response.body?.string()?.let { parseRss(it, url, topN) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Feed fetch failed: $url — ${e.message}")
            emptyList()
        }
    }

    /** Streams the RSS/Atom [xml], collecting up to [topN] items. */
    private fun parseRss(xml: String, feedUrl: String, topN: Int): List<NewsItem> {
        val items = ArrayList<NewsItem>()
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val title = StringBuilder()
            val description = StringBuilder()
            val link = StringBuilder()
            val dateBuf = StringBuilder()

            var eventType = parser.eventType
            var source = feedUrl
            var inItem = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (items.size >= topN) return items
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        if (currentTag == "item" || currentTag == "entry") {
                            title.clear()
                            description.clear()
                            link.clear()
                            dateBuf.clear()
                            inItem = true
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        if ((name == "item" || name == "entry") && inItem) {
                            val rawTitle = title.toString().trim()
                            if (rawTitle.isNotBlank()) {
                                items.add(
                                    NewsItem(
                                        title = cleanText(rawTitle, MAX_TITLE_LEN),
                                        description = cleanText(description.toString(), MAX_DESC_LEN),
                                        link = link.toString().trim(),
                                        source = source,
                                        date = formatDate(dateBuf.toString().trim()),
                                    )
                                )
                            }
                            inItem = false
                        }
                        if (!inItem) {
                            currentTag = ""
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            if (!inItem && currentTag == "title") {
                                source = text
                            } else if (inItem) {
                                when (currentTag) {
                                    "title" -> title.append(text)
                                    "summary", "description" -> description.append(text)
                                    "link" -> link.append(text)
                                    "pubDate", "updated", "dc:date", "published" -> dateBuf.append(text)
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            items
        } catch (e: Exception) {
            Log.w(TAG, "RSS parse error for $feedUrl: ${e.message}")
            items
        }
    }

    /** Strips HTML tags and truncates to [maxLen], adding an ellipsis when cut. */
    private fun cleanText(text: String, maxLen: Int): String {
        val stripped = Regex("<[^>]+>").replace(text, "").trim()
        return if (stripped.length <= maxLen) stripped else stripped.substring(0, maxLen) + "…"
    }

    /** Parses common RSS/Atom date formats into "dd MMM yyyy, hh:mm a", or a best-effort fallback. */
    private fun formatDate(raw: String): String {
        if (raw.isBlank()) return "Date unknown"
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd HH:mm:ss",
        )
        val output = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        for (pattern in patterns) {
            try {
                val parsed = SimpleDateFormat(pattern, Locale.ENGLISH).parse(raw)
                if (parsed != null) {
                    return output.format(parsed)
                }
            } catch (_: Exception) {
            }
        }
        return raw.take(20)
    }

    /** Drops items whose first 40 title chars (lowercased) collide with an earlier item. */
    private fun deduplicate(items: List<NewsItem>): List<NewsItem> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<NewsItem>()
        for (item in items) {
            val key = item.title.take(40).lowercase(Locale.ROOT)
            if (seen.add(key)) {
                result.add(item)
            }
        }
        return result
    }

    /** Builds the human-facing Hinglish headline report. */
    private fun formatReport(
        category: String,
        items: List<NewsItem>,
        topN: Int,
        fetchMs: Long,
        sourcesHit: Int,
    ): String {
        if (items.isEmpty()) {
            return "Boss, '$category' ke liye koi news nahi mili.\n" +
                "Check karein: internet on hai? Ya RSS feeds temporarily down ho sakti hain."
        }
        val now = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val divider = "─".repeat(55)
        val sb = StringBuilder()
        sb.append("📰 ${category.uppercase(Locale.ROOT)} — Top ${minOf(topN, items.size)} Headlines").append('\n')
        sb.append("   🕐 ${fetchMs}ms mein fetch hua, $sourcesHit source(s) se  |  🗓 $now").append('\n')
        sb.append(divider).append('\n')
        items.take(topN).forEachIndexed { index, item ->
            sb.append('\n')
            sb.append("${index + 1}. ${item.title}").append('\n')
            if (item.description.isNotBlank() && item.description != item.title) {
                sb.append("   📝 ${item.description}").append('\n')
            }
            sb.append("   📅 ${item.date}  |  🗞 ${item.source}").append('\n')
            if (item.link.isNotBlank()) {
                sb.append("   🔗 ${item.link}").append('\n')
            }
        }
        sb.append('\n')
        sb.append(divider).append('\n')
        sb.append("✅ ${items.size} unique articles fetched from $sourcesHit feed(s).")
        return sb.toString()
    }
}
