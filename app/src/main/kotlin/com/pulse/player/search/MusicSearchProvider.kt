package com.pulse.player.search

import com.pulse.player.util.isNetworkAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** One result row rendered by the Search screen. */
data class TrackSearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    /** Where the audio lives, when the provider can actually stream it. */
    val streamUrl: String? = null,
    /** True for short previews (some catalogues only allow 30 s clips). */
    val previewOnly: Boolean = true,
    val provider: String = ""
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

/**
 * Replaceable music-provider interface.
 *
 * The first release ships with no provider: [NoopMusicProvider] answers "no
 * results" instantly and offline, and the UI explains that online search is not
 * configured (see web/js/features/search.js). When a legitimate catalogue API
 * becomes available, implement this interface and register it in
 * [MusicSearchRepository.use] — nothing else in the app changes.
 *
 * Rules for any implementation:
 *   • never block the caller for more than [timeoutMs]
 *   • never require the internet for local playback
 *   • provider implementations may use a public client API key; never embed a bearer token or private credential.
 */
interface MusicSearchProvider {
    val name: String
    val timeoutMs: Long get() = 8_000L
    suspend fun search(query: String, limit: Int): List<TrackSearchResult>

    /** Resolve a stable provider track ID into a playable stream URL. */
    suspend fun streamUrl(trackId: String): String? = null
}

/** Default provider: no network, no results, no errors. */
object NoopMusicProvider : MusicSearchProvider {
    override val name: String = "None"
    override suspend fun search(query: String, limit: Int): List<TrackSearchResult> = emptyList()
}

/**
 * Entry point used by the UI bridge.
 *
 * [enabled] is false by default and can only be turned on from Settings; even
 * then, a missing provider or a dead network produces a graceful "unavailable"
 * state rather than an error screen.
 */
class MusicSearchRepository(
    private val contextProvider: () -> android.content.Context
) {

    @Volatile
    var enabled: Boolean = false
        private set

    private val providers = CopyOnWriteArrayList<MusicSearchProvider>()
    private val userApis = UserApiRepository()

    fun use(provider: MusicSearchProvider) {
        providers.clear()
        providers.add(provider)
    }

    fun use(providers: List<MusicSearchProvider>) {
        this.providers.clear()
        this.providers.addAll(providers)
    }

    fun setYouTubeApiKey(apiKey: String?) {
        val yt = providers.filterIsInstance<YouTubeMusicProvider>().firstOrNull()
        yt?.userApiKey = apiKey
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun setUserApis(json: String?) {
        userApis.setFromJson(json)
    }

    fun userApis(): List<UserApiEndpoint> = userApis.list()

    fun providerName(): String {
        val customCount = userApis.list().size
        val activeNames = providers.filter { it.name != "None" }.map { it.name }
        return when {
            activeNames.isEmpty() && customCount == 0 -> "None"
            customCount == 0 -> activeNames.joinToString(" + ")
            activeNames.isEmpty() -> "$customCount custom API${if (customCount == 1) "" else "s"}"
            else -> "${activeNames.joinToString(" + ")} + $customCount API${if (customCount == 1) "" else "s"}"
        }
    }

    suspend fun streamUrl(providerName: String, trackId: String): String? {
        if (providerName.isBlank()) return null
        val target = providers.firstOrNull { it.name.equals(providerName, ignoreCase = true) } ?: return null
        return runCatching { target.streamUrl(trackId) }.getOrNull()
    }

    suspend fun search(query: String, limit: Int = 25): SearchOutcome = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchOutcome.Empty
        if (!enabled) return@withContext SearchOutcome.Disabled
        if (!contextProvider().isNetworkAvailable()) return@withContext SearchOutcome.Offline

        val perProviderLimit = (limit / providers.size.coerceAtLeast(1)).coerceAtLeast(10)
        val providerJobs = providers.map { p ->
            async {
                withTimeoutOrNull(p.timeoutMs) {
                    runCatching { p.search(q, perProviderLimit) }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }
        val customJob = async {
            withTimeoutOrNull(10_000L) {
                runCatching { userApis.search(q, limitPerApi = minOf(15, limit)) }.getOrDefault(emptyList())
            }.orEmpty()
        }

        val providerResults = providerJobs.flatMap { it.await() }
        val customResults = customJob.await()

        val results = (providerResults + customResults)
            .distinctBy { "${it.provider}:${it.id}" }
            .take(limit.coerceIn(1, 50))

        if (results.isEmpty()) SearchOutcome.Empty
        else SearchOutcome.Results(results)
    }
}

sealed interface SearchOutcome {
    data object Disabled : SearchOutcome
    data object Offline : SearchOutcome
    data object Empty : SearchOutcome
    data class Failed(val message: String) : SearchOutcome
    data class Results(val items: List<TrackSearchResult>) : SearchOutcome
}
