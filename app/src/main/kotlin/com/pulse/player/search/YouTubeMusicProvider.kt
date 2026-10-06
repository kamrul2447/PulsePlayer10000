package com.pulse.player.search

import android.net.Uri
import com.pulse.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Data API v3 catalogue search provider.
 * Supports keys set at build time via BuildConfig or dynamically in settings.
 */
class YouTubeMusicProvider : MusicSearchProvider {
    override val name: String = "YouTube"
    override val timeoutMs: Long = 8_000L

    @Volatile
    var userApiKey: String? = null

    override suspend fun search(query: String, limit: Int): List<TrackSearchResult> = withContext(Dispatchers.IO) {
        val apiKey = apiKey() ?: return@withContext emptyList()
        val url = Uri.parse(BASE_URL).buildUpon()
            .appendQueryParameter("part", "snippet")
            .appendQueryParameter("type", "video")
            .appendQueryParameter("q", query)
            .appendQueryParameter("maxResults", limit.coerceIn(1, 50).toString())
            .appendQueryParameter("key", apiKey)
            .build().toString()
        parseSearch(get(url), limit)
    }

    override suspend fun streamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
        val id = trackId.trim()
        if (id.isBlank()) return@withContext null
        if (id.startsWith("http://") || id.startsWith("https://")) id
        else "https://www.youtube.com/watch?v=" + Uri.encode(id)
    }

    private fun apiKey(): String? =
        userApiKey?.trim()?.takeIf { it.isNotBlank() } ?: BuildConfig.YOUTUBE_API_KEY.trim().takeIf { it.isNotBlank() }

    private fun get(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 7_000
            useCaches = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PulsePlayer/1.0")
        }
        return try {
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            if (input == null) return ""
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSearch(body: String, limit: Int): List<TrackSearchResult> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<TrackSearchResult>(minOf(limit, items.length()))
        for (i in 0 until minOf(limit, items.length())) {
            val item = items.optJSONObject(i) ?: continue
            val idObj = item.optJSONObject("id")
            val videoId = idObj?.optString("videoId")?.trim().orEmpty()
            if (videoId.isBlank()) continue

            val snippet = item.optJSONObject("snippet") ?: continue
            val rawTitle = snippet.optString("title").ifBlank { "Unknown title" }
            val title = unescapeHtml(rawTitle)
            val channelTitle = snippet.optString("channelTitle").ifBlank { "YouTube" }
            val artist = unescapeHtml(channelTitle)

            val thumbnails = snippet.optJSONObject("thumbnails")
            val artworkUrl = thumbnails?.optJSONObject("high")?.optString("url")
                ?.ifBlank { null }
                ?: thumbnails?.optJSONObject("medium")?.optString("url")?.ifBlank { null }
                ?: thumbnails?.optJSONObject("default")?.optString("url")?.ifBlank { null }

            val streamUrl = "https://www.youtube.com/watch?v=$videoId"
            out.add(
                TrackSearchResult(
                    id = videoId,
                    title = title,
                    artist = artist,
                    album = "YouTube",
                    artworkUrl = artworkUrl,
                    durationMs = 0L,
                    streamUrl = streamUrl,
                    previewOnly = false,
                    provider = "youtube"
                )
            )
        }
        return out
    }

    private fun unescapeHtml(input: String): String = input
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/youtube/v3/search"
    }
}
