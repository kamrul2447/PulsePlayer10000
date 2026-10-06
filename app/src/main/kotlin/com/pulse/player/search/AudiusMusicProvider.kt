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
 * Read-only Audius catalogue provider. The client uses the public API key only.
 * No Bearer token is read, stored, or sent by the Android app.
 */
class AudiusMusicProvider : MusicSearchProvider {
    override val name: String = "Audius"
    override val timeoutMs: Long = 8_000L

    override suspend fun search(query: String, limit: Int): List<TrackSearchResult> = withContext(Dispatchers.IO) {
        val apiKey = apiKey() ?: return@withContext emptyList()
        val url = Uri.parse(BASE_URL + "/v1/tracks/search").buildUpon()
            .appendQueryParameter("query", query)
            .appendQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .appendQueryParameter("api_key", apiKey)
            .build().toString()
        parseSearch(get(url), apiKey, limit)
    }

    override suspend fun streamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
        val id = trackId.trim()
        val apiKey = apiKey() ?: return@withContext null
        if (id.isBlank()) return@withContext null
        Uri.parse(BASE_URL + "/v1/tracks/" + Uri.encode(id) + "/stream").buildUpon()
            .appendQueryParameter("api_key", apiKey)
            .build().toString()
    }

    private fun apiKey(): String? = BuildConfig.AUDIUS_API_KEY.trim().takeIf { it.isNotBlank() }

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

    private fun parseSearch(body: String, apiKey: String, limit: Int): List<TrackSearchResult> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<TrackSearchResult>(minOf(limit, data.length()))
        for (i in 0 until minOf(limit, data.length())) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) continue
            val title = item.optString("title").ifBlank { "Unknown title" }
            val user = item.optJSONObject("user")
            val artist = user?.optString("name").orEmpty().ifBlank { "Unknown artist" }
            val album = item.optString("playlist_name")
                .ifBlank { item.optJSONObject("playlist")?.optString("playlist_name").orEmpty() }
            val durationMs = (item.optDouble("duration", 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
            val artwork = parseArtwork(item.opt("artwork"))
            val streamable = streamableValue(item.opt("is_streamable"))
                ?: streamableValue(item.opt("isStreamable"))
                ?: true
            val gated = item.optBoolean("is_stream_gated", false) ||
                item.optBoolean("isStreamGated", false) ||
                item.optJSONObject("stream_conditions") != null ||
                item.optJSONObject("streamConditions") != null
            if (!streamable || gated) continue
            val streamUrl = Uri.parse(BASE_URL + "/v1/tracks/" + Uri.encode(id) + "/stream").buildUpon()
                .appendQueryParameter("api_key", apiKey).build().toString()
            out.add(TrackSearchResult(
                id = id, title = title, artist = artist, album = album, artworkUrl = artwork,
                durationMs = durationMs, streamUrl = streamUrl, previewOnly = false, provider = "audius"
            ))
        }
        return out
    }

    private fun streamableValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }

    private fun parseArtwork(value: Any?): String? {
        val obj = value as? JSONObject ?: return null
        return listOf(
            "_1000x1000", "_480x480", "_150x150", "original",
            "1000x1000", "480x480", "150x150"
        ).asSequence()
            .mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            .firstOrNull()
    }

    companion object {
        private const val BASE_URL = "https://api.audius.co"
    }
}
