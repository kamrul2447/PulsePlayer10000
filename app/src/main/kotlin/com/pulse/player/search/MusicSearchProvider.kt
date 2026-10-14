package com.pulse.player.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.pulse.player.util.isNetworkAvailable
import org.json.JSONObject

data class TrackSearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val streamUrl: String? = null,
    val previewOnly: Boolean = false,
    val provider: String = "youtube"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("artworkUrl", artworkUrl ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("streamUrl", streamUrl ?: JSONObject.NULL)
        put("previewOnly", previewOnly)
        put("provider", provider)
        put("source", "online")
    }
}

/** Online catalog: YouTube's extractor only. No API key or custom endpoint. */
class MusicSearchRepository(private val contextProvider: () -> android.content.Context) {
    @Volatile
    private var enabled = true
    private val youtube = YouTubeMusicProvider()

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun providerName(): String = "YouTube"

    suspend fun search(query: String, limit: Int = 25): SearchOutcome = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchOutcome.Empty
        if (!enabled) return@withContext SearchOutcome.Disabled
        if (!contextProvider().isNetworkAvailable()) return@withContext SearchOutcome.Offline
        try {
            val rows = withTimeoutOrNull(15_000L) {
                youtube.search(q, limit.coerceIn(1, 50))
            } ?: return@withContext SearchOutcome.Failed("YouTube took too long to respond. Try again.")
            if (rows.isEmpty()) SearchOutcome.Empty else SearchOutcome.Results(rows)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SearchOutcome.Failed(error.message?.take(200) ?: "YouTube search failed.")
        }
    }

    suspend fun streamUrl(providerName: String, trackId: String): String? {
        if (!providerName.equals("youtube", ignoreCase = true)) return null
        return youtube.streamUrl(trackId)
    }

    suspend fun loadYouTubePlaylist(
        url: String,
        limit: Int = 150,
        onProgress: ((YouTubePlaylist, Boolean) -> Unit)? = null
    ): YouTubePlaylist = youtube.loadPlaylist(url, limit, onProgress)

    suspend fun relatedYouTube(videoId: String, limit: Int = 15): List<TrackSearchResult> =
        youtube.related(videoId, limit)
}

class ProviderException(message: String, val code: String = "provider") : Exception(message)

sealed interface SearchOutcome {
    data object Disabled : SearchOutcome
    data object Offline : SearchOutcome
    data object Empty : SearchOutcome
    data class Failed(val message: String) : SearchOutcome
    data class Results(val items: List<TrackSearchResult>) : SearchOutcome
}
