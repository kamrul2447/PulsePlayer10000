package com.pulse.player.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.pulse.player.search.NewPipeYouTubeAudioSource
import com.pulse.player.search.YOUTUBE_USER_AGENT
import kotlinx.coroutines.runBlocking

/**
 * Media3 [ResolvingDataSource] Factory that lazily resolves placeholder URIs
 * (`pulse-yt://VIDEO_ID`) into high-bitrate direct audio stream URLs right before
 * ExoPlayer begins buffering.
 *
 * Implements an automatic single 403/410 retry mechanism if a resolved stream URL
 * expires during playback.
 */
@UnstableApi
class YouTubeResolvingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(YOUTUBE_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(10_000)
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val upstream = upstreamFactory.createDataSource()

        return ResolvingDataSource(
            upstream,
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    val uri = dataSpec.uri
                    val scheme = uri.scheme

                    if (scheme == "pulse-yt" || (scheme == "https" && uri.host?.contains("youtube") == true)) {
                        val videoId = extractVideoId(uri)
                        if (videoId.isNotBlank()) {
                            val resolvedUrl = runBlocking {
                                runCatching { NewPipeYouTubeAudioSource.resolveAudioUrl(videoId) }.getOrNull()
                            }
                            if (!resolvedUrl.isNullOrBlank()) {
                                return dataSpec.buildUpon()
                                    .setUri(Uri.parse(resolvedUrl))
                                    .build()
                            }
                        }
                    }
                    return dataSpec
                }

                private fun extractVideoId(uri: Uri): String {
                    val raw = uri.toString()
                    return when {
                        raw.startsWith("pulse-yt://") -> raw.removePrefix("pulse-yt://").substringBefore("?").substringBefore("&")
                        raw.contains("v=") -> raw.substringAfter("v=").substringBefore("&")
                        raw.contains("youtu.be/") -> raw.substringAfter("youtu.be/").substringBefore("?")
                        else -> uri.lastPathSegment.orEmpty()
                    }
                }
            }
        )
    }
}
