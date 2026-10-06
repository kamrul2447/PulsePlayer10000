package com.pulse.player.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.pulse.player.media.Track

/**
 * Builds the Media3 representation of a Pulse Player [Track].
 *
 * The [MediaItem.mediaId] is always the track `uid`, which is the key the whole
 * app uses. The service can therefore rebuild any item from its id alone (see
 * PulsePlaybackService.onAddMediaItems), even if the client that asked for it is
 * a Bluetooth device or the notification.
 */
object MediaItemFactory {

    fun fromTrack(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.uid)
        .setUri(Uri.parse(track.contentUri))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(albumArtUri(track))
                .setIsPlayable(true)
                .setExtras(Bundle().apply {
                    putString(Track.EXTRA_UID, track.uid)
                    putString(Track.EXTRA_TITLE, track.title)
                    putString(Track.EXTRA_ARTIST, track.artist)
                    putString(Track.EXTRA_ALBUM, track.album)
                    putString(Track.EXTRA_ART_KEY, track.artKey)
                    putString("pulse.source", "local")
                })
                .build()
        )
        .build()

    /** An item that only carries an id — resolved by the service from the DB. */
    fun reference(uid: String): MediaItem = MediaItem.Builder()
        .setMediaId(uid)
        .setUri(Uri.EMPTY)
        .build()

    /**
     * System album-art URI. Media3's notification loads artwork through its own
     * BitmapLoader; pointing it at MediaStore keeps everything offline and
     * permission-scoped. When there is no album id we pass null rather than a
     * fake http URL, so nothing ever tries to hit the network for artwork.
     */
    private fun albumArtUri(track: Track): Uri? {
        val albumId = track.artKey?.substringAfter("album:", "")?.toLongOrNull()
            ?: track.albumId.substringAfter("album:", "").toLongOrNull()
        return if (albumId != null && albumId > 0L) {
            Uri.parse("content://media/external/audio/albumart/$albumId")
        } else {
            null
        }
    }

    /* ── repeat-mode mapping ─────────────────────────────────────────────
       Pulse UI order:  0 = off, 1 = repeat all, 2 = repeat one
       Media3 order:    0 = off, 1 = repeat one, 2 = repeat all
       Converted here so there is exactly one place that can get it wrong.  */

    fun repeatToMedia3(uiMode: Int): Int = when (uiMode) {
        1 -> androidx.media3.common.Player.REPEAT_MODE_ALL
        2 -> androidx.media3.common.Player.REPEAT_MODE_ONE
        else -> androidx.media3.common.Player.REPEAT_MODE_OFF
    }

    fun repeatFromMedia3(mode: Int): Int = when (mode) {
        androidx.media3.common.Player.REPEAT_MODE_ONE -> 2
        androidx.media3.common.Player.REPEAT_MODE_ALL -> 1
        else -> 0
    }
}
