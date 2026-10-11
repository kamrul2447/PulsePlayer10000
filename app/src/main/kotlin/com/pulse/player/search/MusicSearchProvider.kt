package com.pulse.player.search

import com.pulse.player.ads.AdConfig
import com.pulse.player.util.isNetworkAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
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
 * Rules for any implementation:
 *   - never block the caller for more than [timeoutMs]
 *   - never require the internet for local playback
 *   - throw [ProviderException] with a user-readable message when something is
 *     wrong (missing key, quota, bad key). Do NOT return an empty list silently.
 *   - provider implementations may use a public client API key; never embed a
 *     bearer token or private credential.
 */
interface MusicSearchProvider {
    /** Stable machine id, used for settings keys and the UI ("audius", "youtube"). */
    val id: String get() = name.lowercase()
    val name: String
    val timeoutMs: Long get() = 8_000L
    suspend fun search(query: String, limit: Int): List<TrackSearchResult>

    /** Resolve a stable provider track ID into a playable stream URL. */
    suspend fun streamUrl(trackId: String): String? = null

    /** Cheap health check used by "Scan APIs". Must not burn meaningful quota. */
    suspend fun check(): ProviderCheck = ProviderCheck(true, "No check available")

    /** Where a key (if any) comes from: "user", "build" or "none". Null when no key is needed. */
    fun keySource(): String? = null
}

/** Default provider: no network, no results, no errors. */
object NoopMusicProvider : MusicSearchProvider {
    override val name: String = "None"
    override suspend fun search(query: String, limit: Int): List<TrackSearchResult> = emptyList<TrackSearchResult>()
}

/** Last known state of one API source, shown in Settings → Online & APIs. */
data class ApiStatus(
    val state: String,          // "ok" | "error" | "idle"
    val message: String,
    val at: Long = System.currentTimeMillis(),
    val ms: Long = 0L
)

/**
 * Entry point used by the UI bridge.
 *
 * [enabled] is the master switch; each built-in provider and each custom API
 * additionally has its own on/off switch.
 */
