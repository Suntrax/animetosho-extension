# Tensei: AnimeTosho

A headless background scraper extension for the **Tensei Scraper** app. This extension scrapes `animetosho.org` to find the best-matching torrent release for a given anime and returns its magnet link.

## 📖 How it Works

1. **Discovery:** The Main App (Tensei Scraper) finds this extension via the `EXTENSION_BEACON` receiver.
2. **Query:** When you search an anime, the Main App passes the English or Romaji anime name (plus the AniList ID) to this extension's `ContentProvider`.
3. **Scraping:**
- **Step 1 (Search):** The extension builds `https://animetosho.org/search?q=<anime>` and fetches it with Android's built-in `HttpURLConnection`.
- **Step 2 (Parse):** The HTML is split into per-result regions on the `home_list_entry` marker (robust against nested `div`s, since Jsoup isn't allowed). For each region, regex extracts the series name (from `span.serieslink a`) and the `magnet:` href.
- **Step 3 (Match):** Each result's series name is fuzzy-matched against the query using a Levenshtein-based approximation of `rapidfuzz`'s WRatio (`max(ratio, partialRatio)`). The magnet of the highest-scoring result is kept.
4. **Return:** The best-matching magnet is packaged into a single-element JSON array and sent back to the Main App.

## 🛠️ Technical Details

- **Dependencies:** Zero. Uses only built-in Android APIs (`HttpURLConnection`, `org.json`).
- **APK Size:** ~40KB (Heavily shrunk via R8/ProGuard).
- **Data Format Returned:** Flat Magnet List (`["magnet:..."]`)

## 🏗️ Building

1. Place your release keystore at `app/release.jks` and update the passwords in `app/build.gradle.kts`.
2. Run `./gradlew assembleRelease` to build a shrunk, signed APK.
3. Install the APK on your device alongside the main Tensei Scraper app.
