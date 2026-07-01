package com.blissless.animetosho

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Kotlin port of the animetosho-scraper Python script.
 *
 * Searches animetosho.org for [animeName], picks the series that best matches
 * the query, and returns the magnet links of that series' releases.
 *
 * Returns a List<String> (Format 2 — flat magnet list in the Tensei data
 * contract). ScraperProvider serializes this to a JSON array.
 *
 * Uses only Android built-ins (HttpURLConnection + regex) — no OkHttp,
 * Jsoup, or Gson — to keep the APK under ~50 KB after R8.
 *
 * ── Season disambiguation (the S1-vs-S2 bug) ──────────────────────────────
 * A plain search for "Blue Lock" returns THREE series on animetosho:
 *   1. "Blue Lock vs. U-20 Japan"   ← Season 2 (listed FIRST, ~68 releases)
 *   2. "Blue Lock"                   ← Season 1 (the one we want, ~3 releases)
 *   3. "Gekijouban Blue Lock: …"     ← the movie
 *
 * The old matcher used rapidfuzz-style WRatio = max(ratio, partialRatio).
 * partialRatio slides the short query over the longer name and rewards a
 * substring match with 100 — so "blue lock" scored 100 against BOTH
 * "blue lock" (exact) AND "blue lock vs. u-20 japan" (substring). Because S2
 * is listed first and the loop used strict `>`, S2 locked in 100 first and
 * the exact S1 match never replaced it → S2 magnet returned for an S1 search.
 *
 * FIX:
 *   1. Exact (case-insensitive, trimmed) name equality wins instantly and
 *      unbeatably, so "Blue Lock" → the "Blue Lock" series, never S2.
 *   2. Otherwise fall back to the full Levenshtein ratio (NOT partialRatio),
 *      which penalises the length difference ("Blue Lock" vs "Blue Lock vs.
 *      U-20 Japan" scores low). partialRatio is deliberately dropped for
 *      series-name matching because clean series names don't need substring
 *      forgiveness — and that forgiveness is exactly what caused the bug.
 *   3. Tie-break: shorter series name preferred (less extra suffix).
 *
 * ── Parser robustness ─────────────────────────────────────────────────────
 * The old code split the HTML on the literal "home_list_entry" marker, but
 * that string appears many times per entry (class + data attrs + embedded
 * JSON), so the split boundaries rarely spanned a full entry + its magnet.
 * The real DOM pairs each <span class="serieslink"><a>NAME</a></span> with
 * the NEXT <a href="magnet:…"> that follows it (before the next serieslink),
 * ~364 chars apart. This port pairs by proximity, then groups magnets by
 * series — so we return every magnet of the CORRECT series.
 *
 * Python original:
 *   def scrape_magnet(base_url, anime):
 *       search_url = f"{base_url.rstrip('/')}/search?q={anime.replace(' ', '+')}"
 *       html = requests.get(search_url, headers=HEADERS).text
 *       soup = BeautifulSoup(html, "html.parser")
 *       for entry in soup.select("div.home_list_entry"):
 *           name  = entry.select_one("span.serieslink a").get_text(strip=True)
 *           score = fuzz.WRatio(anime.lower(), name.lower())
 *           if score > best_score:
 *               magnet_tag = entry.select_one('a[href^="magnet:?"]')
 *               if magnet_tag: best_score, best_magnet = score, magnet_tag["href"]
 *       return best_magnet
 */
object AnimeToshoScraper {

    private const val BASE_URL = "https://animetosho.org"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Called by ScraperProvider. Mirrors the old Python
     * `scrape_magnet(base_url, anime)` but returns the magnets of the
     * best-matching SERIES (1 or more), not just a single best entry.
     *
     * @param context    Application context (unused for HTTP; kept for parity
     *                   with the template and a future WebView fallback).
     * @param animeName  Anime title (English or Romaji) from the Main App.
     * @param anilistId  AniList ID (not used by AnimeTosho, but available).
     * @return Magnet links of the best-matching series (may be empty).
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

    // ---- Parsing (proximity pairing + group-by-series) ----------------------

    private val seriesRegex = Regex(
        """class="[^"]*serieslink[^"]*"[^>]*>\s*<a[^>]*>(.*?)</a>""",
        RegexOption.IGNORE_CASE
    )
    private val magnetRegex = Regex(
        """href="(magnet:[^"]+)"""",
        RegexOption.IGNORE_CASE
    )
    // animetosho prints per-release stats as:
    //   <span title="Seeders: 41 / Leechers: 4" ...>[41↑/4↓]</span>
    // right after each Magnet link. We pair the span to its magnet by proximity.
    private val seedersRegex = Regex(
        """Seeders:\s*(\d+)\s*/\s*Leechers:\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    private data class SeriesHit(val name: String, val pos: Int)
    private data class Release(val magnet: String, val seeders: Int, val dn: String)

    /**
     * Pairing strategy (corrected):
     *
     * The animetosho search page lists releases by recency, NOT grouped by
     * series — each release row has its OWN <span class="serieslink"> showing
     * that release's series, followed by its Magnet link and a seeder span:
     *
     *   ... <span class="serieslink"><a>Blue Lock</a></span> ... <a href="magnet:…">Magnet</a> ...
     *       <span title="Seeders: 64 / Leechers: 4">[64↑/4↓]</span> ...
     *
     * So for EACH magnet we determine:
     *   - its series  = the NEAREST PRECEDING serieslink (same release row)
     *   - its seeders = the NEAREST FOLLOWING "Seeders: N / Leechers: N" span
     *
     * The old range-based pairing ("magnet belongs to whichever series' range
     * it falls in") was wrong because serieslinks repeat per-row and the
     * ranges overlap — it bucketed everything under the first series.
     *
     * Then:
     *   1. Group releases by series name.
     *   2. Pick the best-matching series (exact equality → unbeatable; sequel-
     *      marker filter; else Levenshtein ratio; tie-break shorter name).
     *   3. From that series' releases, return a SINGLE magnet:
     *        - if the query signals a dub preference (contains "dub"), prefer
     *          dual-audio releases; among those, the most-seeded;
     *        - otherwise the most-seeded release overall;
     *        - seeder ties broken by dual-audio, then by magnet length.
     *
     * The returned magnet is HTML-entity-decoded (&amp; → &); the raw HTML
     * stores "&amp;" between tracker params, which would break the URI if
     * passed through verbatim.
     */
    private fun parseBestMagnet(html: String, anime: String): List<String> {
        val animeLower = anime.trim().lowercase()
        if (animeLower.isEmpty()) return emptyList()

        val seriesHits = seriesRegex.findAll(html).mapNotNull { m ->
            val name = decodeEntities(stripTags(m.groupValues[1])).trim()
            if (name.isNotEmpty()) SeriesHit(name, m.range.first) else null
        }.toList()
        if (seriesHits.isEmpty()) return emptyList()

        val magnets = magnetRegex.findAll(html)
            .map { it.groupValues[1] to it.range.first }
            .toList()
        if (magnets.isEmpty()) return emptyList()

        val seedSpans = seedersRegex.findAll(html)
            .map { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range.first) }
            .toList()

        // Group releases by series, via per-magnet proximity pairing:
        //   series  = nearest preceding serieslink
        //   seeders = nearest following seeder span
        val groups = LinkedHashMap<String, MutableList<Release>>()
        for ((rawMagnet, mp) in magnets) {
            val magnet = decodeEntities(rawMagnet)   // &amp; → &  (critical for valid URIs)
            val seriesName = seriesHits.lastOrNull { it.pos < mp }?.name ?: continue
            val seeders = seedSpans.firstOrNull { (_, _, sp) -> sp > mp }?.first ?: 0
            groups.getOrPut(seriesName) { mutableListOf() }
                .add(Release(magnet, seeders, extractDn(magnet)))
        }
        if (groups.isEmpty()) return emptyList()

        // ---- Pick the best-matching series (season disambiguation) --------
        val queryHasSeq = hasSequelMarker(animeLower)

        // Exact (case-insensitive) equality is an instant, unbeatable win —
        // but only when the query carries no sequel marker. A bare "Blue Lock"
        // should hit the bare "Blue Lock" series, never a sequel series that
        // merely contains it as a prefix.
        if (!queryHasSeq) {
            for (name in groups.keys) {
                if (name.lowercase() == animeLower) {
                    return pickBestRelease(groups[name]!!, animeLower)
                }
            }
        }

        // Otherwise restrict candidates to series whose sequel-ness matches
        // the query's, so "Blue Lock Season 2" only competes among sequel
        // series ("Blue Lock vs. U-20 Japan"), never the bare S1 "Blue Lock".
        // Falls back to all series if the same-class set is empty.
        var candidates = groups.keys.filter { hasSequelMarker(it.lowercase()) == queryHasSeq }
        if (candidates.isEmpty()) candidates = groups.keys.toList()

        var bestName: String? = null
        var bestScore = -1
        var bestLen = Int.MAX_VALUE
        for (name in candidates) {
            val score = ratio(animeLower, name.lowercase())
            val better = score > bestScore || (score == bestScore && name.length < bestLen)
            if (better) {
                bestScore = score
                bestName = name
                bestLen = name.length
            }
        }

        val winner = bestName ?: return emptyList()
        return pickBestRelease(groups[winner]!!, animeLower)
    }

    /**
     * Select a single magnet from a series' releases.
     *
     * Policy (user-requested):
     *  - If the query signals a dub preference (contains "dub"), prefer
     *    dual-audio releases — pick the most-seeded among those.
     *  - Otherwise pick the most-seeded release overall.
     *  - Ties on seeders: prefer dual-audio, then the shorter magnet (fewer
     *    trackers = cleaner link).
     *
     * Returns a one-element list (or empty if the series had no releases).
     */
    private fun pickBestRelease(releases: List<Release>, queryLower: String): List<String> {
        if (releases.isEmpty()) return emptyList()

        val preferDub = queryLower.contains("dub")

        val pool = if (preferDub) {
            // Prefer dual-audio/dub releases. If the query asked for dub but
            // none are dual-audio, fall back to all (don't return nothing).
            val dual = releases.filter { isDualAudio(it.dn) }
            if (dual.isNotEmpty()) dual else releases
        } else {
            releases
        }

        val best = pool.maxWithOrNull(
            compareByDescending<Release> { it.seeders }
                .thenByDescending { isDualAudio(it.dn) }   // ties → prefer dual-audio
                .thenBy { it.magnet.length }               // then shorter (fewer trackers)
        ) ?: return emptyList()

        return listOf(best.magnet)
    }

    /** True if the magnet's display name mentions dual audio or dub. */
    private fun isDualAudio(dn: String): Boolean =
        dn.contains("dual audio", ignoreCase = true) ||
                dn.contains("dual-audio", ignoreCase = true) ||
                dn.contains("dualaudio", ignoreCase = true) ||
                dn.contains("dub", ignoreCase = true)

    /** Pull the `dn=` (display name) parameter out of a magnet URI, decoded. */
    private fun extractDn(magnet: String): String {
        val m = Regex("""[?&]dn=([^&]+)""", RegexOption.IGNORE_CASE).find(magnet)
        return if (m != null) decodeEntities(
            java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        ) else ""
    }

    /**
     * Conservative season/sequel marker detector. Used to classify a query or
     * series name as "is this a sequel season?" so S1 and S2 don't cross-match.
     * Matches: "season 2", "2nd season", "s2", "part 2", "ii"/"iii"/"iv",
     * and "vs"/"vs." (subtitle-style sequels like "Blue Lock vs. U-20 Japan").
     */
    private val sequelMarkerRegex = Regex(
        """\bseason\s*\d+\b|\b\d+(?:st|nd|rd|th)\s+season\b|\bs\d+\b|""" +
                """\bpart\s*\d+\b|\bii\b|\biii\b|\biv\b|\bvs\.?\b""",
        RegexOption.IGNORE_CASE
    )

    private fun hasSequelMarker(s: String): Boolean = sequelMarkerRegex.containsMatchIn(s)

    private fun stripTags(s: String): String = Regex("<[^>]+>").replace(s, "")

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    // ---- Scoring (exact-match priority + Levenshtein ratio) ----------------
    //
    // NOTE: partialRatio is deliberately ABSENT. rapidfuzz's WRatio blends a
    // sliding-window partial ratio, which rewards substring matches with 100.
    // For series names that caused "Blue Lock" to match "Blue Lock vs. U-20
    // Japan" at 100 (substring) — same as the exact "Blue Lock" match — so S2
    // (listed first) won. Series names on animetosho are clean, so the full
    // Levenshtein ratio is the right metric, and exact equality is handled
    // upstream as an instant unbeatable win.

    private fun ratio(a: String, b: String): Int {
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return if (maxLen == 0) 100
        else (((maxLen - dist).toDouble() / maxLen) * 100).toInt()
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
