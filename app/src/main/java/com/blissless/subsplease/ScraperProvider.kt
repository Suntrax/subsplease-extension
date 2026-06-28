package com.blissless.subsplease

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONObject

class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.subsplease.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")

        private const val CODE_SCRAPES = 1
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        when (uriMatcher.match(uri)) {
            CODE_SCRAPES -> {
                val animeName = uri.getQueryParameter("anime")
                val cursor = MatrixCursor(arrayOf("data"))

                if (animeName == null) {
                    cursor.addRow(arrayOf("{\"error\":\"No anime name provided\"}"))
                    return cursor
                }

                try {
                    // Pass context!! to the scraper
                    val result = SubsPleaseScraper.getMagnetUrl(context!!, animeName)

                    if (result.isEmpty()) {
                        cursor.addRow(arrayOf("{\"error\":\"Scraper returned 0 episodes. Website might be down or HTML changed.\"}"))
                    } else {
                        // Convert Map to JSON using built-in org.json
                        val json = JSONObject()
                        for ((epNum, qualities) in result) {
                            val qJson = JSONObject()
                            for ((q, magnet) in qualities) {
                                qJson.put(q, magnet)
                            }
                            json.put(epNum.toString(), qJson)
                        }
                        cursor.addRow(arrayOf(json.toString()))
                    }
                } catch (e: Exception) {
                    val errorJson = "{\"error\":\"Scraping failed: ${e.message}\"}"
                    cursor.addRow(arrayOf(errorJson))
                }

                return cursor
            }
        }
        return null
    }

    // Required overrides for ContentProvider
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}