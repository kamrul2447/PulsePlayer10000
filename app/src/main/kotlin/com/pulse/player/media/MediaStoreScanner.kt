package com.pulse.player.media

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContentResolverCompat
import androidx.core.content.ContextCompat
import androidx.core.os.CancellationSignal
import com.pulse.player.data.TrackEntity
import com.pulse.player.util.artistFromFileName
import com.pulse.player.util.orUnknown
import com.pulse.player.util.sha1
import com.pulse.player.util.titleFromFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore scanner for the real Pulse music library.
 *
 * The normal scan is audio-first. A second, tightly classified pass handles
 * genuine music-video downloads such as lyric MP4s, while rejecting camera,
 * screen-recording, movie, messaging, and game-video content.
 */
class MediaStoreScanner(private val context: Context) {

    suspend fun scan(
        onProgress: (read: Int) -> Unit = {},
        minDurationMs: Long = MIN_DURATION_MS
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val tracks = ArrayList<TrackEntity>(512)
        val now = System.currentTimeMillis()
        var read = 0

        val audioCursor = queryAudio(resolver)
            ?: throw IllegalStateException("MediaStore audio query failed; library was not changed.")
        audioCursor.use { cursor ->
            val idx = AudioColumnIndex.of(cursor)
            while (cursor.moveToNext()) {
                read++
                if (read % 250 == 0) onProgress(read)
                val entity = runCatching { readAudioRow(cursor, idx, now, minDurationMs) }.getOrNull()
                if (entity != null) tracks.add(entity)
            }
        }

        // Some legitimate music downloads, especially from SnapTube, are MP4
        // containers with AAC audio. MediaStore puts those in Video rather than
        // Audio, so a strict audio-only query would miss them completely.
        if (hasVideoPermission()) {
            queryVideoForMusic(resolver)?.use { cursor ->
                val idx = VideoColumnIndex.of(cursor)
                while (cursor.moveToNext()) {
                    read++
                    if (read % 100 == 0) onProgress(read)
                    val entity = runCatching { readMusicVideoRow(cursor, idx, now, minDurationMs) }.getOrNull()
                    if (entity != null) tracks.add(entity)
                }
            }
        }

        onProgress(read)
        tracks.distinctBy {
            it.path.takeIf(String::isNotBlank)
                ?: "${it.contentUri}|${it.displayName}|${it.sizeBytes}|${it.durationMs}"
        }
    }

    /**
     * Builds a manual-review queue from a user-selected file and nearby
     * MediaStore audio files that look related to it. Nothing is added to the
     * playable library until the user explicitly approves it.
     */
    suspend fun findSimilarReviewCandidates(
        selected: List<ReviewCandidate>
    ): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        if (selected.isEmpty()) return@withContext emptyList()
        val result = LinkedHashMap<String, ReviewCandidate>()
        selected.forEach { result[it.uid] = it }

        queryAudioForReview(context.contentResolver)?.use { cursor ->
            val idx = AudioReviewColumnIndex.of(cursor)
            while (cursor.moveToNext()) {
                val candidate = runCatching { readReviewAudioRow(cursor, idx) }.getOrNull() ?: continue
                if (selected.any { it.contentUri == candidate.contentUri }) continue
                if (selected.any { similarityScore(it, candidate) >= SIMILARITY_THRESHOLD }) {
                    result[candidate.uid] = candidate.copy(
                        reason = "Similar file found near the selected music"
                    )
                }
            }
        }

