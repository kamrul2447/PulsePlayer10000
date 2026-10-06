package com.pulse.player.ads

import com.pulse.player.bridge.BridgeProtocol
import com.pulse.player.util.EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Interface for whatever supplies creatives in the future. */
interface AdProvider {
    val name: String
    suspend fun fetch(position: AdPosition): List<AdCreative>
}

/** Shipped default: no requests, no data, no cost. */
object NoopAdProvider : AdProvider {
    override val name: String = "noop"
    override suspend fun fetch(position: AdPosition): List<AdCreative> = emptyList()
}

/**
 * Skeleton of a future remote provider.
 *
 * It is only ever constructed when [AdConfig.ENABLED] is true *and* an endpoint
 * is configured, so this release performs zero ad networking. Even when it is
 * enabled later it will:
 *   • use HTTPS only
 *   • time out quickly and fail silently (ads must never break the player)
 *   • return an empty list on any error
 */
class RemoteAdProvider(private val endpoint: String) : AdProvider {

    override val name: String = "remote"

    private var cache: List<AdCreative> = emptyList()
    private var cacheAt = 0L
    private val mutex = Mutex()

    override suspend fun fetch(position: AdPosition): List<AdCreative> = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now - cacheAt < AdConfig.MAX_CACHE_AGE_MS && cache.isNotEmpty()) {
            return cache.filter { it.position == position && it.isLive(now) }
        }
        val downloaded = withContext(Dispatchers.IO) { download() } ?: return emptyList()
        cache = downloaded
        cacheAt = now
        return downloaded.filter { it.position == position && it.isLive(now) }
    }

    private fun download(): List<AdCreative>? {
        if (!endpoint.startsWith("https://")) return null   // HTTPS only, always
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = AdConfig.FETCH_TIMEOUT_MS.toInt()
            connection.readTimeout = AdConfig.FETCH_TIMEOUT_MS.toInt()
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val array: JSONArray = json.optJSONArray("ads") ?: return emptyList()
            val list = ArrayList<AdCreative>(array.length())
            for (i in 0 until array.length()) {
                AdCreative.fromJson(array.optJSONObject(i) ?: continue)?.let { list.add(it) }
            }
            list
        } catch (t: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Facade used by the app. With [AdConfig.ENABLED] false every method is a
 * no-op: no threads, no sockets, no UI.
 */
object AdRepository {

    private var provider: AdProvider = NoopAdProvider

    fun active(): Boolean = AdConfig.ENABLED && provider !== NoopAdProvider

    fun useProvider(provider: AdProvider) {
        this.provider = provider
    }

    /** Called once during startup. */
    suspend fun init() {
        if (!AdConfig.ENABLED) {
            provider = NoopAdProvider
            publish()
            return
        }
        if (AdConfig.ENDPOINT.isNotBlank()) {
            provider = RemoteAdProvider(AdConfig.ENDPOINT)
        }
        publish()
    }

    suspend fun creativesFor(position: AdPosition): List<AdCreative> {
        if (!AdConfig.ENABLED) return emptyList()
        if (AdConfig.POSITIONS[position] != true) return emptyList()
        return withTimeoutOrNull(AdConfig.FETCH_TIMEOUT_MS) {
            runCatching { provider.fetch(position) }.getOrNull()
        } ?: emptyList()
    }

    /** Pushes the current configuration to the UI (usually "disabled"). */
    fun publish() {
        EventBus.emit(
            BridgeProtocol.EVENT_ADS,
            "enabled" to AdConfig.ENABLED,
            "provider" to provider.name,
            "slots" to JSONObject().apply {
                AdConfig.POSITIONS.forEach { (position, allowed) ->
                    put(position.jsonName, allowed && AdConfig.ENABLED)
                }
            }
        )
    }
}
