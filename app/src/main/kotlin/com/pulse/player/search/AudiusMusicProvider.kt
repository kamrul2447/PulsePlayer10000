package com.pulse.player.search

import android.net.Uri
import com.pulse.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only Audius catalogue provider. Uses the public Audius client API key only
 * (never a bearer token). The key can come from the build or from Settings.
 */
class AudiusMusicProvider : MusicSearchProvider {
    override val id: String = "audius"
    override val name: String = "Audius"
    override val timeoutMs: Long = 8_000L

    @Volatile
    var userApiKey: String? = null

    override fun keySource(): String =
        when {
            !userApiKey?.trim().isNullOrBlank() -> "user"
            BuildConfig.AUDIUS_API_KEY.trim().isNotBlank() -> "build"
            else -> "none"
        }

    private fun apiKey(): String? =
        userApiKey?.trim()?.takeIf { it.isNotBlank() }
            ?: BuildConfig.AUDIUS_API_KEY.trim().takeIf { it.isNotBlank() }

    override suspend fun search(query: String, limit: Int): List<TrackSearchResult> = withContext(Dispatchers.IO) {
        val apiKey = apiKey() ?: throw ProviderException(
            "Audius needs its public API key. Add it in Settings → Online & APIs.", "no_key"
        )
        val url = Uri.parse("$BASE_URL/v1/tracks/search").buildUpon()
            .appendQueryParameter("query", query)
            .appendQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .appendQueryParameter("api_key", apiKey)
            .build().toString()
        val r = ApiHttp.get("audius", url)
        if (r.code !in 200..299) {
            throw ProviderException(
                when (r.code) {
                    401, 403 -> "Audius rejected the API key."
                    429 -> "Audius is rate limiting requests. Try again shortly."
                    else -> "Audius error ${r.code}"
                }, "http"
            )
        }
        parseSearch(r.body, apiKey, limit)
    }

    override suspend fun streamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
        val id = trackId.trim()
        val apiKey = apiKey() ?: return@withContext null
        if (id.isBlank()) return@withContext null
        Uri.parse("$BASE_URL/v1/tracks/" + Uri.encode(id) + "/stream").buildUpon()
            .appendQueryParameter("api_key", apiKey)
            .build().toString()
    }

    override suspend fun check(): ProviderCheck = withContext(Dispatchers.IO) {
        val apiKey = apiKey() ?: return@withContext ProviderCheck(false, "No API key")
        val url = Uri.parse("$BASE_URL/v1/tracks/search").buildUpon()
            .appendQueryParameter("query", "a")
            .appendQueryParameter("limit", "1")
            .appendQueryParameter("api_key", apiKey)
            .build().toString()
        val r = ApiHttp.get("audius", url)
        if (r.code in 200..299) ProviderCheck(true, "Reachable (${r.ms} ms)", r.ms)
        else ProviderCheck(false, "Audius error ${r.code}", r.ms)
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
            val artist = item.optJSONObject("user")?.optString("name").orEmpty().ifBlank { "Unknown artist" }
            val album = item.optString("playlist_name")
                .ifBlank { item.optJSONObject("playlist")?.optString("playlist_name").orEmpty() }
            val durationMs = (item.optDouble("duration", 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
            val artwork = parseArtwork(item.opt("artwork"))
            val streamable = streamableValue(item.opt("is_streamable"))
                ?: streamableValue(item.opt("isStreamable")) ?: true
            val gated = item.optBoolean("is_stream_gated", false) ||
                item.optBoolean("isStreamGated", false) ||
                item.optJSONObject("stream_conditions") != null ||
                item.optJSONObject("streamConditions") != null
            if (!streamable || gated) continue
            val streamUrl = Uri.parse("$BASE_URL/v1/tracks/" + Uri.encode(id) + "/stream").buildUpon()
                .appendQueryParameter("api_key", apiKey).build().toString()
            out.add(
                TrackSearchResult(
                    id = id, title = title, artist = artist, album = album, artworkUrl = artwork,
                    durationMs = durationMs, streamUrl = streamUrl, previewOnly = false, provider = "audius"
                )
            )
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
        return listOf("_1000x1000", "_480x480", "_150x150", "original", "1000x1000", "480x480", "150x150")
            .asSequence()
            .mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            .firstOrNull()
    }

    companion object {
        private const val BASE_URL = "https://api.audius.co"
    }
}
