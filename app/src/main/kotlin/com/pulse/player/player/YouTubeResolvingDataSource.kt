package com.pulse.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.pulse.player.search.NewPipeYouTubeAudioSource
import com.pulse.player.search.YOUTUBE_USER_AGENT
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns placeholder URIs (pulse-yt://VIDEO_ID) into real audio stream URLs right before
 * ExoPlayer loads them. Every other scheme (content://, file://, http(s)) passes through
 * untouched, so local songs and other APIs keep working.
 */
object YouTubeUrlResolver : ResolvingDataSource.Resolver {
    private const val TTL_MS = 4 * 60 * 60 * 1000L          // stream URLs expire after ~6 h
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    /** Forget a cached URL (called after a 403/410 so the next open re-resolves). */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    @UnstableApi
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != "pulse-yt") return dataSpec

        val id = uri.toString().removePrefix("pulse-yt://").substringBefore("?").substringBefore("&")
        if (id.isBlank()) throw IOException("Missing YouTube video id")

        val now = System.currentTimeMillis()
        val cached = cache[id]?.takeIf { now - it.second < TTL_MS }?.first
        val url = cached ?: resolve(id)
        cache[id] = url to now
        return dataSpec.buildUpon().setUri(Uri.parse(url)).build()
    }

    private fun resolve(id: String): String {
        val url = try {
            runBlocking { NewPipeYouTubeAudioSource.resolveAudioUrl(id) }
        } catch (e: Exception) {
            // Keep the real message (live stream, age-restricted, offline...) so the UI can show it.
            throw IOException(e.message ?: "YouTube error", e)
        }
        if (url.isNullOrBlank()) throw IOException("Could not resolve audio stream")
        return url
    }
}

@UnstableApi
class YouTubeResolvingDataSourceFactory(context: Context) : DataSource.Factory {
    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(YOUTUBE_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(10_000)

    // DefaultDataSource understands content://, file://, asset:// and http(s)://.
    // Using DefaultHttpDataSource alone is what broke local songs ("Source error").
    private val upstream = DefaultDataSource.Factory(context.applicationContext, http)

    override fun createDataSource(): DataSource =
        ResolvingDataSource(upstream.createDataSource(), YouTubeUrlResolver)
}
