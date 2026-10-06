package com.pulse.player.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A file waiting for the user to decide whether it really is music. */
data class ReviewCandidate(
    val uid: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val displayName: String,
    val contentUri: String,
    val path: String,
    val folder: String,
    val artKey: String?,
    val mediaStoreId: Long,
    val reason: String,
    val addedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("sizeBytes", sizeBytes)
        put("mimeType", mimeType)
        put("displayName", displayName)
        put("contentUri", contentUri)
        put("path", path)
        put("folder", folder)
        put("artKey", artKey ?: JSONObject.NULL)
        put("mediaStoreId", mediaStoreId)
        put("reason", reason)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ReviewCandidate = ReviewCandidate(
            uid = o.optString("uid"),
            title = o.optString("title", "Unknown title"),
            artist = o.optString("artist", "Unknown artist"),
            album = o.optString("album", "Unknown album"),
            durationMs = o.optLong("durationMs", 0L),
            sizeBytes = o.optLong("sizeBytes", 0L),
            mimeType = o.optString("mimeType", ""),
            displayName = o.optString("displayName", "Unknown file"),
            contentUri = o.optString("contentUri", ""),
            path = o.optString("path", ""),
            folder = o.optString("folder", ""),
            artKey = if (o.isNull("artKey")) null else o.optString("artKey", ""),
            mediaStoreId = o.optLong("mediaStoreId", 0L),
            reason = o.optString("reason", "Manual review"),
            addedAt = o.optLong("addedAt", System.currentTimeMillis())
        )
    }
}

/** Small native-only persistent queue. No new dependency and no Room schema change. */
class ReviewRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pulse_review_queue", Context.MODE_PRIVATE)
    private val lock = Any()

    fun getAll(): List<ReviewCandidate> = synchronized(lock) {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) { for (i in 0 until array.length()) add(ReviewCandidate.fromJson(array.getJSONObject(i))) }
        }.getOrDefault(emptyList())
    }

    fun upsertAll(items: List<ReviewCandidate>) = synchronized(lock) {
        if (items.isEmpty()) return@synchronized
        val map = LinkedHashMap<String, ReviewCandidate>()
        getAll().forEach { map[it.uid] = it }
        items.forEach { map[it.uid] = it }
        val array = JSONArray()
        map.values.take(MAX_ITEMS).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun remove(uid: String) = synchronized(lock) {
        val array = JSONArray()
        getAll().filterNot { it.uid == uid }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX_ITEMS = 200
    }
}
