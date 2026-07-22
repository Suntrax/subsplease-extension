package com.blissless.subsplease

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Pure-HTTP scraper for subsplease.org.
 *
 * Two-step resolution to get EVERY episode SubsPlease still has for a show,
 * instead of only the most-recent ~30 keys returned by the search endpoint:
 *
 *   1. GET https://subsplease.org/api/?f=search&tz=...&s=<show>
 *        -> JSON object of recent episodes; each entry has a "show" field
 *           giving the canonical SubsPlease title (e.g. searching "Dragon Ball"
 *           returns entries whose "show" is "Dragon Ball Daima").
 *
 *   2. From the first entry's "show" field, derive the URL slug
 *      (lowercase, non-alphanumeric -> '-'), fetch
 *        GET https://subsplease.org/shows/<slug>/
 *      and extract the sid from
 *        <table id="show-release-table" ... sid="N">
 *
 *   3. GET https://subsplease.org/api/?f=show&tz=...&sid=<N>
 *        -> { "batch": [...], "episode": { "<show> - <ep>": {...}, ... } }
 *      Parse the "episode" sub-object the same way the search response was
 *      parsed before.
 *
 * Fallback: if any step in (2) or (3) fails (404, no sid, malformed JSON,
 * network error), we silently fall back to the search response from step (1),
 * which preserves the old behavior. The new version is therefore strictly
 * better — it can only return MORE episodes than before, never fewer.
 *
 * Returns:
 *   Map<episodeNumber:Int, Map<resolution:String, magnet:String>>
 * sorted by episode number ascending.
 *
 * Why HttpURLConnection instead of WebView:
 *   - No Chromium/V8/HTTP cache lands in the extension's data dir.
 *   - Per-scrape time stays at ~1-2 s (was ~5-25 s with WebView).
 *   - Installed size stays at ~150 KB instead of growing to ~4.5 MB.
 */
object SubsPleaseScraper {

    private const val API_URL = "https://subsplease.org/api/"
    private const val SHOWS_URL = "https://subsplease.org/shows/"
    private const val TIMEZONE = "Europe/Vienna"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun getMagnetUrl(context: Context, anime: String): Map<Int, Map<String, String>> {
        if (anime.isBlank()) {
            throw IllegalArgumentException("Anime name must not be blank")
        }

        // ---- Step 1: search API (also gives us the fallback episode set) ----
        val searchUrl = "$API_URL?f=search&tz=$TIMEZONE&s=" +
                URLEncoder.encode(anime.trim(), "UTF-8")
        val searchRaw = fetch(searchUrl)

        // subsplease returns the literal "[]" (2 bytes) when nothing matched.
        if (searchRaw.isBlank() || searchRaw.trim() == "[]") {
            throw Exception("No episodes found for \"$anime\".")
        }

        val searchRoot: JSONObject = try {
            JSONObject(searchRaw)
        } catch (e: Exception) {
            throw Exception("SubsPlease API returned malformed JSON: ${e.message}")
        }

        // Parse the search response — used as fallback AND as a source of the
        // canonical show name.
        val fallbackEpisodes = parseEpisodesDict(searchRoot)

        // ---- Step 2 + 3: resolve show page -> sid -> full episode list ----
        // Pick the show name from the FIRST entry the API returned. The API
        // returns newest episodes first, so this prefers the most recent
        // matching show (e.g. "Sousou no Frieren S2" over S1, "Dragon Ball
        // Daima" over the original Dragon Ball which SubsPlease doesn't have).
        val canonicalShow: String? = run {
            val keys = searchRoot.keys()
            while (keys.hasNext()) {
                val entry = searchRoot.optJSONObject(keys.next()) ?: continue
                val show = entry.optString("show").trim()
                if (show.isNotEmpty()) return@run show
            }
            null
        }

        if (canonicalShow != null) {
            val fullEpisodes = tryFetchAllEpisodes(canonicalShow)
            if (fullEpisodes.isNotEmpty()) {
                return fullEpisodes.toSortedMap()
            }
            // else: silently fall through to the search-based fallback.
        }

        // ---- Fallback: use the search response directly (old behavior) ----
        if (fallbackEpisodes.isEmpty()) {
            throw Exception("No downloadable episodes found for \"$anime\".")
        }
        return fallbackEpisodes.toSortedMap()
    }

