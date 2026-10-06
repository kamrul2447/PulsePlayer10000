package com.pulse.player.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts, down-sizes and caches album artwork.
 *
 * Pipeline: disk cache → MediaStore album-art thumbnail → embedded tag picture
 * → give up (the web UI then draws its own branded cover, so there is never a
 * broken image).
 *
 * Artwork is decoded at the size the UI asks for (never the full-resolution
 * original) and stored as a JPEG in the app cache, so scrolling a long list
 * does not hold dozens of 3000×3000 bitmaps in memory.
 */
class ArtworkRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    private val cacheDir: File = File(appContext.cacheDir, "art-v3").apply { mkdirs() }

    /** Small memory index so repeated requests for the same key skip file I/O. */
    private val memory = object : LruCache<String, File>(128) {}

    /** One decode per key at a time. */
    private val locks = HashMap<String, Mutex>()

    /**
     * Returns a cached JPEG for [key], creating it if needed.
     * @param key "album:<id>", "audio:<mediaStoreId>" or "video:<mediaStoreId>"
     * @param sizePx longest edge of the requested image
     */
    suspend fun fileFor(key: String, sizePx: Int): File? = withContext(Dispatchers.IO) {
        val size = sizePx.coerceIn(48, 1024)
        val cached = memory.get(cacheKey(key, size))
        if (cached != null && cached.exists()) return@withContext cached

        val file = File(cacheDir, cacheKey(key, size) + ".jpg")
        if (file.exists() && file.length() > 0L) {
            memory.put(cacheKey(key, size), file)
            return@withContext file
        }

        val mutex = synchronized(locks) { locks.getOrPut(cacheKey(key, size)) { Mutex() } }
        mutex.withLock {
            if (file.exists() && file.length() > 0L) return@withContext file
            val bitmap = decode(key, size) ?: return@withContext null
            write(bitmap, file)
            memory.put(cacheKey(key, size), file)
            file
        }
    }

    /** Clears decoded artwork. Called from Settings → "Clear artwork cache". */
    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
        memory.evictAll()
    }

    /* ─────────────────────────── decoding ─────────────────────────── */

    private fun decode(key: String, sizePx: Int): Bitmap? {
        val uriKey = key.substringAfter("uri:", "").takeIf { key.startsWith("uri:") }
        if (uriKey != null) {
            val uri = Uri.parse(uriKey)
            embeddedArtwork(uri.toString(), sizePx)?.let { return it }
        }

        val albumId = key.substringAfter("album:", "").toLongOrNull()
        if (albumId != null && albumId > 0L) {
            albumArtThumbnail(albumId, sizePx)?.let { return it }
        }
        val audioId = key.substringAfter("audio:", "").toLongOrNull()
        if (audioId != null && audioId > 0L) {
            audioArtwork(audioId, sizePx)?.let { return it }
        }

        val videoId = key.substringAfter("video:", "").toLongOrNull()
        if (videoId != null && videoId > 0L) {
            videoThumbnail(videoId, sizePx)?.let { return it }
        }

        // Some OEM MediaProviders expose an MP4 in the Video table but do not
        // implement loadThumbnail reliably. The filename/ID key may still point
        // to a playable video, so no-op here and let the WebView fallback cover
        // the last-resort case rather than returning a broken image.

        val downloadId = key.substringAfter("download:", "").toLongOrNull()
        if (downloadId != null && downloadId > 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadUri = "${MediaStore.Downloads.EXTERNAL_CONTENT_URI}/$downloadId"
            embeddedArtwork(downloadUri, sizePx)?.let { return it }
        }

        return null
    }

    /**
     * Audio artwork is resolved from several Android sources. Some devices only
     * expose embedded art on one track in an album, so after checking the current
     * file we inspect a small number of sibling tracks from the same album. This
     * stays inside READ_MEDIA_AUDIO and avoids requesting photo/storage access just
     * to display a music cover.
     */
    private fun audioArtwork(audioId: Long, sizePx: Int): Bitmap? {
        val audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)}/$audioId"
        } else {
            "${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/$audioId"
        }

        embeddedArtwork(audioUri, sizePx)?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                resolver.loadThumbnail(android.net.Uri.parse(audioUri), Size(sizePx, sizePx), null)
                    ?.takeIf { it.width > 0 && it.height > 0 }
                    ?.let { return it }
            } catch (_: Throwable) {
                // Provider may not implement thumbnails for audio rows.
            }
        }

        val albumId = queryAlbumId(audioId)
        if (albumId > 0L) {
            albumArtThumbnail(albumId, sizePx)?.let { return it }
            embeddedArtworkFromAlbum(albumId, audioId, sizePx)?.let { return it }
        }
        return null
    }

    private fun videoThumbnail(videoId: Long, sizePx: Int): Bitmap? {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri.parse("${MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)}/$videoId")
        } else {
            Uri.parse("${MediaStore.Video.Media.EXTERNAL_CONTENT_URI}/$videoId")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                resolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }?.let { return it }
        }

        // Samsung and a few other MediaProviders can expose the video row but
        // fail loadThumbnail(). Descriptor-based MediaMetadataRetriever is the
        // reliable fallback and also works for AAC-in-MP4 music videos.
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val candidates = listOf(1_000_000L, 500_000L, 2_000_000L, 0L)
                candidates.firstNotNullOfOrNull { timeUs ->
                    runCatching {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull()
                }
            }
        } catch (_: Throwable) { null }
        finally { runCatching { retriever.release() } }
    }

    private fun queryAlbumId(audioId: Long): Long {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(audioId.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun embeddedArtworkFromAlbum(albumId: Long, excludeAudioId: Long, sizePx: Int): Bitmap? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.ALBUM_ID} = ? AND ${MediaStore.Audio.Media._ID} != ?",
                arrayOf(albumId.toString(), excludeAudioId.toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                var checked = 0
                while (c.moveToNext() && checked++ < 8) {
                    val id = c.getLong(0)
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "${MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)}/$id"
                    } else {
                        "${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/$id"
                    }
                    embeddedArtwork(uri, sizePx)?.let { return@use it }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun albumArtThumbnail(albumId: Long, sizePx: Int): Bitmap? {
        val albumUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Albums.getContentUri(MediaStore.VOLUME_EXTERNAL).buildUpon().appendPath(albumId.toString()).build()
        } else {
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI.buildUpon().appendPath(albumId.toString()).build()
        }

        // 1) Modern, correctly-sized thumbnail (Android 10+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val thumb = resolver.loadThumbnail(albumUri, Size(sizePx, sizePx), null)
                if (thumb.width > 0 && thumb.height > 0) return thumb
            } catch (t: Throwable) {
                // Fall through to the legacy path.
            }
        }

        // 2) Legacy: the ALBUM_ART column points at a file we can decode.
        return try {
            val projection = arrayOf(MediaStore.Audio.Albums.ALBUM_ART)
            resolver.query(albumUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val path = cursor.getString(0) ?: return@use null
                decodeSampled(File(path), sizePx)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Reads embedded album art using a real content FileDescriptor.
     *  Samsung/OEM MediaProviders can fail when MediaMetadataRetriever is given
     *  a Context + content Uri directly, while the descriptor path is reliable.
     */
    private fun embeddedArtwork(uriString: String, sizePx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            var bytes: ByteArray? = null
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                runCatching {
                    retriever.setDataSource(pfd.fileDescriptor)
                    bytes = retriever.embeddedPicture
                }
            }
            if (bytes == null) {
                runCatching {
                    retriever.setDataSource(appContext, uri)
                    bytes = retriever.embeddedPicture
                }
            }
            val art = bytes ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
            val sampled = BitmapFactory.decodeByteArray(
                art, 0, art.size,
                BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(bounds, sizePx, sizePx)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
            scaleTo(sampled, sizePx)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSampled(file: File, sizePx: Int): Bitmap? {
        return try {
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val sampled = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(bounds, sizePx, sizePx)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
            scaleTo(sampled, sizePx)
        } catch (t: Throwable) {
            null
        }
    }

    private fun scaleTo(bitmap: Bitmap?, sizePx: Int): Bitmap? {
        if (bitmap == null) return null
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= sizePx) return bitmap
        val ratio = sizePx.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun write(bitmap: Bitmap, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.flush()
            }
            if (tmp.length() > 0L && (tmp.renameTo(file) || tmp.copyTo(file, overwrite = true).length() > 0L)) {
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
        }
    }

    private fun cacheKey(key: String, sizePx: Int): String =
        key.replace(Regex("[^A-Za-z0-9_.:-]"), "_") + "@" + sizePx

    companion object {
        /** Bucket sizes so we cache a handful of images per album, not dozens. */
        fun bucketSize(requested: Int): Int = when {
            requested <= 96 -> 96
            requested <= 160 -> 160
            requested <= 320 -> 320
            requested <= 512 -> 512
            else -> 768
        }
    }
}
