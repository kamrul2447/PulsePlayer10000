package com.pulse.player.ads

import org.json.JSONObject
import java.util.Date

/* ═══════════════════════════════════════════════════════════════════════════
   FUTURE ADVERTISEMENT SYSTEM — DATA MODEL

   This file describes the contract a future, *separate* admin panel will feed.
   Nothing here is populated, requested or rendered in this release:
   AdConfig.enabled is false, AdRepository performs no network call, and the
   three UI slots stay hidden.

   No ad SDK is included. No banner may play audio or video, pause music, or
   block startup — those rules are enforced in AdPolicy and in the web renderer.
   ═════════════════════════════════════════════════════════════════════════ */

/** Where a banner can be shown. */
enum class AdPosition(val jsonName: String) {
    TOP("top"),
    BOTTOM("bottom"),
    /** Temporary takeover of the album-art area; the artwork returns afterwards. */
    ALBUM_ART("art")
}

/**
 * A creative, as it will arrive from the remote configuration endpoint.
 * Mirrors web/js/features/ads.js → AdCreative.
 */
data class AdCreative(
    val id: String,
    val name: String = "",
    val imageUrl: String = "",
    val clickUrl: String? = null,
    val position: AdPosition = AdPosition.BOTTOM,
    /** "6:1" for banners, "1:1" for the album-art slot. */
    val aspect: String = "6:1",
    /** Only meaningful for the temporary album-art position. */
    val displayDurationMs: Long = 15_000L,
    val enabled: Boolean = true,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val priority: Int = 0
) {
    fun isLive(now: Long = System.currentTimeMillis()): Boolean =
        enabled && imageUrl.isNotBlank() &&
            (startDate == null || now >= startDate) &&
            (endDate == null || now <= endDate)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("imageUrl", imageUrl)
        put("clickUrl", clickUrl ?: JSONObject.NULL)
        put("position", position.jsonName)
        put("aspect", aspect)
        put("displayDurationMs", displayDurationMs)
        put("enabled", enabled)
        put("startDate", startDate ?: JSONObject.NULL)
        put("endDate", endDate ?: JSONObject.NULL)
        put("priority", priority)
    }

    companion object {
        fun fromJson(json: JSONObject): AdCreative? = try {
            AdCreative(
                id = json.optString("id"),
                name = json.optString("name"),
                imageUrl = json.optString("imageUrl"),
                clickUrl = json.optStringOrNull("clickUrl"),
                position = AdPosition.values().firstOrNull { it.jsonName == json.optString("position") }
                    ?: AdPosition.BOTTOM,
                aspect = json.optString("aspect", "6:1"),
                displayDurationMs = json.optLong("displayDurationMs", 15_000L),
                enabled = json.optBoolean("enabled", true),
                startDate = json.optLongOrNull("startDate"),
                endDate = json.optLongOrNull("endDate"),
                priority = json.optInt("priority", 0)
            ).takeIf { it.id.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }
}

/** Rules that must hold no matter what a server sends. */
object AdPolicy {
    const val ALLOW_AUDIO = false
    const val ALLOW_VIDEO = false
    const val MAY_PAUSE_PLAYBACK = false
    const val MAY_BLOCK_STARTUP = false
    const val MAX_ART_DISPLAY_MS = 15_000L
}

/** Configuration for the (inactive) advertisement system. */
object AdConfig {
    /** Master switch — **false** in this release. Do not flip without a backend. */
    const val ENABLED: Boolean = false

    /** Remote configuration endpoint. Empty ⇒ [NoopAdProvider] is used. */
    const val ENDPOINT: String = ""

    const val FETCH_TIMEOUT_MS: Long = 6_000L
    const val MAX_CACHE_AGE_MS: Long = 15 * 60 * 1000L

    /** Which slots a future server is allowed to fill. */
    val POSITIONS: Map<AdPosition, Boolean> = mapOf(
        AdPosition.TOP to false,
        AdPosition.BOTTOM to false,
        AdPosition.ALBUM_ART to false
    )
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

/** Placeholder so the model can carry dates later without extra imports. */
@Suppress("unused")
fun adDate(millis: Long): Date = Date(millis)