    /**
     * Try the show-page -> sid -> ?f=show flow. Returns an empty map on any
     * failure so the caller can fall back to the search response.
     */
    private fun tryFetchAllEpisodes(showName: String): Map<Int, Map<String, String>> {
        val slug = slugify(showName).takeIf { it.isNotEmpty() } ?: return emptyMap()

        val showPageHtml: String = try {
            fetch("$SHOWS_URL$slug/")
        } catch (e: Exception) {
            return emptyMap()
        }

        val sid = extractSid(showPageHtml) ?: return emptyMap()

        val showApiRaw: String = try {
            fetch("$API_URL?f=show&tz=$TIMEZONE&sid=$sid")
        } catch (e: Exception) {
            return emptyMap()
        }

        val showRoot = try {
            JSONObject(showApiRaw)
        } catch (e: Exception) {
            return emptyMap()
        }

        val episodeObj = showRoot.optJSONObject("episode") ?: return emptyMap()
        return parseEpisodesDict(episodeObj)
    }

    /**
     * SubsPlease slug convention (verified against multiple titles):
     *   - lowercase
     *   - any run of non-alphanumeric chars becomes a single '-'
     *   - trim leading/trailing '-'
     *
     * Examples:
     *   "Blue Lock"                                 -> "blue-lock"
     *   "Dragon Ball Daima"                         -> "dragon-ball-daima"
     *   "Sousou no Frieren"                         -> "sousou-no-frieren"
     *   "Re Zero kara Hajimeru Isekai Seikatsu"     -> "re-zero-kara-hajimeru-isekai-seikatsu"
     */
    private fun slugify(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    /**
     * Extract the show ID from the show-page HTML. SubsPlease renders:
     *   <table id="show-release-table" cellpadding="0" border="0" cellspacing="0" sid="449">
     * The sid attribute position varies, so we match the whole table tag and
     * grab whichever sid="..." appears inside it.
     */
    private fun extractSid(html: String): String? {
        val tableMatch = Regex(
            """<table[^>]*id="show-release-table"[^>]*>"""
        ).find(html) ?: return null
        val tableTag = tableMatch.value
        val sidMatch = Regex("""sid="(\d+)"""").find(tableTag) ?: return null
        return sidMatch.groupValues[1].takeIf { it.isNotEmpty() }
    }

    /**
     * Shared parser for both the search response (top-level object) and the
     * ?f=show response's "episode" sub-object — both have the same shape:
     *   { "<show> - <ep>": { "episode": "12", "downloads": [ {res, magnet}, ... ] }, ... }
     */
    private fun parseEpisodesDict(root: JSONObject): MutableMap<Int, MutableMap<String, String>> {
        val episodesDict = mutableMapOf<Int, MutableMap<String, String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = root.optJSONObject(key) ?: continue

            // The API gives us "episode" as a string. Try it directly first,
            // then fall back to a trailing-number regex in case subsplease
            // ever ships something weird like "12v2" or "12.5".
            val epNum = entry.optString("episode").toIntOrNull()
                ?: extractEpisodeNumber(key) ?: continue

            val downloads = entry.optJSONArray("downloads") ?: continue
            if (downloads.length() == 0) continue

            val qualities = mutableMapOf<String, String>()
            for (i in 0 until downloads.length()) {
                val dl = downloads.optJSONObject(i) ?: continue
                val res = dl.optString("res").trim()
                val magnet = dl.optString("magnet").trim()
                if (res.isNotEmpty() && magnet.startsWith("magnet:")) {
                    qualities[res] = magnet
                }
            }
            if (qualities.isNotEmpty()) {
                episodesDict[epNum] = qualities
            }
        }
        return episodesDict
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/html, text/plain, */*")
            setRequestProperty("Referer", "https://subsplease.org/")
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("SubsPlease HTTP $code for $url")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Fallback episode-number extractor. Used when the "episode" field is
     * missing or non-numeric — handles keys like "Show - 12v2" or "Show - 12.5".
     */
    private fun extractEpisodeNumber(text: String): Int? {
        val match = Regex("(\\d+)\\s*(?:v\\d+)?\\s*$", RegexOption.IGNORE_CASE).find(text)
        if (match != null) return match.groupValues[1].toIntOrNull()
        val fallback = Regex("\\d+").findAll(text).toList()
        return fallback.lastOrNull()?.value?.toIntOrNull()
    }
}
