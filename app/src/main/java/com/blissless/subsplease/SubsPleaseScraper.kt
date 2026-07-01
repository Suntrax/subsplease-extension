package com.blissless.subsplease

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SubsPleaseScraper {

    fun getMagnetUrl(context: Context, anime: String): Map<Int, Map<String, String>> {
        val url = "https://subsplease.org/shows/${anime.lowercase().replace(" ", "-")}/"

        var error: Exception? = null
        val latch = CountDownLatch(1)

        // 1. Create a bridge to receive the massive JSON string without truncation
        val bridge = object {
            @Volatile
            var result: String? = null

            @JavascriptInterface
            fun sendData(json: String) {
                result = json
                latch.countDown() // unblock the background thread
            }
        }

        // WebView MUST be created on the Main UI thread
        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = false
                webView.settings.databaseEnabled = false
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView.settings.blockNetworkImage = true
                webView.settings.loadsImagesAutomatically = false
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                // Attach the bridge so JavaScript can call AndroidScraper.sendData()
                webView.addJavascriptInterface(bridge, "AndroidScraper")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        val handler = Handler(Looper.getMainLooper())
                        var attempts = 0
                        val maxAttempts = 40

                        val pollRunnable = object : Runnable {
                            override fun run() {
                                attempts++
                                view?.evaluateJavascript("(function() { return document.querySelectorAll('label.episode-title').length; })();") { res ->
                                    val count = res?.removeSurrounding("\"")?.toIntOrNull() ?: 0

                                    if (count > 0) {
                                        // Elements found! Inject JS to package data and send via Bridge
                                        val jsCode = """
                                            (function() {
                                                const episodes = [];
                                                document.querySelectorAll("label.episode-title").forEach(label => {
                                                    const text = label.textContent;
                                                    const container = label.nextElementSibling;
                                                    if (!container) return;
                                                    const entry = { title: text, qualities: {} };
                                                    let currentQuality = null;
                                                    container.childNodes.forEach(node => {
                                                        if (node.tagName === "LABEL" && node.classList.contains("links")) {
                                                            currentQuality = node.textContent.trim();
                                                        }
                                                        if (node.tagName === "A" && node.href && node.href.startsWith("magnet:?")) {
                                                            if (currentQuality) {
                                                                entry.qualities[currentQuality] = node.href;
                                                            }
                                                        }
                                                    });
                                                    episodes.push(entry);
                                                });
                                                // Pass the data to Kotlin without size limits!
                                                AndroidScraper.sendData(JSON.stringify(episodes));
                                            })()
                                        """.trimIndent()

                                        view?.evaluateJavascript(jsCode, null)
                                    } else if (attempts < maxAttempts) {
                                        handler.postDelayed(this, 500)
                                    } else {
                                        latch.countDown()
                                    }
                                }
                            }
                        }
                        handler.postDelayed(pollRunnable, 500)
                    }
                }

                webView.loadUrl(url)
            } catch (e: Exception) {
                error = e
                latch.countDown()
            }
        }

        latch.await(25, TimeUnit.SECONDS)
        error?.let { throw it }

        val cleanJson = bridge.result
        if (cleanJson.isNullOrEmpty() || cleanJson == "[]") {
            throw Exception("No episodes found. JavaScript did not render them in time.")
        }

        // Parse the JSON using your exact Python logic
        val episodesArray = JSONArray(cleanJson)
        val episodesDict = mutableMapOf<Int, MutableMap<String, String>>()

        for (i in 0 until episodesArray.length()) {
            val ep = episodesArray.getJSONObject(i)
            val title = ep.getString("title")

            val epNum = extractEpisodeNumber(title) ?: continue
            val qualitiesObj = ep.getJSONObject("qualities")

            val qualities = mutableMapOf<String, String>()
            val keys = qualitiesObj.keys()
            while (keys.hasNext()) {
                val q = keys.next()
                qualities[q] = qualitiesObj.getString(q)
            }

            if (qualities.isNotEmpty()) {
                episodesDict[epNum] = qualities
            }
        }

        return episodesDict.toSortedMap()
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val match = Regex("(\\d+)\\s*(?:v\\d+)?\\s*$", RegexOption.IGNORE_CASE).find(text)
        if (match != null) return match.groupValues[1].toIntOrNull()

        val fallback = Regex("\\d+").findAll(text).toList()
        return fallback.lastOrNull()?.value?.toIntOrNull()
    }
}