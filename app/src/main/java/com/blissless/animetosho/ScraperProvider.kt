package com.blissless.animetosho

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContentProvider queried by the Tensei Main App.
 *
 * Query URI:  content://com.tensei.extension.animetosho.provider/scrape
 *             ?anime=<title>&anilistId=<id>
 *
 * Returns a single-row MatrixCursor whose "data" column holds a JSON string:
 *   - List<String>  -> ["magnet:...", ...]          (flat magnets, used here)
 *   - Map<Int, Map> -> {"1":{"1080p":"magnet:..."}} (per-episode, optional)
 *   - failure       -> {"error":"..."}
 *
 * NOTE: the template's original `result.isEmpty()` call did NOT compile,
 * because `scrape()` returns `Any` and `Any` has no `isEmpty()`. This version
 * narrows with explicit `is` checks and binds each branch to a typed local,
 * so there is no reliance on smart-casting and the release build succeeds.
 */
class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.animetosho.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != CODE_SCRAPES) return null

        val animeName = uri.getQueryParameter("anime")
        val anilistId = uri.getQueryParameter("anilistId")
        val cursor = MatrixCursor(arrayOf("data"))

        val json: String = try {
            val result = AnimeToshoScraper.scrape(context!!, animeName, anilistId)
            serialize(result)
        } catch (e: Exception) {
            val msg = e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: "Unknown error"
            "{\"error\":\"Scraping failed: $msg\"}"
        }

        cursor.addRow(arrayOf(json))
        return cursor
    }

    /**
     * Turn the scraper's `Any` return value into the JSON the Main App expects.
     * Each branch binds to an explicitly-typed local so the compiler never has
     * to smart-cast `Any` — this is what fixes the `Unresolved reference
     * 'isEmpty'` build error present in the template.
     */
    private fun serialize(result: Any): String {
        when (result) {
            is List<*> -> {
                val list: List<*> = result
                return if (list.isEmpty()) {
                    "{\"error\":\"No results found.\"}"
                } else {
                    val arr = JSONArray()
                    for (item in list) arr.put(item.toString())
                    arr.toString()
                }
            }
            is Map<*, *> -> {
                val map: Map<*, *> = result
                return if (map.isEmpty()) {
                    "{\"error\":\"No results found.\"}"
                } else {
                    val obj = JSONObject()
                    for ((key, value) in map) {
                        @Suppress("UNCHECKED_CAST")
                        obj.put(key.toString(), JSONObject(value as Map<String, Any>))
                    }
                    obj.toString()
                }
            }
        }
        return "{\"error\":\"Unexpected scraper result: ${result::class.java.simpleName}\"}"
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
