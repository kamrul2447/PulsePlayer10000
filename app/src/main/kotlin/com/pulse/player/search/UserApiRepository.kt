package com.pulse.player.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

/**
 * User-configurable JSON music APIs.
 *
 * Endpoint rules:
 *   - HTTPS GET endpoint.
 *   - Put {query} or {q} in the URL to control the query parameter yourself.
 *   - When no placeholder exists Pulse appends ?q=... (or &q=...).
 *
 * The parser is deliberately tolerant because public APIs disagree about whether
 * a song is called "title", "name", "track", etc. Humanity apparently couldn't
 * standardize JSON field names, so Pulse has to compensate.
 */
data class UserApiEndpoint(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("enabled", enabled)
    }
}

class UserApiRepository {

    @Volatile
    private var endpoints: List<UserApiEndpoint> = emptyList()

    fun setFromJson(raw: String?) {
        val json = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: JSONArray()
        val out = ArrayList<UserApiEndpoint>(json.length())
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) continue
            val name = item.optString("name").trim().ifBlank { "Custom API ${i + 1}" }
            val id = item.optString("id").trim().ifBlank { stableId(name, url) }
            out += UserApiEndpoint(
                id = id,
                name = name,
                url = url,
                enabled = item.optBoolean("enabled", true)
            )
        }
        endpoints = out.distinctBy { it.id }
    }

    fun list(): List<UserApiEndpoint> = endpoints.toList()

    suspend fun search(query: String, limitPerApi: Int = 15): List<TrackSearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) return@withContext emptyList()

            val output = ArrayList<TrackSearchResult>()
            endpoints.filter { it.enabled }.forEach { api ->
                runCatching {
                    output += searchOne(api, q, limitPerApi)
                }
            }
            output
        }

    private fun searchOne(api: UserApiEndpoint, query: String, limit: Int): List<TrackSearchResult> {
        val requestUrl = buildQueryUrl(api.url, query)
        if (!requestUrl.startsWith("https://", true) && !requestUrl.startsWith("http://", true)) return emptyList()

        val body = httpGet(requestUrl)
        if (body.isBlank()) return emptyList()

        val root: Any = when {
            body.trimStart().startsWith("[") -> runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
            else -> runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        }

        val rows = extractRows(root)
        val out = ArrayList<TrackSearchResult>(minOf(limit, rows.size))
        for ((index, row) in rows.withIndex()) {
            if (out.size >= limit) break
            val parsed = parseTrack(api, row, index) ?: continue
            out += parsed
        }
        return out
    }

    private fun buildQueryUrl(base: String, query: String): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return when {
            base.contains("{query}", true) -> base.replace(Regex("\\{query\\}", RegexOption.IGNORE_CASE), encoded)
            base.contains("{q}", true) -> base.replace(Regex("\\{q\\}", RegexOption.IGNORE_CASE), encoded)
            base.contains("?") -> "$base&q=$encoded"
            else -> "$base?q=$encoded"
        }
    }

    private fun httpGet(urlString: String): String {
        val conn = (URL(urlString).openConnection() as? HttpURLConnection) ?: return ""
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.useCaches = true
            conn.setRequestProperty("Accept", "application/json, text/plain;q=0.8")
            conn.setRequestProperty("User-Agent", "PulsePlayer/1.0")
            val code = conn.responseCode
            val proto = conn.url.protocol.lowercase()
            if (proto != "https" && proto != "http") return ""
            val input = if (code in 200..299) conn.inputStream else conn.errorStream
            if (input == null) "" else input.bufferedReader(Charsets.UTF_8).use { it.readText().take(MAX_RESPONSE_CHARS) }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractRows(root: Any): List<Any> {
        if (root is JSONArray) {
            val direct = mutableListOf<Any>()
            for (i in 0 until root.length()) {
                val value = root.opt(i)
                if (value != null && value != JSONObject.NULL) direct += value
            }
            return direct
        }

        val obj = root as JSONObject
        val preferred = listOf("data", "results", "items", "tracks", "songs", "results.items", "data.items")
        for (key in preferred) {
            val candidate = nestedArray(obj, key) ?: continue
            val rows = extractRows(candidate)
            if (rows.isNotEmpty()) return rows
        }

        // Last resort: use the first useful array in the response.
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val candidate = obj.opt(key)
            if (candidate is JSONArray && candidate.length() > 0) return extractRows(candidate)
        }
        return emptyList()
    }

    private fun nestedArray(root: JSONObject, dotted: String): JSONArray? {
        var cur: Any? = root
        for (part in dotted.split('.')) {
            cur = when (cur) {
                is JSONObject -> cur.opt(part)
                else -> null
            }
        }
        return cur as? JSONArray
    }

    private fun parseTrack(api: UserApiEndpoint, row: Any, index: Int): TrackSearchResult? {
        if (row is String) {
            val stream = row.trim()
            if (!stream.startsWith("https://") && !stream.startsWith("http://")) return null
            return TrackSearchResult(
                id = "${api.id}:$index",
                title = "Track ${index + 1}",
                artist = api.name,
                streamUrl = stream,
                previewOnly = false,
                provider = api.id
            )
        }

        val obj = row as? JSONObject ?: return null
        val stream = firstString(obj, "streamUrl", "stream_url", "audioUrl", "audio_url", "mediaUrl", "media_url", "fileUrl", "file_url", "downloadUrl", "download_url", "url")
            ?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: firstUrl(obj, "audio", "media", "file", "download", "stream")
            ?: return null

        val title = firstString(obj, "title", "name", "track", "song", "filename", "fileName")
            ?: nestedString(obj, "track", "title")
            ?: nestedString(obj, "song", "title")
            ?: "Track ${index + 1}"
        val artist = firstString(obj, "artist", "artistName", "artist_name", "author", "uploader", "creator", "performer")
            ?: nestedString(obj, "user", "name")
            ?: nestedString(obj, "artist", "name")
            ?: api.name
        val album = firstString(obj, "album", "albumName", "album_name", "playlist", "playlist_name")
            ?: nestedString(obj, "album", "name")
            ?: ""
        val artwork = firstUrl(
            obj,
            "artworkUrl", "artwork_url", "artwork", "thumbnail", "thumbnailUrl",
            "image", "imageUrl", "image_url", "cover", "coverUrl", "cover_url", "albumArt"
        )
        val durationMs = parseDurationMs(obj)

        val rawId = firstString(obj, "id", "trackId", "track_id", "uuid", "key")
            .orEmpty().ifBlank { stream }
        val safeId = stableId(api.id, rawId)

        return TrackSearchResult(
            id = safeId,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artwork,
            durationMs = durationMs,
            streamUrl = stream,
            previewOnly = false,
            provider = api.id
        )
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                is String -> if (value.isNotBlank() && value != "null") return value
                is Number, is Boolean -> return value.toString()
            }
        }
        return null
    }

    private fun nestedString(obj: JSONObject, objectKey: String, childKey: String): String? =
        obj.optJSONObject(objectKey)?.optString(childKey)?.takeIf { it.isNotBlank() }

    private fun firstUrl(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key)
            val url = when (value) {
                is String -> value
                is JSONObject -> firstString(value, "url", "original", "src", "source", "large", "medium", "small")
                else -> null
            }?.trim()
            if (!url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))) return url
        }
        return null
    }

    private fun parseDurationMs(obj: JSONObject): Long {
        val ms = listOf("durationMs", "duration_ms", "durationMillis", "lengthMs")
            .asSequence()
            .mapNotNull { key -> obj.opt(key).toLongOrNullSafe() }
            .firstOrNull()
        if (ms != null) return ms.coerceAtLeast(0L)

        val seconds = listOf("duration", "length", "seconds", "durationSeconds")
            .asSequence()
            .mapNotNull { key -> obj.opt(key).toDoubleOrNullSafe() }
            .firstOrNull()
        return when {
            seconds == null -> 0L
            seconds > 100_000.0 -> seconds.toLong()
            else -> (seconds * 1000.0).toLong()
        }.coerceAtLeast(0L)
    }

    private fun Any?.toLongOrNullSafe(): Long? =
        when (this) {
            is Number -> this.toLong()
            is String -> this.trim().toLongOrNull()
            else -> null
        }

    private fun Any?.toDoubleOrNullSafe(): Double? =
        when (this) {
            is Number -> this.toDouble()
            is String -> this.trim().toDoubleOrNull()
            else -> null
        }

    private fun stableId(a: String, b: String): String {
        val clean = "$a:$b".lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9:_-]+"), "_")
        return clean.take(220)
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