class MusicSearchRepository(
    private val contextProvider: () -> android.content.Context
) {

    @Volatile
    var enabled: Boolean = false
        private set

    private val providers = CopyOnWriteArrayList<MusicSearchProvider>()
    private val disabledProviders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val statuses = ConcurrentHashMap<String, ApiStatus>()
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
        providers.filterIsInstance<YouTubeMusicProvider>().firstOrNull()?.userApiKey = apiKey
    }

    fun setAudiusApiKey(apiKey: String?) {
        providers.filterIsInstance<AudiusMusicProvider>().firstOrNull()?.userApiKey = apiKey
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun setProviderEnabled(id: String, on: Boolean) {
        if (on) disabledProviders.remove(id) else disabledProviders.add(id)
    }

    fun isProviderEnabled(id: String): Boolean = id !in disabledProviders

    fun setUserApis(json: String?) {
        userApis.setFromJson(json)
    }

    fun userApis(): List<UserApiEndpoint> = userApis.list()

    fun providerName(): String {
        val customCount = userApis.list().count { it.enabled }
        val activeNames = providers.filter { it.name != "None" && isProviderEnabled(it.id) }.map { it.name }
        return when {
            activeNames.isEmpty() && customCount == 0 -> "None"
            customCount == 0 -> activeNames.joinToString(" + ")
            activeNames.isEmpty() -> "$customCount custom API${if (customCount == 1) "" else "s"}"
            else -> "${activeNames.joinToString(" + ")} + $customCount API${if (customCount == 1) "" else "s"}"
        }
    }

    suspend fun streamUrl(providerName: String, trackId: String): String? {
        if (providerName.isBlank()) return null
        val target = providers.firstOrNull {
            it.name.equals(providerName, ignoreCase = true) || it.id.equals(providerName, ignoreCase = true)
        } ?: return null
        return try {
            target.streamUrl(trackId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun search(query: String, limit: Int = 25): SearchOutcome = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchOutcome.Empty
        if (!enabled) return@withContext SearchOutcome.Disabled
        if (!contextProvider().isNetworkAvailable()) return@withContext SearchOutcome.Offline

        val activeProviders = providers.filter { it.name != "None" && isProviderEnabled(it.id) }
        val activeCustom = userApis.list().filter { it.enabled }
        if (activeProviders.isEmpty() && activeCustom.isEmpty()) {
            return@withContext SearchOutcome.Failed("All APIs are turned off. Turn one on in Settings → Online & APIs.")
        }

        val perProviderLimit = (limit / activeProviders.size.coerceAtLeast(1)).coerceAtLeast(10)
        val errors = ArrayList<String>()

        val results = coroutineScope {
            val providerJobs = activeProviders.map { p ->
                async {
                    val started = System.currentTimeMillis()
                    try {
                        val rows = withTimeoutOrNull(p.timeoutMs) { p.search(q, perProviderLimit) }
                        if (rows == null) {
                            statuses[p.id] = ApiStatus("error", "Timed out")
                            synchronized(errors) { errors += "${p.name}: timed out" }
                            emptyList<TrackSearchResult>()
                        } else {
                            statuses[p.id] = ApiStatus("ok", "${rows.size} result(s)", ms = System.currentTimeMillis() - started)
                            rows
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: ProviderException) {
                        statuses[p.id] = ApiStatus("error", e.message.orEmpty())
                        synchronized(errors) { errors += "${p.name}: ${e.message}" }
                        emptyList<TrackSearchResult>()
                    } catch (t: Throwable) {
                        val text = ApiHttp.describe(t)
                        statuses[p.id] = ApiStatus("error", text)
                        synchronized(errors) { errors += "${p.name}: $text" }
                        emptyList<TrackSearchResult>()
                    }
                }
            }
            val customJob = async {
                if (activeCustom.isEmpty()) emptyList<TrackSearchResult>()
                else try {
                    withTimeoutOrNull(10_000L) { userApis.search(q, limitPerApi = minOf(15, limit)) }.orEmpty()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    emptyList<TrackSearchResult>()
                }
            }
            providerJobs.awaitAll().flatten() + customJob.await()
        }

        val merged = results
            .distinctBy { "${it.provider}:${it.id}" }
            .take(limit.coerceIn(1, 50))

        when {
            merged.isNotEmpty() -> SearchOutcome.Results(merged)
            errors.isNotEmpty() -> SearchOutcome.Failed(errors.joinToString("\n"))
            else -> SearchOutcome.Empty
        }
    }

    /* ─────────────────────── API registry / scan ─────────────────────── */

    private fun keyMask(source: String?): String? = when (source) {
        "user" -> "your key"
        "build" -> "built-in key"
        else -> null
    }

    /** Everything the UI needs to list every API the app can talk to. */
    fun describeApis(): JSONArray {
        val out = JSONArray()
        providers.filter { it.name != "None" }.forEach { p ->
            val source = p.keySource()
            val needsKey = source != null
            val hasKey = source == "user" || source == "build"
            val status = statuses[p.id]
            out.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("kind", "builtin")
                put("host", when (p.id) {
                    "youtube" -> "googleapis.com"
                    "audius" -> "api.audius.co"
                    else -> p.name
                })
                put("enabled", isProviderEnabled(p.id))
                put("needsKey", needsKey)
                put("keyConfigured", hasKey)
                put("keySource", keyMask(source) ?: JSONObject.NULL)
                put("state", when {
                    needsKey && !hasKey -> "needs_key"
                    else -> status?.state ?: "idle"
                })
                put("message", when {
                    needsKey && !hasKey -> "No API key. Add one to use ${p.name}."
                    else -> status?.message ?: "Not checked yet"
                })
                put("ms", status?.ms ?: 0L)
                put("checkedAt", status?.at ?: 0L)
            })
        }
        userApis.list().forEach { api ->
            val status = statuses[api.id]
            out.put(JSONObject().apply {
                put("id", api.id)
                put("name", api.name)
                put("kind", "custom")
                put("host", ApiHttp.hostOf(api.url))
                put("enabled", api.enabled)
                put("needsKey", false)
                put("keyConfigured", false)
                put("keySource", JSONObject.NULL)
                put("state", status?.state ?: "idle")
                put("message", status?.message ?: "Not checked yet")
                put("ms", status?.ms ?: 0L)
                put("checkedAt", status?.at ?: 0L)
            })
        }
        // Ads are compiled out in this build; list them so nothing is hidden from the user.
        out.put(JSONObject().apply {
            put("id", "ads")
            put("name", "Ads")
            put("kind", "ads")
            put("host", if (AdConfig.ENDPOINT.isBlank()) "none" else ApiHttp.hostOf(AdConfig.ENDPOINT))
            put("enabled", AdConfig.ENABLED)
            put("needsKey", false)
            put("keyConfigured", false)
            put("keySource", JSONObject.NULL)
            put("state", "locked")
            put("message", if (AdConfig.ENABLED) "Enabled in this build" else "Turned off in this build. No ad requests are made.")
            put("ms", 0L)
            put("checkedAt", 0L)
        })
        return out
    }

    /** Health-check every API (or just [onlyId]) in parallel. */
    suspend fun scan(onlyId: String? = null) {
        if (!contextProvider().isNetworkAvailable()) {
            providers.forEach { statuses[it.id] = ApiStatus("error", "Offline") }
            userApis.list().forEach { statuses[it.id] = ApiStatus("error", "Offline") }
            return
        }
        coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
            providers.filter { it.name != "None" && (onlyId == null || it.id == onlyId) }.forEach { p ->
                jobs += async(Dispatchers.IO) {
                    val result = try {
                        withTimeoutOrNull(9_000L) { p.check() } ?: ProviderCheck(false, "Timed out")
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        ProviderCheck(false, ApiHttp.describe(t))
                    }
                    statuses[p.id] = ApiStatus(if (result.ok) "ok" else "error", result.message, ms = result.ms)
                }
            }
            userApis.list().filter { onlyId == null || it.id == onlyId }.forEach { api ->
                jobs += async(Dispatchers.IO) {
                    val result = try {
                        withTimeoutOrNull(9_000L) { userApis.check(api) } ?: ProviderCheck(false, "Timed out")
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        ProviderCheck(false, ApiHttp.describe(t))
                    }
                    statuses[api.id] = ApiStatus(if (result.ok) "ok" else "error", result.message, ms = result.ms)
                }
            }
            jobs.awaitAll()
        }
    }

    fun activityJson(): JSONArray = ApiActivityLog.toJson()
}

sealed interface SearchOutcome {
    data object Disabled : SearchOutcome
    data object Offline : SearchOutcome
    data object Empty : SearchOutcome
    data class Failed(val message: String) : SearchOutcome
    data class Results(val items: List<TrackSearchResult>) : SearchOutcome
}
