package com.pulse.player.ui

import android.app.Activity
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pulse.player.media.ArtworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Serves the whole app from inside the APK over a private origin:
 *
 *     https://pulse.local/index.html   → assets/web/index.html
 *     https://pulse.local/css/app.css  → assets/web/css/app.css
 *     https://pulse.local/art/<key>?s=320 → cached album artwork
 *
 * Two important properties:
 *  1. **Nothing else is navigable.** External page navigation is blocked. Audius
 *     artwork is allowed as an image subresource, while audio streaming stays
 *     in native Media3 and never routes through the WebView.
 *  2. **No local HTTP server.** Interception is cheaper, safer and uses no
 *     battery compared with running a loopback web server.
 */
class PulseWebViewClient(
    private val context: Context,
    private val artwork: ArtworkRepository
) : WebViewClient() {

    companion object {
        const val HOST = "pulse.local"
        const val ENTRY_URL = "https://$HOST/index.html"
    }


    /** Keep a WebView renderer failure from taking the whole Pulse process down.
     * The native Media3 service remains the source of playback truth and can keep
     * playing while the Activity recreates the UI.
     */
    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        val activity = context as? Activity
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            activity.runOnUiThread {
                runCatching { activity.recreate() }
            }
        }
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val host = request?.url?.host.orEmpty()
        return host != HOST
    }

    @Deprecated("Kept for old WebView implementations.")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val host = url?.let { android.net.Uri.parse(it).host }.orEmpty()
        return host != HOST
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url
        if (url == null || request.method != "GET") return blocked()
        if (url.host == HOST) return respond(url.path?.trimStart('/').orEmpty(), url.getQueryParameter("s"))
        if (isAllowedAudiusAsset(url)) return null
        return blocked()
    }

    @Deprecated("Kept for very old WebView implementations.")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        val uri = url?.let { android.net.Uri.parse(it) } ?: return blocked()
        if (uri.host == HOST) return respond(uri.path?.trimStart('/').orEmpty(), uri.getQueryParameter("s"))
        if (isAllowedAudiusAsset(uri)) return null
        return blocked()
    }

    private fun isAllowedAudiusAsset(uri: android.net.Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        // Online artwork only. Media3 receives Audius streams natively and never
        // routes them through the WebView. Navigation remains blocked separately.
        return host == "audius.co" || host.endsWith(".audius.co")
    }

    /* ───────────────────────────── routing ───────────────────────────── */

    private fun respond(rawPath: String, sizeParam: String?): WebResourceResponse {
        val path = rawPath.ifBlank { "index.html" }

        // ── artwork ──
        if (path.startsWith("art/")) {
            val key = path.removePrefix("art/").let { java.net.URLDecoder.decode(it, "UTF-8") }
            val size = ArtworkRepository.bucketSize(sizeParam?.toIntOrNull() ?: 320)
            val file = runCatching {
                runBlocking(Dispatchers.IO) { artwork.fileFor(key, size) }
            }.getOrNull()
            return if (file != null) fileResponse(file, "image/jpeg") else notFound()
        }

        // ── static assets ──
        val assetPath = "web/" + path.substringBefore('?')
        val stream = try {
            context.assets.open(assetPath)
        } catch (t: Throwable) {
            // Directory-style URL → index.html inside it.
            return try {
                val indexStream = context.assets.open("$assetPath/index.html")
                assetResponse(indexStream, "text/html")
            } catch (t2: Throwable) {
                notFound()
            }
        }
        return assetResponse(stream, mimeFor(assetPath))
    }

    /* ───────────────────────────── helpers ───────────────────────────── */

    private fun assetResponse(stream: InputStream, mime: String): WebResourceResponse =
        WebResourceResponse(mime, "utf-8", stream).apply {
            responseHeaders = mutableMapOf(
                "Cache-Control" to "no-cache",
                "X-Content-Type-Options" to "nosniff"
            )
        }

    private fun fileResponse(file: File, mime: String): WebResourceResponse = try {
        WebResourceResponse(mime, null, file.inputStream()).apply {
            responseHeaders = mutableMapOf(
                "Cache-Control" to "max-age=86400",
                "X-Content-Type-Options" to "nosniff"
            )
        }
    } catch (t: Throwable) {
        notFound()
    }

    /** External web navigation is blocked; only Audius artwork subresources are allowed. */
    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))).apply {
            setStatusCodeAndReasonPhrase(403, "Blocked")
        }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("Not found".toByteArray())).apply {
            setStatusCodeAndReasonPhrase(404, "Not found")
        }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "text/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }
}
