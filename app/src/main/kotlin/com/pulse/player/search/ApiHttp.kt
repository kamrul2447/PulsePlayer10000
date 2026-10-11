package com.pulse.player.search

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedDeque

/** One network call made by an API provider. Only the HOST is ever stored (never the query or key). */
data class ApiCall(
    val at: Long,
    val provider: String,
    val host: String,
    val status: Int,
    val ms: Long,
    val error: String?
)

/** Small ring buffer of recent API calls, shown by "Scan APIs". */
object ApiActivityLog {
    private const val MAX = 60
    private val calls = ConcurrentLinkedDeque<ApiCall>()

    fun record(call: ApiCall) {
        calls.addFirst(call)
        while (calls.size > MAX) calls.pollLast()
    }

    fun toJson(limit: Int = 30): JSONArray {
        val array = JSONArray()
        calls.take(limit).forEach {
            array.put(
                JSONObject().apply {
                    put("at", it.at)
                    put("provider", it.provider)
                    put("host", it.host)
                    put("status", it.status)
                    put("ms", it.ms)
                    put("error", it.error ?: JSONObject.NULL)
                }
            )
        }
        return array
    }
}

/** Raised by providers with a message that is safe to show to the user. */
class ProviderException(message: String, val code: String = "error") : Exception(message)

data class ProviderCheck(val ok: Boolean, val message: String, val ms: Long = 0L)

/**
 * Shared GET helper. Compared with the per-provider copies it replaces it:
 *  - caps the response size (a hostile/huge custom API can no longer cause an OOM),
 *  - logs host/status/latency to [ApiActivityLog] (never URLs, never keys),
 *  - never follows a redirect from https to http.
 */
internal object ApiHttp {
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    data class Response(val code: Int, val body: String, val ms: Long)

    fun get(
        provider: String,
        urlString: String,
        connectMs: Int = 5_000,
        readMs: Int = 7_000,
        accept: String = "application/json"
    ): Response {
        val started = System.nanoTime()
        val host = hostOf(urlString)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectMs
                readTimeout = readMs
                instanceFollowRedirects = true
                useCaches = true
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "PulsePlayer/1.0")
            }
            val code = conn.responseCode
            if (conn.url.protocol.lowercase() != "https") {
                throw IOException("Insecure redirect blocked")
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readCapped(it) }.orEmpty()
            val ms = (System.nanoTime() - started) / 1_000_000
            ApiActivityLog.record(
                ApiCall(System.currentTimeMillis(), provider, host, code, ms, if (code in 200..299) null else "HTTP $code")
            )
            return Response(code, body, ms)
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - started) / 1_000_000
            ApiActivityLog.record(
                ApiCall(System.currentTimeMillis(), provider, host, -1, ms, t.javaClass.simpleName)
            )
            throw t
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) throw IOException("Response too large")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull().orEmpty().ifBlank { "unknown" }

    /** Friendly text for network failures. */
    fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "No internet or server not found"
        is java.net.SocketTimeoutException -> "Timed out"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        else -> t.message?.take(120) ?: t.javaClass.simpleName
    }
}
