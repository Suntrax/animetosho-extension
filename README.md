# Tensei: AnimeTosho

A headless background scraper extension for the **Tensei Scraper** app. This extension scrapes `animetosho.org` to find the best-matching torrent release for a given anime and returns its magnet link.

## 📖 How it Works

1. **Discovery:** The Main App (Tensei Scraper) finds this extension via the `EXTENSION_BEACON` receiver.
2. **Query:** When you search an anime, the Main App passes the English or Romaji anime name (plus the AniList ID) to this extension's `ContentProvider`.
3. **Scraping:**
- **Step 1 (Search):** The extension builds `https://animetosho.org/search?q=<anime>` and fetches it with Android's built-in `HttpURLConnection`.
- **Step 2 (Pair):** The search page lists releases by recency, each row with its own `span.serieslink` (series name), Magnet link, and a `Seeders: N / Leechers: N` span. For each magnet the extension pairs it to the **nearest preceding serieslink** (its series) and the **nearest following seeder span** (its seeders).
- **Step 3 (Disambiguate):** Series are filtered by a sequel/season marker so S1 and S2 don't cross-match — a bare `"Blue Lock"` only competes among non-sequel series; `"Blue Lock Season 2"` only among sequel series. Exact (case-insensitive) name equality wins instantly.
- **Step 4 (Select):** From the winning series' releases, one magnet is returned — the most-seeded overall, or (if the query contains `"dub"`) the most-seeded dual-audio release. Ties prefer dual-audio, then the shorter magnet.
4. **Return:** The magnet is HTML-entity-decoded (`&amp;` → `&`) and packaged into a single-element JSON array for the Main App.

## 🛠️ Technical Details

- **Dependencies:** Zero. Uses only built-in Android APIs (`HttpURLConnection`, `org.json`).
- **APK Size:** ~40KB (Heavily shrunk via R8/ProGuard).
- **Data Format Returned:** Flat Magnet List (`["magnet:?xt=urn:btih:…"]`)
- **Selection policy:** most-seeded release of the best-matching series; dub preference via `"dub"` in the query.
- **Season disambiguation:** exact-name priority + sequel-marker filter (`season N` / `sN` / `Nth season` / `part N` / `ii`/`iii`/`iv` / `vs`).

## 🏗️ Building

1. Place your release keystore at `app/release.jks` and update the passwords in `app/build.gradle.kts`.
2. Run `./gradlew assembleRelease` to build a shrunk, signed APK.
3. Install the APK on your device alongside the main Tensei Scraper app.
