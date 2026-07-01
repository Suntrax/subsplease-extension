package com.blissless.subsplease

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Pure-HTTP scraper for subsplease.org.
 *
 * Uses the official JSON API:
 *   GET https://subsplease.org/api/?f=search&tz=Europe/Vienna&s=<show>
 *
 * The response is a JSON object keyed by "<show> - <episode>", e.g.
 *   { "Blue Lock - 38": { "episode": "38", "show": "Blue Lock",
 *                         "downloads": [ {"res":"1080","magnet":"magnet:?..."}, ... ] } }
 *
 * Returns the same shape as the previous WebView-based scraper:
 *   Map<episodeNumber:Int, Map<resolution:String, magnet:String>>
 * sorted by episode number ascending.
 *
 * Why HttpURLConnection instead of WebView:
 *   - No Chromium/V8/HTTP cache lands in the extension's data dir.
 *   - Per-scrape time drops from ~5-25 s to ~500 ms.
 *   - Installed size stays at ~150 KB instead of growing to ~4.5 MB.
 */
object SubsPleaseScraper {

    private const val API_URL = "https://subsplease.org/api/"
    private const val TIMEZONE = "Europe/Vienna"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun getMagnetUrl(context: Context, anime: String): Map<Int, Map<String, String>> {
        if (anime.isBlank()) {
            throw IllegalArgumentException("Anime name must not be blank")
        }

        val url = "$API_URL?f=search&tz=$TIMEZONE&s=" +
                URLEncoder.encode(anime.trim(), "UTF-8")
        val raw = fetch(url)

        // subsplease returns the literal "[]" (2 bytes) when nothing matched.
        // JSONObject can't parse a bare array at top level — handle it explicitly.
        if (raw.isBlank() || raw.trim() == "[]") {
            throw Exception("No episodes found for \"$anime\".")
        }

        val root: JSONObject = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw Exception("SubsPlease API returned malformed JSON: ${e.message}")
        }

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

        if (episodesDict.isEmpty()) {
            throw Exception("No downloadable episodes found for \"$anime\".")
        }
        return episodesDict.toSortedMap()
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Referer", "https://subsplease.org/")
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("SubsPlease API HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Fallback episode-number extractor. Used only if the "episode" field is
     * missing or non-numeric — currently never, but kept for resilience.
     */
    private fun extractEpisodeNumber(text: String): Int? {
        // Match "Show Name - 12v2" or "Show Name - 12.5"
        val match = Regex("(\\d+)\\s*(?:v\\d+)?\\s*$", RegexOption.IGNORE_CASE).find(text)
        if (match != null) return match.groupValues[1].toIntOrNull()
        val fallback = Regex("\\d+").findAll(text).toList()
        return fallback.lastOrNull()?.value?.toIntOrNull()
    }
}
