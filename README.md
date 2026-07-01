# Tensei: SubsPlease

A headless background scraper extension for the **Tensei Scraper** anime client.
Queries the SubsPlease JSON API and returns every episode of a show, each with
its magnet links sorted by video quality (480p / 720p / 1080p).

## How it works

1. **Discovery** — The Tensei Scraper main app finds this extension via the
   `com.blissless.animeclient.EXTENSION_BEACON` broadcast receiver and the
   `"Tensei: "` label prefix.
2. **Query** — The main app calls this extension's `ContentProvider` with the
   URI `content://com.blissless.subsplease.provider/scrape?anime=<english>`.
3. **Scrape** — A single HTTP GET to the SubsPlease JSON API:

   ```
   GET https://subsplease.org/api/?f=search&tz=Europe/Vienna&s=<show>
   ```

   The response is a JSON object keyed by `"<show> - <episode>"`:

   ```json
   {
     "Blue Lock - 38": {
       "episode": "38",
       "show": "Blue Lock",
       "downloads": [
         { "res": "1080", "magnet": "magnet:?xt=urn:btih:..." },
         { "res": "720",  "magnet": "magnet:?xt=urn:btih:..." },
         { "res": "480",  "magnet": "magnet:?xt=urn:btih:..." }
       ]
     }
   }
   ```

   The extension walks every entry, reads the `episode` field (with a
   trailing-number regex fallback for unusual values like `"12v2"`), and
   builds a map of `episodeNumber → { resolution → magnet }`. The literal
   `[]` response (no match) is detected and surfaced as an error.

4. **Return** — The map is serialized to JSON and returned to the main app.

No `WebView`, no JavaScript rendering, no DOM polling — the API hands us
everything in one HTTP call. A typical scrape completes in under 500 ms.

## Data format returned

```json
{
  "1":  { "1080": "magnet:?xt=urn:btih:...", "720": "magnet:?...", "480": "magnet:?..." },
  "2":  { "1080": "magnet:?xt=urn:btih:...", "720": "magnet:?...", "480": "magnet:?..." }
}
```

On failure:

```json
{ "error": "No episodes found for \"<show>\"." }
```

## Technical details

| | |
|---|---|
| **Dependencies** | Zero. Uses only `java.net.HttpURLConnection` + `org.json`. |
| **HTTP calls per scrape** | 1 |
| **APK size** | ~40 KB after R8 shrinking |
| **Min Android** | API 26 |
| **Parameters read** | `anime` (English title) |

## Architecture

| File | Purpose |
|------|---------|
| `SubsPleaseScraper.kt` | API call + JSON parsing. Returns `Map<Int, Map<String, String>>`. |
| `ScraperProvider.kt` | `ContentProvider` entry point. Serializes the map to JSON. |
| `ExtensionBeaconReceiver.kt` | Empty `BroadcastReceiver` for discovery. |

## Building

1. Place your release keystore at `app/release.jks` and add its credentials to
   `local.properties` (gitignored):

   ```properties
   storeFile=/absolute/path/to/release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

2. Build the shrunk, signed APK:

   ```bash
   ./gradlew assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-release.apk`

3. Install alongside the Tensei Scraper main app:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
