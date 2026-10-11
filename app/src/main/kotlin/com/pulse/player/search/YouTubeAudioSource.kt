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
import java.util.concurrent.TimeUnit

const val YOUTUBE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

interface YouTubeAudioSource {
    suspend fun search(query: String, limit: Int): List<TrackSearchResult>
    suspend fun resolveAudioUrl(videoId: String): String?
}

class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val data = request.dataToSend()
        val body = if (data != null && data.isNotEmpty()) {
            data.toRequestBody()
        } else if (httpMethod.equals("POST", ignoreCase = true)) {
            ByteArray(0).toRequestBody()
        } else {
            null
        }

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
        val service = ServiceList.YouTube
        val searchExtractor = service.getSearchExtractor(query) as SearchExtractor
        try {
            searchExtractor.fetchPage()
        } catch (t: Throwable) {
            throw ProviderException(explain(t), "search_error")
        }

        val page = searchExtractor.initialPage ?: return@withContext emptyList()
        val items = page.items
        if (items.isNullOrEmpty()) return@withContext emptyList()

        val results = ArrayList<TrackSearchResult>()
        for (item in items) {
            if (results.size >= limit.coerceIn(1, 50)) break
            if (item is StreamInfoItem) {
                val videoId = extractVideoId(item.url)
                if (videoId.isBlank()) continue
                val title = item.name.orEmpty().ifBlank { "Unknown title" }
                val artist = item.uploaderName.orEmpty().ifBlank { "YouTube" }
                val artworkUrl = item.thumbnails?.firstOrNull()?.url
                val durationMs = (item.duration.coerceAtLeast(0L)) * 1000L

                results.add(
                    TrackSearchResult(
                        id = videoId,
                        title = title,
                        artist = artist,
                        album = "YouTube",
                        artworkUrl = artworkUrl,
                        durationMs = durationMs,
                        streamUrl = "pulse-yt://$videoId",
                        previewOnly = false,
                        provider = "youtube"
                    )
                )
            }
        }
        results
    }

    override suspend fun resolveAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        init()
        val id = videoId.trim()
            .removePrefix("pulse-yt://")
            .removePrefix("https://www.youtube.com/watch?v=")
            .removePrefix("https://youtu.be/")
        if (id.isBlank()) return@withContext null

        val watchUrl = "https://www.youtube.com/watch?v=$id"
        val service = ServiceList.YouTube
        val streamExtractor = try {
            service.getStreamExtractor(watchUrl)
        } catch (t: Throwable) {
            throw ProviderException(explain(t), "stream_error")
        }

        try {
            streamExtractor.fetchPage()
        } catch (t: Throwable) {
            throw ProviderException(explain(t), "stream_error")
        }

        if (streamExtractor.streamType == StreamType.LIVE_STREAM) {
            throw ProviderException("Live streams are not supported for audio playback.", "live_stream")
        }

        val audioStreams = streamExtractor.audioStreams
        if (audioStreams.isNullOrEmpty()) {
            throw ProviderException("No audio stream available for this video.", "no_audio")
        }

        val bestStream = audioStreams
            .filter { !it.url.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }

        bestStream?.url ?: throw ProviderException("Could not resolve audio stream URL.", "stream_error")
    }

    private fun extractVideoId(url: String): String {
        val u = url.trim()
        return when {
            u.contains("v=") -> u.substringAfter("v=").substringBefore("&")
            u.contains("youtu.be/") -> u.substringAfter("youtu.be/").substringBefore("?")
            else -> u
        }
    }

    private fun explain(t: Throwable): String = when (t) {
        is GeographicRestrictionException -> "This video is not available in your region."
        is ContentNotAvailableException -> "This video is unavailable or has been removed."
        is PaidContentException -> "This video is paid content and cannot be played."
        else -> when {
            t.message?.contains("Age restricted", ignoreCase = true) == true -> "This video is age-restricted."
            t.message?.contains("Sign in", ignoreCase = true) == true -> "This video requires YouTube sign-in."
            else -> t.message?.take(120) ?: "YouTube service error."
        }
    }
}
