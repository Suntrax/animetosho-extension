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

<<<<<<< HEAD
- **Dependencies:** Zero. Uses only built-in Android APIs (`HttpURLConnection`, `org.json`).
- **APK Size:** ~40KB (Heavily shrunk via R8/ProGuard).
- **Data Format Returned:** Flat Magnet List (`["magnet:..."]`)

## 🏗️ Building

1. Place your release keystore at `app/release.jks` and update the passwords in `app/build.gradle.kts`.
2. Run `./gradlew assembleRelease` to build a shrunk, signed APK.
3. Install the APK on your device alongside the main Tensei Scraper app.
=======
1. Click **"Use this template"** at the top of this repository to create a new repo for your extension.
2. Clone your new repo and open it in Android Studio.
3. In Android Studio, press `Ctrl+Shift+R` (or `Cmd+Shift+R` on Mac) to open **Replace in Path**.
   - Search for `com.blissless.tensei_extension_template` and replace it with your new package name (e.g., `com.blissless.seadex`).
   - Search for `TEMPLATE_NAME` and replace it with your extension's display name (e.g., `SeaDex`).
4. Move your Kotlin files into the new package folder structure (e.g., `com/blissless/seadex/`).
5. Place your release keystore at `app/release` and update the passwords in `app/build.gradle.kts`.
6. Open `TemplateScraper.kt` (rename it if you like) and implement your scraping logic!

## 📦 Data Contract

The Main App sends two parameters to your `ContentProvider`:
- `anime`: The English or Romaji name of the anime (e.g., "BLUE LOCK").
- `anilistId`: The Anilist ID (e.g., "137822").

Your scraper must return a JSON string in one of two formats:

**Format 1: Episode Map (e.g., [SubsPlease](https://github.com/Suntrax/subsplease-extension))**
```json
{
  "1": {
    "1080p": "magnet:?xt=urn:btih:...",
    "720p": "magnet:?xt=urn:btih:..."
  },
  "2": {
    "1080p": "magnet:?xt=urn:btih:..."
  }
}
```

**Format 2: Flat Magnet List (e.g., [SeaDex](https://github.com/Suntrax/seadex-extension))**
```json
[
  "magnet:?xt=urn:btih:...",
  "magnet:?xt=urn:btih:..."
]
```

If your scraper fails, return an error object:
```json
{
  "error": "Description of what went wrong."
}
```

## 🏗️ Building

Extensions are built to be as tiny as possible (~40KB). 
- Do not add any external dependencies (no OkHttp, no Jsoup, no Gson). Use Android's built-in `HttpURLConnection`, `WebView`, and `org.json`.
- R8 shrinking rules are stored in `app/src/main/keepRules/rules.keep`.
- Delete folders /app/main/res/layout and /app/main/res/values and /app/main/res/values-night
- Always build the **Release APK** (`./gradlew assembleRelease`) to ensure R8 shrinks the APK size.
>>>>>>> d32cd9c77f0e013f06cc7e3b917c4092b6ff28c3
