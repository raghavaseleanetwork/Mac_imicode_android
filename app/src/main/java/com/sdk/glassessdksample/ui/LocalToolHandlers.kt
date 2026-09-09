package com.sdk.glassessdksample.ui

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Small collection of local web tool helpers used by GeminiLiveService for quick lookups.
 * - No API keys required for dictionary (dictionaryapi.dev), wiki (wikipedia REST), web search (duckduckgo instantanswer),
 *   weather (open-meteo), stocks (unofficial Yahoo Finance scrape).
 * - These methods return short plain-text summaries suitable for conversational replies.
 */
object LocalToolHandlers {
    private const val TAG = "LocalToolHandlers"

    // 🆕 Explicit short timeouts. These calls run inside a `runBlocking` on the
    // Gemini Live tool-call callback (see MainActivity.handleGeminiToolCall), so a
    // hung/slow network call previously blocked that thread forever - Gemini Live
    // never got a tool response and the user heard only the loading tone with no
    // answer. A bounded timeout guarantees the tool call always returns in time.
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun dictionaryLookup(word: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(word, "UTF-8")}" 
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext("No definition found for: $word")
                val body = resp.body?.string() ?: return@withContext("No definition found for: $word")
                val arr = JSONArray(body)
                val first = arr.getJSONObject(0)
                val meanings = first.getJSONArray("meanings")
                val defs = StringBuilder()
                for (i in 0 until minOf(2, meanings.length())) {
                    val meaning = meanings.getJSONObject(i)
                    val part = meaning.optString("partOfSpeech")
                    val definitions = meaning.getJSONArray("definitions")
                    if (definitions.length() > 0) {
                        val d = definitions.getJSONObject(0).optString("definition")
                        defs.append("($part) $d")
                        if (i < minOf(2, meanings.length()) - 1) defs.append("; ")
                    }
                }
                return@withContext(defs.toString().ifEmpty { "No definition found for: $word" })
            }
        } catch (e: Exception) {
            return@withContext("Error looking up definition: ${e.message}")
        }
    }

    suspend fun wikiSummary(query: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext("No Wikipedia summary found for: $query")
                val body = resp.body?.string() ?: return@withContext("No Wikipedia summary found for: $query")
                val obj = JSONObject(body)
                val title = obj.optString("title")
                val extract = obj.optString("extract")
                return@withContext(if (extract.isNotBlank()) "$title: ${extract.take(800)}" else "No summary found for: $query")
            }
        } catch (e: Exception) {
            return@withContext("Error fetching wiki summary: ${e.message}")
        }
    }

    suspend fun webSearchInstant(query: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_redirect=1"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) {
                        val obj = JSONObject(body)
                        val abstractText = obj.optString("AbstractText")
                        if (abstractText.isNotBlank()) return@withContext(abstractText.take(800))
                        val abstract = obj.optString("Abstract")
                        if (abstract.isNotBlank()) return@withContext(abstract.take(800))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instant answer lookup failed, falling back to full search: ${e.message}")
        }

        // DuckDuckGo's instant-answer API has no abstract for most conversational
        // queries ("what's happening in the country right now"), which used to
        // return the literal string "No instant answer; try web_search_full" to the
        // model. That reads as a dead end rather than a retryable failure, and the
        // model would often end the turn without answering at all. Escalate to the
        // full search here instead of handing that decision back to the model.
        return@withContext webSearchFull(query)
    }

    suspend fun webSearchFull(query: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val req = Request.Builder().url(url).header("User-Agent", "ImiText-Sample/1.0").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext("No results for: $query")
                val body = resp.body?.string() ?: return@withContext("No results for: $query")

                val titleRegex = Regex("<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val snippetRegex = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

                val title = titleRegex.find(body)?.groupValues?.get(1)?.replace(Regex("<.*?>"), "")?.trim()
                val snippet = snippetRegex.find(body)?.groupValues?.get(1)?.replace(Regex("<.*?>"), "")?.trim()

                if (!snippet.isNullOrBlank()) return@withContext((title?.let { "$it: " } ?: "") + snippet.take(800))

                // Fallback to meta description
                val metaRegex = Regex("<meta name=\"description\" content=\"([^\"]+)\">", setOf(RegexOption.IGNORE_CASE))
                val meta = metaRegex.find(body)?.groupValues?.get(1)
                if (!meta.isNullOrBlank()) return@withContext((title?.let { "$it: " } ?: "") + meta.take(800))

                return@withContext("No instant answer found for: $query")
            }
        } catch (e: Exception) {
            return@withContext("Error performing full web search: ${e.message}")
        }
    }

    suspend fun weatherForCity(city: String): String = withContext(Dispatchers.IO) {
        try {
            // Use Nominatim to geocode then Open-Meteo for weather
            val geoUrl = "https://nominatim.openstreetmap.org/search?format=json&q=${URLEncoder.encode(city, "UTF-8")}" 
            val geoReq = Request.Builder().url(geoUrl).header("User-Agent", "ImiText-Sample/1.0").get().build()
            client.newCall(geoReq).execute().use { gResp ->
                if (!gResp.isSuccessful) return@withContext("Could not geocode: $city")
                val gBody = gResp.body?.string() ?: return@withContext("Could not geocode: $city")
                val gArr = JSONArray(gBody)
                if (gArr.length() == 0) return@withContext("Location not found: $city")
                val loc = gArr.getJSONObject(0)
                val lat = loc.optString("lat")
                val lon = loc.optString("lon")
                val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&temperature_unit=celsius"
                val wReq = Request.Builder().url(weatherUrl).get().build()
                client.newCall(wReq).execute().use { wResp ->
                    if (!wResp.isSuccessful) return@withContext("Weather fetch failed for: $city")
                    val wBody = wResp.body?.string() ?: return@withContext("Weather fetch failed for: $city")
                    val wObj = JSONObject(wBody)
                    val cur = wObj.optJSONObject("current_weather") ?: return@withContext("No current weather for: $city")
                    val temp = cur.optDouble("temperature")
                    val wind = cur.optDouble("windspeed")
                    val desc = "$city: ${temp}°C, wind ${wind} m/s"
                    return@withContext(desc)
                }
            }
        } catch (e: Exception) {
            return@withContext("Error fetching weather: ${e.message}")
        }
    }

    suspend fun stockQuote(symbol: String): String = withContext(Dispatchers.IO) {
        try {
            // Quick Yahoo Finance scrape of summary JSON endpoint
            val s = URLEncoder.encode(symbol.trim().uppercase(), "UTF-8")
            val url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=$s"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext("No stock info for: $symbol")
                val body = resp.body?.string() ?: return@withContext("No stock info for: $symbol")
                val obj = JSONObject(body)
                val result = obj.optJSONObject("quoteResponse")?.optJSONArray("result") ?: return@withContext("No stock info for: $symbol")
                if (result.length() == 0) return@withContext("No stock info for: $symbol")
                val first = result.getJSONObject(0)
                val name = first.optString("shortName", first.optString("longName", symbol))
                val price = first.optDouble("regularMarketPrice", Double.NaN)
                val change = first.optDouble("regularMarketChange", Double.NaN)
                val pct = first.optDouble("regularMarketChangePercent", Double.NaN)
                return@withContext("$name: ${if (!price.isNaN()) "${String.format("%.2f", price)}" else "N/A"} (${if (!change.isNaN()) String.format("%.2f", change) else "N/A"}, ${if (!pct.isNaN()) String.format("%.2f", pct) + "%" else "N/A"})")
            }
        } catch (e: Exception) {
            return@withContext("Error fetching stock: ${e.message}")
        }
    }

    suspend fun googleSearch(query: String): String = withContext(Dispatchers.IO) {
        // Fallback behavior: use the no-API DuckDuckGo HTML scraper when Google API keys are not desired.
        return@withContext(webSearchFull(query))
    }
}