        // SnapTube music is often stored as video/* (AAC inside MP4). Include
        // those nearby candidates in Review as well, so selecting one file can
        // discover the other downloads from the same folder.
        if (hasVideoPermission()) {
            queryVideoForMusic(context.contentResolver)?.use { cursor ->
                val idx = VideoColumnIndex.of(cursor)
                while (cursor.moveToNext()) {
                    val candidate = runCatching { readReviewVideoRow(cursor, idx) }.getOrNull() ?: continue
                    if (selected.any { it.contentUri == candidate.contentUri }) continue
                    if (selected.any { similarityScore(it, candidate) >= SIMILARITY_THRESHOLD }) {
                        result[candidate.uid] = candidate.copy(
                            reason = "Similar music video/audio file found near the selected music"
                        )
                    }
                }
            }
        }
        result.values.take(MAX_REVIEW_CANDIDATES).toList()
    }

    private fun queryAudio(resolver: ContentResolver): Cursor? {
        val collection = audioCollection()
        val projection = audioProjection()
        return try {
            // IS_MUSIC is still the first gate. The stronger classifier below
            // removes game BGM/SFX and system sounds that OEM providers wrongly
            // mark as music.
            ContentResolverCompat.query(
                resolver,
                collection,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
                CancellationSignal()
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun canScanMusicVideos(): Boolean = hasVideoPermission()

    private fun hasVideoPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun queryVideoForMusic(resolver: ContentResolver): Cursor? {
        if (!hasVideoPermission()) return null
        return try {
            ContentResolverCompat.query(
                resolver,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection(),
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
                CancellationSignal()
            )
        } catch (_: Throwable) { null }
    }

    private fun readMusicVideoRow(
        cursor: Cursor,
        idx: VideoColumnIndex,
        now: Long,
        minDurationMs: Long
    ): TrackEntity? {
        val id = cursor.getLongOrNull(idx.id) ?: return null
        if (id <= 0L) return null
        val displayName = cursor.getStringOrNull(idx.displayName).orEmpty()
        val mimeType = cursor.getStringOrNull(idx.mimeType).orEmpty()
        if (!mimeType.startsWith("video/", true) || !isVideoLike(displayName, mimeType)) return null
        val duration = cursor.getLongOrNull(idx.duration) ?: 0L
        if (duration <= 0L || (minDurationMs > 0 && duration < minDurationMs)) return null
        val size = cursor.getLongOrNull(idx.size) ?: 0L
        if (size <= 0L) return null
        val relativePath = cursor.getStringOrNull(idx.relativePath).orEmpty()
        @Suppress("DEPRECATION")
        val dataPath = cursor.getStringOrNull(idx.data).orEmpty()
        val folder = relativePath.ifBlank { dataPath.substringBeforeLast('/', "") }
        if (isBlockedMediaPath(folder, dataPath)) return null

        val uri = videoContentUri(id)
        val meta = readVideoMetadata(uri)
        if (!meta.hasAudio) return null
        if (!looksLikeMusicVideo(displayName, meta.title, meta.artist, meta.album, folder)) return null

        val title = meta.title.orEmpty().takeIf { it.isNotBlank() }?.trim()
            ?: titleFromFileName(displayName.ifBlank { "Unknown title" })
        val artist = meta.artist.orUnknown(artistFromFileName(displayName) ?: "Unknown artist")
        val album = meta.album.orUnknown("Unknown album")
        return TrackEntity(
            uid = sha1("$folder|$displayName|$size|$duration"),
            mediaStoreId = id,
            title = title,
            artist = artist,
            album = album,
            albumId = "video-album:$id",
            artistId = "video-artist:$id",
            durationMs = duration,
            year = 0,
            trackNumber = 0,
            sizeBytes = size,
            mimeType = mimeType,
            displayName = displayName,
            path = dataPath,
            contentUri = uri,
            dateAdded = cursor.getLongOrNull(idx.dateAdded) ?: 0L,
            dateModified = cursor.getLongOrNull(idx.dateModified) ?: 0L,
            artKey = "video:$id",
            lastScannedAt = now,
            mediaType = "audio",
            isMusic = true,
            folder = folder,
            hidden = false
        )
    }

    private data class VideoMeta(val hasAudio: Boolean, val title: String?, val artist: String?, val album: String?)

    private fun readVideoMetadata(uriString: String): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, Uri.parse(uriString))
            VideoMeta(
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.equals("yes", true) == true,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            )
        } catch (_: Throwable) {
            VideoMeta(false, null, null, null)
        } finally { runCatching { retriever.release() } }
    }

    private fun looksLikeMusicVideo(displayName: String, title: String?, artist: String?, album: String?, folder: String): Boolean {
        val combined = "$displayName ${title.orEmpty()} ${artist.orEmpty()} ${album.orEmpty()} $folder".lowercase()
        if (isBlockedMediaPath(folder, "")) return false
        val strongMusic = listOf(
            "lyrics", "lyric", "official music", "official video",
            "music video", "audio", "song", "vevo", "karaoke", "cover"
        ).any { combined.contains(it) }
        val hasArtistTitle = displayName.contains(Regex("\\s[-–]\\s"))
        val snapTubePath = normalizePath(folder).contains("/snaptube/")
        val snapTubeMusic = snapTubePath && (
            strongMusic ||
                hasArtistTitle ||
                displayName.contains(Regex("\\(\\d{3,4}p\\)", RegexOption.IGNORE_CASE))
        )
        val musicFolder = listOf("/music/", "/songs/", "/audio/").any { normalizePath(folder).contains(it) }
        return strongMusic || hasArtistTitle || snapTubeMusic || musicFolder
    }

    private fun queryAudioForReview(resolver: ContentResolver): Cursor? {
        return try {
            ContentResolverCompat.query(
                resolver,
                audioCollection(),
                audioProjection(),
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
                CancellationSignal()
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun readReviewVideoRow(cursor: Cursor, idx: VideoColumnIndex): ReviewCandidate? {
        val id = cursor.getLongOrNull(idx.id) ?: return null
        val displayName = cursor.getStringOrNull(idx.displayName).orEmpty()
        val mimeType = cursor.getStringOrNull(idx.mimeType).orEmpty()
        if (!mimeType.startsWith("video/", true) || !isVideoLike(displayName, mimeType)) return null
        val duration = cursor.getLongOrNull(idx.duration) ?: 0L
        if (duration > 0L && duration < MIN_DURATION_MS) return null
        val size = cursor.getLongOrNull(idx.size) ?: 0L
        if (size <= 0L) return null
        val relativePath = cursor.getStringOrNull(idx.relativePath).orEmpty()
        @Suppress("DEPRECATION")
        val dataPath = cursor.getStringOrNull(idx.data).orEmpty()
        val folder = relativePath.ifBlank { dataPath.substringBeforeLast('/', "") }
        if (isBlockedMediaPath(folder, dataPath)) return null

        val uri = videoContentUri(id)
        val meta = readVideoMetadata(uri)
        if (!meta.hasAudio) return null
        if (!looksLikeMusicVideo(displayName, meta.title, meta.artist, meta.album, folder)) return null

        val title = meta.title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: titleFromFileName(displayName.ifBlank { "Unknown title" })
        val artist = meta.artist.orUnknown(artistFromFileName(displayName) ?: "Unknown artist")
        val album = meta.album.orUnknown("Unknown album")

        return ReviewCandidate(
            uid = sha1("review-video:$id"),
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            sizeBytes = size,
            mimeType = mimeType,
            displayName = displayName,
            contentUri = uri,
            path = dataPath,
            folder = folder,
            artKey = "video:$id",
            mediaStoreId = id,
            reason = "Music video/audio candidate",
            addedAt = System.currentTimeMillis()
        )
    }

    private fun readAudioRow(
        cursor: Cursor,
        idx: AudioColumnIndex,
        now: Long,
        minDurationMs: Long
    ): TrackEntity? {
        val id = cursor.getLongOrNull(idx.id) ?: return null
        if (id <= 0L) return null

        val displayName = cursor.getStringOrNull(idx.displayName).orEmpty()
        val mimeType = cursor.getStringOrNull(idx.mimeType).orEmpty()
        val audioExtension = isAudioExtension(displayName)
        // A few OEM MediaStore implementations leave MIME blank or slightly wrong.
        // Extension is only a secondary gate here. The existing duration/path/game
        // filters and video-track check still protect the library from random files.
        if (!mimeType.startsWith("audio/", ignoreCase = true) && !audioExtension) return null
        if (isVideoLike(displayName, mimeType)) return null

        val duration = cursor.getLongOrNull(idx.duration) ?: 0L
        if (minDurationMs > 0 && duration > 0L && duration < minDurationMs) return null
        val size = cursor.getLongOrNull(idx.size) ?: 0L
        if (size <= 0L && duration <= 0L) return null

        val title = cursor.getStringOrNull(idx.title)
            ?.takeIf { it.isNotBlank() && !it.equals(displayName, true) }
            ?: titleFromFileName(displayName.ifBlank { "Unknown title" })
        val artist = cursor.getStringOrNull(idx.artist)
            .orUnknown(artistFromFileName(displayName) ?: "Unknown artist")
        val album = cursor.getStringOrNull(idx.album).orUnknown("Unknown album")

        @Suppress("DEPRECATION")
        val dataPath = cursor.getStringOrNull(idx.data).orEmpty()
        val relativePath = cursor.getStringOrNull(idx.relativePath).orEmpty()
        val folder = relativePath.ifBlank { dataPath.substringBeforeLast('/', "") }

        // This is intentionally stricter than IS_MUSIC. Game engines often ship
        // .ogg/.mp3/.wav BGM and SFX which Android correctly recognizes as audio
        // but which are not songs from the user's point of view.
        if (isExcludedAudio(displayName, title, artist, album, folder, dataPath)) return null
        if (hasVideoTrack(audioContentUri(id))) return null

        return TrackEntity(
            uid = sha1("$folder|$displayName|$size|$duration"),
            mediaStoreId = id,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            albumId = "album:${cursor.getLongOrNull(idx.albumId) ?: id}",
            artistId = "artist:${cursor.getLongOrNull(idx.artistId) ?: id}",
            durationMs = duration,
            year = cursor.getIntOrNull(idx.year) ?: 0,
            trackNumber = cursor.getIntOrNull(idx.track) ?: 0,
            sizeBytes = size,
            mimeType = mimeType,
            displayName = displayName,
            path = dataPath,
            contentUri = audioContentUri(id),
            dateAdded = cursor.getLongOrNull(idx.dateAdded) ?: 0L,
            dateModified = cursor.getLongOrNull(idx.dateModified) ?: 0L,
            artKey = "audio:$id",
            lastScannedAt = now,
            mediaType = "audio",
            isMusic = true,
            folder = folder,
            hidden = false
        )
    }

    private fun readReviewAudioRow(cursor: Cursor, idx: AudioReviewColumnIndex): ReviewCandidate? {
        val id = cursor.getLongOrNull(idx.id) ?: return null
        val displayName = cursor.getStringOrNull(idx.displayName).orEmpty()
        val mimeType = cursor.getStringOrNull(idx.mimeType).orEmpty()
        if (!mimeType.startsWith("audio/", true)) return null
        if (isVideoLike(displayName, mimeType)) return null
        val duration = cursor.getLongOrNull(idx.duration) ?: 0L
        if (duration > 0L && duration < MIN_DURATION_MS) return null
        val size = cursor.getLongOrNull(idx.size) ?: 0L
        @Suppress("DEPRECATION")
        val dataPath = cursor.getStringOrNull(idx.data).orEmpty()
        val relativePath = cursor.getStringOrNull(idx.relativePath).orEmpty()
        val folder = relativePath.ifBlank { dataPath.substringBeforeLast('/', "") }
        val title = cursor.getStringOrNull(idx.title)
            ?.takeIf { it.isNotBlank() && !it.equals(displayName, true) }
            ?: titleFromFileName(displayName.ifBlank { "Unknown title" })
        val artist = cursor.getStringOrNull(idx.artist)
            .orUnknown(artistFromFileName(displayName) ?: "Unknown artist")
        val album = cursor.getStringOrNull(idx.album).orUnknown("Unknown album")
        if (isExcludedAudio(displayName, title, artist, album, folder, dataPath)) return null

        return ReviewCandidate(
            uid = sha1("review-media:$id"),
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration,
            sizeBytes = size,
            mimeType = mimeType,
            displayName = displayName,
            contentUri = audioContentUri(id),
            path = dataPath,
            folder = folder,
            artKey = "audio:$id",
            mediaStoreId = id,
            reason = "Audio file found during review scan",
            addedAt = System.currentTimeMillis()
        )
    }

    private fun similarityScore(a: ReviewCandidate, b: ReviewCandidate): Int {
        var score = 0
        val af = normalizePath(a.folder)
        val bf = normalizePath(b.folder)
        if (af.isNotBlank() && bf.isNotBlank() && af == bf) score += 60

        val an = normalizeName(a.displayName)
        val bn = normalizeName(b.displayName)
        if (an.startsWith("bgm") && bn.startsWith("bgm")) score += 60
        if (an.startsWith("bgmc") && bn.startsWith("bgmc")) score += 70
        if (a.artist != "Unknown artist" && a.artist.equals(b.artist, true)) score += 30
        if (a.album != "Unknown album" && a.album.equals(b.album, true)) score += 25
        if (a.durationMs > 0 && b.durationMs > 0) {
            val delta = kotlin.math.abs(a.durationMs - b.durationMs)
            if (delta <= 5_000L) score += 10
        }
        return score
    }

    private fun isAudioExtension(name: String): Boolean {
        val lower = name.lowercase()
        return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun isExcludedAudio(
        displayName: String,
        title: String,
        artist: String,
        album: String,
        folder: String,
        dataPath: String
    ): Boolean {
        if (isBlockedMediaPath(folder, dataPath)) return true
        val combined = "$displayName $title $artist $album".lowercase()
        val strongGameName = Regex("(^|[^a-z0-9])(bgmc|bgm|sfx|sound_effect|soundeffect|voiceover|dialogue|foley|ambience|ambient_sfx)([^a-z0-9]|$)")
            .containsMatchIn(combined)
        if (strongGameName) return true
        val lower = combined.replace('_', ' ').replace('-', ' ')
        if ((lower.contains("game") || lower.contains("gameplay")) &&
            (lower.contains("sound") || lower.contains("audio") || lower.contains("bgm"))) return true
        if (artist == "Unknown artist" && album == "Unknown album" &&
            (lower.contains("notification") || lower.contains("ringtone") || lower.contains("alarm"))) return true
        return false
    }

    private fun isBlockedMediaPath(folder: String, dataPath: String): Boolean {
        val path = normalizePath("$folder/$dataPath")
        val blocked = listOf(
            "/dcim/", "/camera/", "/screenrecord/", "/screen recorder/",
            "/whatsapp/video/", "/movies/", "/recordings/",
            "/android/data/", "/android/obb/", "/streamingassets/", "/assetbundles/"
        )
        return blocked.any { path.contains(it) }
    }

    private fun hasVideoTrack(uriString: String): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    ?.equals("yes", true) == true
            } ?: false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isVideoLike(displayName: String, mimeType: String): Boolean {
        val name = displayName.lowercase()
        val mime = mimeType.lowercase()
        return mime.startsWith("video/") || VIDEO_EXTENSIONS.any(name::endsWith)
    }

    private fun videoContentUri(id: Long): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)}/$id"
    } else "${MediaStore.Video.Media.EXTERNAL_CONTENT_URI}/$id"

    private fun videoProjection(): Array<String> = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.MIME_TYPE,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media._ID,
        @Suppress("DEPRECATION") MediaStore.Video.Media.DATA
    )

    private fun audioCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun audioProjection(): Array<String> = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.IS_MUSIC,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.YEAR,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media._ID,
        @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
    )

    private fun audioContentUri(id: Long): String = "${audioCollection()}/$id"

    private fun normalizePath(value: String): String = value.replace('\\', '/').trim('/').lowercase()
    private fun normalizeName(value: String): String = value.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]"), "")

    private class VideoColumnIndex(cursor: Cursor) {
        val id = cursor.safeIndex(MediaStore.Video.Media._ID)
        val duration = cursor.safeIndex(MediaStore.Video.Media.DURATION)
        val dateAdded = cursor.safeIndex(MediaStore.Video.Media.DATE_ADDED)
        val dateModified = cursor.safeIndex(MediaStore.Video.Media.DATE_MODIFIED)
        val size = cursor.safeIndex(MediaStore.Video.Media.SIZE)
        val displayName = cursor.safeIndex(MediaStore.Video.Media.DISPLAY_NAME)
        val mimeType = cursor.safeIndex(MediaStore.Video.Media.MIME_TYPE)
        val relativePath = cursor.safeIndex(MediaStore.Video.Media.RELATIVE_PATH)
        val data = cursor.safeIndex(MediaStore.Video.Media.DATA)
        companion object { fun of(c: Cursor) = VideoColumnIndex(c) }
    }

    private class AudioColumnIndex(cursor: Cursor) {
        val id = cursor.safeIndex(MediaStore.Audio.Media._ID)
        val title = cursor.safeIndex(MediaStore.Audio.Media.TITLE)
        val artist = cursor.safeIndex(MediaStore.Audio.Media.ARTIST)
        val album = cursor.safeIndex(MediaStore.Audio.Media.ALBUM)
        val albumId = cursor.safeIndex(MediaStore.Audio.Media.ALBUM_ID)
        val artistId = cursor.safeIndex(MediaStore.Audio.Media.ARTIST_ID)
        val duration = cursor.safeIndex(MediaStore.Audio.Media.DURATION)
        val dateAdded = cursor.safeIndex(MediaStore.Audio.Media.DATE_ADDED)
        val dateModified = cursor.safeIndex(MediaStore.Audio.Media.DATE_MODIFIED)
        val size = cursor.safeIndex(MediaStore.Audio.Media.SIZE)
        val displayName = cursor.safeIndex(MediaStore.Audio.Media.DISPLAY_NAME)
        val mimeType = cursor.safeIndex(MediaStore.Audio.Media.MIME_TYPE)
        val year = cursor.safeIndex(MediaStore.Audio.Media.YEAR)
        val track = cursor.safeIndex(MediaStore.Audio.Media.TRACK)
        val relativePath = cursor.safeIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        val data = cursor.safeIndex(MediaStore.Audio.Media.DATA)

        companion object { fun of(c: Cursor) = AudioColumnIndex(c) }
    }

    private class AudioReviewColumnIndex(cursor: Cursor) {
        val id = cursor.safeIndex(MediaStore.Audio.Media._ID)
        val title = cursor.safeIndex(MediaStore.Audio.Media.TITLE)
        val artist = cursor.safeIndex(MediaStore.Audio.Media.ARTIST)
        val album = cursor.safeIndex(MediaStore.Audio.Media.ALBUM)
        val duration = cursor.safeIndex(MediaStore.Audio.Media.DURATION)
        val size = cursor.safeIndex(MediaStore.Audio.Media.SIZE)
        val displayName = cursor.safeIndex(MediaStore.Audio.Media.DISPLAY_NAME)
        val mimeType = cursor.safeIndex(MediaStore.Audio.Media.MIME_TYPE)
        val relativePath = cursor.safeIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        val data = cursor.safeIndex(MediaStore.Audio.Media.DATA)

        companion object { fun of(c: Cursor) = AudioReviewColumnIndex(c) }
    }

    private val AUDIO_EXTENSIONS = setOf(
        ".mp3", ".m4a", ".m4b", ".aac", ".flac", ".wav", ".wave",
        ".ogg", ".oga", ".opus", ".amr", ".3gp", ".3ga", ".wma"
    )

    companion object {
        const val MIN_DURATION_MS = 15_000L
        private const val SIMILARITY_THRESHOLD = 55
        private const val MAX_REVIEW_CANDIDATES = 100
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".mov", ".3gp", ".m4v", ".avi")
    }
}

private fun Cursor.safeIndex(column: String): Int = getColumnIndex(column)
private fun Cursor.getStringOrNull(index: Int): String? = if (index >= 0 && !isNull(index)) getString(index) else null
private fun Cursor.getLongOrNull(index: Int): Long? = if (index >= 0 && !isNull(index)) getLong(index) else null
private fun Cursor.getIntOrNull(index: Int): Int? = if (index >= 0 && !isNull(index)) getInt(index) else null
