package com.pulse.player.media

import org.json.JSONObject

/**
 * A song discovered on the device.
 *
 * `uid` is the identity Pulse Player uses everywhere (queue, favorites, play
 * stats). It is derived from the file's location + name rather than the
 * MediaStore `_ID`, so it survives the `_ID` churn that happens when files are
 * moved between folders or the media provider is rebuilt.
 *
 * The JSON produced by [toJson] is the wire format consumed by the web UI —
 * see web/js/core/store.js. Keep the two in sync.
 */
data class Track(
    val uid: String,
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val durationMs: Long,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val sizeBytes: Long = 0L,
    val mimeType: String = "",
    val displayName: String = "",
    val path: String = "",
    val contentUri: String,
    val dateAdded: Long = 0L,
    val dateModified: Long = 0L,
    val artKey: String? = null,
    val artVersion: Int = 1,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val mediaType: String = "audio",
    val isMusic: Boolean = true,
    val folder: String = "",
    val hidden: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("id", mediaStoreId)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("albumId", albumId)
        put("artistId", artistId)
        put("duration", durationMs)
        put("year", year)
        put("track", trackNumber)
        put("size", sizeBytes)
        put("mimeType", mimeType)
        put("displayName", displayName)
        put("path", path)
        put("dateAdded", dateAdded)
        put("dateModified", dateModified)
        put("artKey", artKey ?: JSONObject.NULL)
        put("artVersion", artVersion)
        put("playCount", playCount)
        put("lastPlayedAt", lastPlayedAt)
        put("mediaType", mediaType)
        put("isMusic", isMusic)
        put("folder", folder)
        put("hidden", hidden)
        put("source", "local")
    }

    companion object {
        /** Keys used when a track travels through the JS bridge. */
        const val EXTRA_UID = "pulse.uid"
        const val EXTRA_TITLE = "pulse.title"
        const val EXTRA_ARTIST = "pulse.artist"
        const val EXTRA_ALBUM = "pulse.album"
        const val EXTRA_ART_KEY = "pulse.artKey"
    }
}

/** Result of a device scan. */
data class ScanResult(
    val tracks: List<Track>,
    val added: Int,
    val removed: Int,
    val skipped: Int
)
