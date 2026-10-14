package com.pulse.player.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Search and play YouTube music through NewPipeExtractor, without Data API keys. */
class YouTubeMusicProvider {
    private val source: YouTubeAudioSource = NewPipeYouTubeAudioSource
    private val cache = object : LinkedHashMap<String, Pair<Long, List<TrackSearchResult>>>(16, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, List<TrackSearchResult>>>?
        ): Boolean = size > 24
    }

    suspend fun search(query: String, limit: Int): List<TrackSearchResult> = withContext(Dispatchers.IO) {
        val cacheKey = query.trim().lowercase() + "|" + limit
        synchronized(cache) {
            cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let {
                return@withContext it.second
            }
        }
        val rows = source.search(query, limit)
        synchronized(cache) { cache[cacheKey] = System.currentTimeMillis() to rows }
        rows
    }

    suspend fun streamUrl(trackId: String): String? = source.resolveAudioUrl(trackId)
    suspend fun loadPlaylist(
        url: String,
        limit: Int,
        onProgress: ((YouTubePlaylist, Boolean) -> Unit)? = null
    ): YouTubePlaylist = source.loadPlaylist(url, limit, onProgress)
    suspend fun related(videoId: String, limit: Int): List<TrackSearchResult> = source.related(videoId, limit)

    companion object {
        private const val CACHE_MS = 5 * 60 * 1000L
    }
}
