package com.pulse.player.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/* ─────────────────────────── strings & ids ─────────────────────────── */

/** Stable SHA-1 hex of [value]; used for track identity and cache keys. */
fun sha1(value: String): String {
    return try {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        value.hashCode().toString(16)
    }
}

/** Falls back to [fallback] for the "<unknown>" values MediaStore loves. */
fun String?.orUnknown(fallback: String = "Unknown"): String {
    val v = this?.trim().orEmpty()
    if (v.isEmpty()) return fallback
    if (v.equals("unknown", ignoreCase = true)) return fallback
    if (v.equals("<unknown>", ignoreCase = true)) return fallback
    if (v.equals("null", ignoreCase = true)) return fallback
    return v
}

/** Title guess from a file name: strips the extension and a "01 - " prefix. */
fun titleFromFileName(displayName: String): String {
    val noExt = displayName.substringBeforeLast('.', displayName)
    val noTrackNumber = noExt.replace(Regex("^\\s*\\d{1,3}\\s*[-._]\\s*"), "")
    return noTrackNumber.trim().ifEmpty { noExt.trim() }
}

/** "Artist - Title.mp3" → "Artist". Returns null when there is no separator. */
fun artistFromFileName(displayName: String): String? {
    val noExt = displayName.substringBeforeLast('.', displayName)
    val parts = noExt.split(Regex("\\s[-–]\\s"), limit = 2)
    return if (parts.size == 2) parts[0].trim().takeIf { it.isNotEmpty() } else null
}

/* ─────────────────────────── collections ─────────────────────────── */

fun <T> List<T>.chunkedForBulk(size: Int = 400): List<List<T>> = chunked(size)

/* ─────────────────────────── network ─────────────────────────── */

/**
 * Best-effort connectivity check. Pulse Player is offline-first: this is only
 * consulted by *optional* features (online music search, future remote config),
 * never by local playback.
 */
fun Context.isNetworkAvailable(): Boolean {
    return try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    } catch (t: Throwable) {
        false
    }
}

/* ─────────────────────────── json safety ─────────────────────────── */

/** Never let a malformed payload from the JS layer crash the app. */
inline fun <T> jsonSafe(default: T, block: () -> T): T {
    return try {
        block()
    } catch (t: Throwable) {
        default
    }
}

/**
 * Escapes a string so it can be embedded in a JavaScript single-quoted literal.
 * The bridge pushes JSON into the page through evaluateJavascript(), so this
 * must handle quotes, backslashes, control characters and the two characters
 * that are illegal in JS string literals even when escaped (U+2028/2029).
 */
fun jsQuote(value: String): String {
    val sb = StringBuilder(value.length + 16)
    sb.append('\'')
    for (ch in value) {
        when {
            ch == '\\' -> sb.append("\\\\")
            ch == '\'' -> sb.append("\\'")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '\u2028' -> sb.append("\\u2028")
            ch == '\u2029' -> sb.append("\\u2029")
            ch < ' ' -> sb.append("\\u%04x".format(Locale.US, ch.code))
            else -> sb.append(ch)
        }
    }
    sb.append('\'')
    return sb.toString()
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
