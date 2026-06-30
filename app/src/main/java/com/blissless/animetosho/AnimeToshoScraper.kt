package com.blissless.animetosho

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Kotlin port of the animetosho-scraper Python script.
 *
 * Searches animetosho.org for [animeName], fuzzy-matches each result's
 * series name against the query, and returns the magnet link of the
 * best-matching release.
 *
 * Returns a List<String> (Format 2 — flat magnet list in the Tensei
 * data contract). ScraperProvider serializes this to a JSON array.
 *
 * Uses only Android built-ins (HttpURLConnection + regex) — no OkHttp,
 * Jsoup, or Gson — to keep the APK under ~40 KB after R8.
 *
 * Python original:
 *   def scrape_magnet(base_url, anime):
 *       search_url = f"{base_url.rstrip('/')}/search?q={anime.replace(' ', '+')}"
 *       html = requests.get(search_url, headers=HEADERS).text
 *       soup = BeautifulSoup(html, "html.parser")
 *       ...
 *       for entry in soup.select("div.home_list_entry"):
 *           name  = entry.select_one("span.serieslink a").get_text(strip=True)
 *           score = fuzz.WRatio(anime.lower(), name.lower())
 *           if score > best_score:
 *               magnet_tag = entry.select_one('a[href^="magnet:?"]')   # (typo fixed)
 *               if magnet_tag: best_score, best_magnet = score, magnet_tag["href"]
 */
object AnimeToshoScraper {

    private const val BASE_URL = "https://animetosho.org"

    // A full browser UA is less likely to be challenged than the Python's "Mozilla/5.0".
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Called by ScraperProvider. Mirrors the old Python
     * `scrape_magnet(base_url, anime)` but returns a list so the
     * Main App can display zero, one, or many magnets.
     *
     * @param context    Application context (unused for HTTP; kept for parity
     *                   with the template and a future WebView fallback).
     * @param animeName  Anime title (English or Romaji) from the Main App.
     * @param anilistId  AniList ID (not used by AnimeTosho, but available).
     * @return List of magnet links (may be empty).
     */
    fun scrape(context: Context, animeName: String?, anilistId: String?): Any {
        val anime = animeName?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("No anime name provided")

        val searchUrl = "$BASE_URL/search?q=" + URLEncoder.encode(anime, "UTF-8")
        val html = fetchHtml(searchUrl)
        return parseBestMagnet(html, anime)
    }

    // ---- Networking (replaces `requests.get`) -------------------------------

    private fun fetchHtml(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            throw RuntimeException("HTTP $code from AnimeTosho")
        } finally {
            conn.disconnect()
        }
    }

    // ---- Parsing (replaces BeautifulSoup selectors) -------------------------

    /**
     * Mirror of the Python loop:
     *   for entry in soup.select("div.home_list_entry"):
     *       name  = entry.select_one("span.serieslink a").text
     *       score = fuzz.WRatio(anime, name)
     *       keep best score whose entry has a magnet anchor
     *
     * Jsoup isn't allowed, so we split the page into per-entry regions on the
     * `home_list_entry` class marker (robust to nested divs) and regex out the
     * series name + magnet href inside each region.
     */
    private fun parseBestMagnet(html: String, anime: String): List<String> {
        val regions = splitByMarker(html, "home_list_entry")
        if (regions.isEmpty()) return emptyList()

        var bestScore = 0
        var bestMagnet: String? = null
        val animeLower = anime.lowercase()

        val nameRegex = Regex(
            """class="[^"]*serieslink[^"]*"[^>]*>\s*<a[^>]*>(.*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        val magnetRegex = Regex(
            """href="(magnet:[^"]+)"""",
            RegexOption.IGNORE_CASE
        )

        for (region in regions) {
            val name = nameRegex.find(region)?.groupValues?.get(1)
                ?.let { decodeEntities(stripTags(it)).trim() } ?: continue

            val score = wRatio(animeLower, name.lowercase())
            if (score > bestScore) {
                val magnet = magnetRegex.find(region)?.groupValues?.get(1)
                if (magnet != null) {
                    bestScore = score
                    bestMagnet = magnet
                }
            }
        }
        return bestMagnet?.let { listOf(it) } ?: emptyList()
    }

    /** Split [html] into chunks each starting at a [marker] class occurrence. */
    private fun splitByMarker(html: String, marker: String): List<String> {
        val starts = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = html.indexOf(marker, from)
            if (i < 0) break
            starts.add(i)
            from = i + marker.length
        }
        if (starts.isEmpty()) return emptyList()
        return (0 until starts.size).map { k ->
            val s = starts[k]
            val e = if (k + 1 < starts.size) starts[k + 1] else html.length
            html.substring(s, e)
        }
    }

    private fun stripTags(s: String): String = Regex("<[^>]+>").replace(s, "")

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    // ---- Fuzzy matching (replaces rapidfuzz.fuzz.WRatio) --------------------

    /**
     * Approximation of rapidfuzz's WRatio on a 0–100 scale. WRatio blends
     * full-string and partial-string ratios; we take the max of a Levenshtein
     * ratio and a sliding-window partial ratio — the component that matters
     * most when matching a title against a release name like
     * "Blue Lock S2 [1080p]".
     */
    private fun wRatio(a: String, b: String): Int {
        val s1 = a.trim()
        val s2 = b.trim()
        if (s1.isEmpty() && s2.isEmpty()) return 100
        if (s1.isEmpty() || s2.isEmpty()) return 0
        return maxOf(ratio(s1, s2), partialRatio(s1, s2))
    }

    private fun ratio(a: String, b: String): Int {
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return if (maxLen == 0) 100
        else (((maxLen - dist).toDouble() / maxLen) * 100).toInt()
    }

    private fun partialRatio(a: String, b: String): Int {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.isEmpty()) return if (long.isEmpty()) 100 else 0
        var best = 0
        var i = 0
        while (i + short.length <= long.length) {
            val window = long.substring(i, i + short.length)
            val dist = levenshtein(short, window)
            val score = (((short.length - dist).toDouble() / short.length) * 100).toInt()
            if (score > best) best = score
            i++
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            for (j in 0..m) prev[j] = curr[j]
        }
        return prev[m]
    }
}