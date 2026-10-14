package com.pulse.player.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URI
import java.util.concurrent.TimeUnit

const val YOUTUBE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

interface YouTubeAudioSource {
    suspend fun search(query: String, limit: Int): List<TrackSearchResult>
    suspend fun resolveAudioUrl(videoId: String): String?
    suspend fun loadPlaylist(
        url: String,
        limit: Int = 1000,
        onProgress: ((YouTubePlaylist, Boolean) -> Unit)? = null
    ): YouTubePlaylist
    suspend fun related(videoId: String, limit: Int = 15): List<TrackSearchResult>
}

data class YouTubePlaylist(
    val title: String,
    val tracks: List<TrackSearchResult>
)

class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val data = request.dataToSend()
        val body = if (data != null && data.isNotEmpty()) {
            data.toRequestBody()
        } else if (httpMethod.equals("POST", ignoreCase = true)) {
            ByteArray(0).toRequestBody()
        } else null

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(httpMethod, body)

        request.headers().forEach { (key, values) ->
            values.forEach { value -> builder.addHeader(key, value) }
        }
        if (request.headers()["User-Agent"] == null) {
            builder.header("User-Agent", YOUTUBE_USER_AGENT)
        }

        val response = client.newCall(builder.build()).execute()
        val responseBody = response.body?.string().orEmpty()
        val responseHeaders = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            responseHeaders[name] = response.headers.values(name)
        }
        return Response(
            response.code,
            response.message,
            responseHeaders,
            responseBody,
            response.request.url.toString()
        )
    }
}

