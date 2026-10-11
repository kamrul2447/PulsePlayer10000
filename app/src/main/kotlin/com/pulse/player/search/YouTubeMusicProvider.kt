package com.pulse.player.search

import android.net.Uri
import com.pulse.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube search & stream provider using NewPipeExtractor for native search
 * and lazy audio stream URL resolution.
 */
class YouTubeMusicProvider : MusicSearchProvider {
    override val id: String = "youtube"
    override val name: String = "YouTube"
    override val timeoutMs: Long = 10_000L

    @Volatile
    var userApiKey: String? = null

    private val audioSource: YouTubeAudioSource = NewPipeYouTubeAudioSource

    private class Cached(val at: Long, val rows: List<TrackSearchResult>)

    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 24
    }

    override fun keySource(): String =
        when {
            !userApiKey?.trim().isNullOrBlank() -> "user"
            BuildConfig.YOUTUBE_API_KEY.trim().isNotBlank() -> "build"
            else -> "extractor"
        }

    override suspend fun search(query: String, limit: Int): List<TrackSearchResult> = withContext(Dispatchers.IO) {
        val cacheKey = query.lowercase() + "|" + limit
        synchronized(cache) {
            cache[cacheKey]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return@withContext it.rows }
        }

        // 1. Try NewPipeExtractor native search
        val extractorResults = runCatching { audioSource.search(query, limit) }.getOrNull()
        if (!extractorResults.isNullOrEmpty()) {
            synchronized(cache) { cache[cacheKey] = Cached(System.currentTimeMillis(), extractorResults) }
            return@withContext extractorResults
        }

        // 2. Fallback to YouTube Data API v3 if key is available
        val apiKey = apiKey() ?: throw ProviderException(
            "YouTube search unavailable. Check internet connection or add API key in Settings → Online & APIs.", "no_results"
        )

        val url = Uri.parse(BASE_URL + "/search").buildUpon()
            .appendQueryParameter("part", "snippet")
            .appendQueryParameter("type", "video")
            .appendQueryParameter("q", query)
            .appendQueryParameter("maxResults", limit.coerceIn(1, 25).toString())
            .appendQueryParameter("key", apiKey)
            .build().toString()

        val response = ApiHttp.get("youtube", url)
        if (response.code !in 200..299) {
            throw ProviderException(explain(response.code, response.body), "http")
        }

        val rows = parseSearch(response.body, limit)
        synchronized(cache) { cache[cacheKey] = Cached(System.currentTimeMillis(), rows) }
        rows
    }

    override suspend fun streamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
        val id = trackId.trim()
            .removePrefix("pulse-yt://")
            .removePrefix("https://www.youtube.com/watch?v=")
            .removePrefix("https://youtu.be/")
        if (id.isBlank()) return@withContext null

        audioSource.resolveAudioUrl(id) ?: ("https://www.youtube.com/watch?v=" + Uri.encode(id))
    }

    override suspend fun check(): ProviderCheck = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isNullOrBlank()) {
            val testSearch = runCatching { audioSource.search("music", 1) }.getOrNull()
            return@withContext if (!testSearch.isNullOrEmpty()) {
                ProviderCheck(true, "NewPipeExtractor active")
            } else {
                ProviderCheck(true, "Ready (Add API key for YouTube Data API v3)")
            }
        }

        val url = Uri.parse(BASE_URL + "/videos").buildUpon()
            .appendQueryParameter("part", "id")
            .appendQueryParameter("id", "M7lc1UVf-VE")
            .appendQueryParameter("key", apiKey)
            .build().toString()

        val r = ApiHttp.get("youtube", url)
        if (r.code in 200..299) ProviderCheck(true, "Key works (${r.ms} ms)", r.ms)
        else ProviderCheck(false, explain(r.code, r.body), r.ms)
    }

    private fun apiKey(): String? =
        userApiKey?.trim()?.takeIf { it.isNotBlank() }
            ?: BuildConfig.YOUTUBE_API_KEY.trim().takeIf { it.isNotBlank() }

    private fun explain(code: Int, body: String): String {
        val rootObj = runCatching { JSONObject(body) }.getOrNull()
        val errorObj = rootObj?.optJSONObject("error")
        val errorsArray = errorObj?.optJSONArray("errors")
        val firstError = errorsArray?.optJSONObject(0)
        val reason = firstError?.optString("reason").orEmpty()
        val apiMsg = errorObj?.optString("message").orEmpty()

        return when {
            reason == "quotaExceeded" || reason == "dailyLimitExceeded" || reason == "rateLimitExceeded" ->
                "YouTube daily quota used up. It resets daily; try again tomorrow."
            reason == "keyInvalid" ->
                "YouTube key is invalid. Double check it in Settings → Online & APIs."
            reason == "accessNotConfigured" ->
                "YouTube Data API v3 is disabled in Google Cloud Console. Click 'Enable' for YouTube Data API v3 in Google Cloud Console."
            reason.contains("Referer", true) || reason.contains("ipRefererBlocked", true) ->
                "Key is restricted to websites. Set Application Restrictions to 'None' or 'Android apps' in Google Cloud Console."
            apiMsg.isNotBlank() ->
                "YouTube error ($code): $apiMsg"
            code == 400 -> "YouTube rejected the request (400 Bad Request). Check API key."
            code == 403 -> "YouTube refused access (403 Forbidden). Ensure 'YouTube Data API v3' is enabled in Google Cloud Console."
            else -> "YouTube error $code"
        }
    }

    private fun parseSearch(body: String, limit: Int): List<TrackSearchResult> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<TrackSearchResult>(minOf(limit, items.length()))
        for (i in 0 until minOf(limit, items.length())) {
            val item = items.optJSONObject(i) ?: continue
            val videoId = item.optJSONObject("id")?.optString("videoId")?.trim().orEmpty()
            if (videoId.isBlank()) continue
            val snippet = item.optJSONObject("snippet") ?: continue
            val title = unescapeHtml(snippet.optString("title").ifBlank { "Unknown title" })
            val artist = unescapeHtml(snippet.optString("channelTitle").ifBlank { "YouTube" })
            val thumbs = snippet.optJSONObject("thumbnails")
            val artwork = thumbs?.optJSONObject("high")?.optString("url")?.ifBlank { null }
                ?: thumbs?.optJSONObject("medium")?.optString("url")?.ifBlank { null }
                ?: thumbs?.optJSONObject("default")?.optString("url")?.ifBlank { null }
            out.add(
                TrackSearchResult(
                    id = videoId,
                    title = title,
                    artist = artist,
                    album = "YouTube",
                    artworkUrl = artwork,
                    durationMs = 0L,
                    streamUrl = "pulse-yt://$videoId",
                    previewOnly = false,
                    provider = "youtube"
                )
            )
        }
        return out
    }

    private fun unescapeHtml(input: String): String = input
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/youtube/v3"
        private const val CACHE_MS = 10 * 60 * 1000L
    }
}