object NewPipeYouTubeAudioSource : YouTubeAudioSource {
    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            NewPipe.init(OkHttpDownloader(client))
            initialized = true
        }
    }

    override suspend fun search(query: String, limit: Int): List<TrackSearchResult> = withContext(Dispatchers.IO) {
        init()
        val extractor = ServiceList.YouTube.getSearchExtractor(query) as SearchExtractor
        try {
            extractor.fetchPage()
        } catch (t: Throwable) {
            throw ProviderException("YouTube search failed: " + explain(t), "search_error")
        }
        extractor.initialPage?.items.orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(::toResult)
            .distinctBy { it.id }
            .take(limit.coerceIn(1, 50))
    }

    override suspend fun loadPlaylist(
        url: String,
        limit: Int,
        onProgress: ((YouTubePlaylist, Boolean) -> Unit)?
    ): YouTubePlaylist = withContext(Dispatchers.IO) {
        init()
        val normalizedUrl = normalizePlaylistUrl(url)
        val extractor = try {
            ServiceList.YouTube.getPlaylistExtractor(normalizedUrl)
        } catch (t: Throwable) {
            throw ProviderException("That YouTube playlist link could not be opened.", "playlist_url")
        }

        try {
            extractor.fetchPage()
        } catch (t: Throwable) {
            throw ProviderException("YouTube could not load this playlist: " + explain(t), "playlist_error")
        }

        val title = extractor.name.trim().ifBlank { "YouTube playlist" }
        val maximum = limit.coerceIn(1, 1000)
        val result = ArrayList<TrackSearchResult>()
        val seen = HashSet<String>()
        var page = extractor.initialPage
            ?: throw ProviderException("YouTube returned no playlist page.", "empty_playlist")
        var pagesRead = 0

        while (result.size < maximum) {
            page.items.orEmpty().filterIsInstance<StreamInfoItem>().forEach { item ->
                if (result.size >= maximum) return@forEach
                val track = toResult(item) ?: return@forEach
                if (seen.add(track.id)) result.add(track)
            }
            pagesRead++

            val nextPage = page.nextPage
            val complete = result.size >= maximum || nextPage == null
            // Show the first page immediately, then batch updates to avoid
            // repeatedly serializing the whole playlist after every tiny page.
            if (result.isNotEmpty() && (pagesRead == 1 || pagesRead % 3 == 0 || complete)) {
                onProgress?.invoke(YouTubePlaylist(title, result.toList()), complete)
            }
            if (complete) break

            page = try {
                extractor.getPage(nextPage!!)
            } catch (t: Throwable) {
                if (result.isNotEmpty()) {
                    // Keep already collected tracks usable if a later page fails.
                    onProgress?.invoke(YouTubePlaylist(title, result.toList()), true)
                    break
                }
                throw ProviderException("YouTube could not read this playlist: " + explain(t), "playlist_error")
            }
        }

        if (result.isEmpty()) {
            throw ProviderException("No playable videos were found in this playlist.", "empty_playlist")
        }
        YouTubePlaylist(title, result.take(maximum))
    }

    override suspend fun related(videoId: String, limit: Int): List<TrackSearchResult> = withContext(Dispatchers.IO) {
        init()
        val id = cleanVideoId(videoId)
        if (id.isBlank()) return@withContext emptyList()

        val extractor = try {
            ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        try {
            extractor.fetchPage()
        } catch (_: Throwable) {
            return@withContext emptyList()
        }

        // Keep YouTube/NewPipe's own related-video order. It is the best available
        // native signal for similarity and popularity without a developer API key.
        extractor.relatedItems?.items.orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(::toResult)
            .filter { it.id != id && it.durationMs > 0L }
            .distinctBy { it.id }
            .take(limit.coerceIn(1, 25))
    }

    override suspend fun resolveAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        init()
        val id = cleanVideoId(videoId)
        if (id.isBlank()) return@withContext null
        val extractor = try {
            ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        } catch (t: Throwable) {
            throw ProviderException("Could not open this YouTube video: " + explain(t), "stream_error")
        }
        try {
            extractor.fetchPage()
        } catch (t: Throwable) {
            throw ProviderException("Could not load this YouTube video: " + explain(t), "stream_error")
        }

        if (extractor.streamType == StreamType.LIVE_STREAM) {
            throw ProviderException("Live streams are not supported for audio playback.", "live_stream")
        }
        extractor.audioStreams.orEmpty()
            .filter { !it.url.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate.takeIf { bitrate -> bitrate > 0 } ?: it.bitrate }
            ?.url
            ?: throw ProviderException("Could not resolve the YouTube audio stream.", "no_audio")
    }

    private fun toResult(item: StreamInfoItem): TrackSearchResult? {
        val id = cleanVideoId(item.url)
        if (id.isBlank()) return null
        return TrackSearchResult(
            id = id,
            title = item.name.orEmpty().ifBlank { "Unknown title" },
            artist = item.uploaderName.orEmpty().ifBlank { "YouTube" },
            album = "YouTube",
            artworkUrl = item.thumbnails?.firstOrNull()?.url,
            durationMs = item.duration.coerceAtLeast(0L) * 1000L,
            streamUrl = "pulse-yt://$id",
            previewOnly = false,
            provider = "youtube"
        )
    }

    private fun normalizePlaylistUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw ProviderException("Paste a valid YouTube playlist link.", "playlist_url")
        val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https") ||
            host !in setOf("youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) {
            throw ProviderException("Paste a YouTube playlist link.", "playlist_url")
        }
        val id = Regex("[?&]list=([^&]+)", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            ?: throw ProviderException("This YouTube link does not contain a playlist ID.", "playlist_url")
        return "https://www.youtube.com/playlist?list=$id"
    }

    private fun cleanVideoId(raw: String): String {
        val value = raw.trim().removePrefix("pulse-yt://")
        val candidates = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})").find(value)?.groupValues?.getOrNull(1),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(value)?.groupValues?.getOrNull(1),
            Regex("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})").find(value)?.groupValues?.getOrNull(1),
            value.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun explain(t: Throwable): String = when (t) {
        is GeographicRestrictionException -> "The video is not available in your region."
        is ContentNotAvailableException -> "The video is unavailable or has been removed."
        is PaidContentException -> "The video is paid content and cannot be played."
        else -> when {
            t.message?.contains("Age restricted", ignoreCase = true) == true -> "The video is age-restricted."
            t.message?.contains("Sign in", ignoreCase = true) == true -> "The video requires YouTube sign-in."
            else -> t.message?.take(150) ?: "YouTube service error."
        }
    }
}
