package com.pulse.player.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pulse.player.media.ScanResult
import com.pulse.player.media.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single owner of everything persisted by Pulse Player.
 *
 * Split by concern on purpose:
 *   • [LibraryRepository]  — the scanned device library
 *   • [FavoritesRepository] — favorites (survive restarts *and* rescans)
 *   • [SettingsRepository] — user preferences + resume state (DataStore)
 */
class LibraryRepository private constructor(context: Context) {

    private val db: PulseDatabase = Room.databaseBuilder(
        context.applicationContext,
        PulseDatabase::class.java,
        "pulse-player.db"
    )
        // A corrupted cache must never brick the player: fall back to a clean DB.
        .addMigrations(MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()

    private val trackDao = db.trackDao()
    private val favoriteDao = db.favoriteDao()
    private val playStatDao = db.playStatDao()
    private val archiveDao = db.archiveDao()

    /** Guards scan/reconcile so two triggers can never interleave. */
    private val scanMutex = Mutex()

    fun observeTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()

    /**
     * Replaces the cached library with [tracks] and returns what changed, so the
     * UI can report "12 new songs" instead of just reloading silently.
     */
    suspend fun reconcile(tracks: List<TrackEntity>): ScanResult = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val existing = trackDao.getAll()
            val before = existing.map { it.uid }.toSet()
            trackDao.upsertAll(tracks)
            val scannedUids = tracks.map { it.uid }.toSet()
            // Manual-review approvals are explicit user choices. Keep those rows
            // across normal MediaStore rescans even when OEM MediaStore still
            // reports IS_MUSIC=0 for the same file.
            val manualUids = existing.filter { it.mimeType == "audio/manual" }.map { it.uid }.toSet()
            val after = scannedUids + manualUids
            trackDao.deleteMissing(after.toList())
            val removed = before.minus(after).size
            val added = after.minus(before).size
            ScanResult(
                tracks = loadTracks(),
                added = added,
                removed = removed,
                skipped = 0
            )
        }
    }

    /** Tracks joined with play stats + favorites, ready for the UI. */
    suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        val stats = playStatDao.getAll().associateBy { it.uid }
        trackDao.getAll().map { entity ->
            entity.toTrack(
                playCount = stats[entity.uid]?.playCount ?: 0,
                lastPlayedAt = stats[entity.uid]?.lastPlayedAt ?: 0L
            )
        }
    }

    suspend fun tracksByUids(uids: List<String>): List<Track> = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext emptyList()
        val order = uids.withIndex().associate { it.value to it.index }
        // SQLite caps bound variables (999 on older Android); chunk large queues.
        uids.chunked(900)
            .flatMap { trackDao.getByUids(it) }
            .map { it.toTrack() }
            .sortedBy { order[it.uid] ?: Int.MAX_VALUE }
    }

    suspend fun trackByUid(uid: String): Track? = withContext(Dispatchers.IO) {
        trackDao.getByUid(uid)?.toTrack()
    }

    /**
     * Adds (or refreshes) tracks without touching the rest of the library.
     * Kept for review/restore workflows; the normal UI relies on MediaStore scanning.
     */
    suspend fun upsert(entities: List<TrackEntity>) = withContext(Dispatchers.IO) {
        if (entities.isNotEmpty()) trackDao.upsertAll(entities)
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { trackDao.count() }

    suspend fun isEmpty(): Boolean = count() == 0

    suspend fun registerPlay(uid: String) = withContext(Dispatchers.IO) {
        playStatDao.registerPlay(uid, System.currentTimeMillis())
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        // Only the artwork cache lives on disk elsewhere; the track table is the
        // library mirror and is rebuilt by a scan.
        trackDao.clear()
    }

    suspend fun removeTrack(uid: String): Boolean = withContext(Dispatchers.IO) {
        trackDao.deleteByUid(uid) > 0
    }

    suspend fun purgeKnownNonSongs(): Int = withContext(Dispatchers.IO) {
        trackDao.purgeKnownNonSongs()
    }

    suspend fun clearPreviouslyIndexedVideos(): Int = withContext(Dispatchers.IO) {
        trackDao.clearPreviouslyIndexedVideos()
    }


    /* ------------------------------- archive ------------------------------- */

    suspend fun archiveLocal(track: Track) = withContext(Dispatchers.IO) {
        archiveDao.upsert(
            ArchiveEntity(
                archiveKey = "local:${track.uid}",
                provider = "local",
                trackId = track.uid,
                localUid = track.uid,
                contentUri = track.contentUri,
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = null,
                streamUrl = null,
                durationMs = track.durationMs,
                archivedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun archiveOnline(
        provider: String,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        durationMs: Long,
        streamUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        archiveDao.upsert(
            ArchiveEntity(
                archiveKey = "$provider:$trackId",
                provider = provider,
                trackId = trackId,
                localUid = null,
                contentUri = null,
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                streamUrl = streamUrl,
                durationMs = durationMs,
                archivedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun restore(provider: String, trackId: String) = withContext(Dispatchers.IO) {
        archiveDao.remove(provider, trackId)
    }

    suspend fun isArchived(provider: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        archiveDao.getByProviderTrack(provider, trackId) != null
    }

    suspend fun archived(): List<ArchiveEntity> = withContext(Dispatchers.IO) { archiveDao.getAll() }

    suspend fun archiveKeys(): List<String> = withContext(Dispatchers.IO) { archiveDao.keys() }

    /* ───────────────────────────── favorites ───────────────────────────── */

    val favorites: FavoritesRepository = FavoritesRepository(favoriteDao)

    suspend fun favoriteUids(): List<String> = withContext(Dispatchers.IO) { favoriteDao.uids() }

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `archives` (`archiveKey` TEXT NOT NULL, `provider` TEXT NOT NULL, `trackId` TEXT NOT NULL, `localUid` TEXT, `contentUri` TEXT, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `artworkUrl` TEXT, `streamUrl` TEXT, `durationMs` INTEGER NOT NULL, `archivedAt` INTEGER NOT NULL, PRIMARY KEY(`archiveKey`))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_archives_provider_trackId` ON `archives` (`provider`, `trackId`)")
            }
        }

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(context.applicationContext).also { instance = it }
            }
    }
}

class FavoritesRepository(private val dao: FavoriteDao) {

    suspend fun set(uid: String, favorite: Boolean) {
        if (favorite) dao.add(FavoriteEntity(uid, System.currentTimeMillis()))
        else dao.remove(uid)
    }

    suspend fun toggle(uid: String): Boolean {
        val isFav = dao.contains(uid) > 0
        set(uid, !isFav)
        return !isFav
    }

    suspend fun uids(): List<String> = dao.uids()
}

/* ═════════════════════════════ mapping ═══════════════════════════════════ */

fun TrackEntity.toTrack(
    playCount: Int = 0,
    lastPlayedAt: Long = 0L
): Track = Track(
    uid = uid,
    mediaStoreId = mediaStoreId,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    artistId = artistId,
    durationMs = durationMs,
    year = year,
    trackNumber = trackNumber,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    displayName = displayName,
    path = path,
    contentUri = contentUri,
    dateAdded = dateAdded,
    dateModified = dateModified,
    artKey = artKey,
    playCount = playCount,
    lastPlayedAt = lastPlayedAt,
    mediaType = mediaType,
    isMusic = isMusic,
    folder = folder,
    hidden = hidden
)

/** Convenience: all tracks with a title matching [query] (local, offline). */
suspend fun LibraryRepository.searchLocal(query: String, limit: Int = 50): List<Track> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return loadTracks().asSequence()
        .filter { t ->
            t.title.lowercase().contains(q) ||
                t.artist.lowercase().contains(q) ||
                t.album.lowercase().contains(q)
        }
        .take(limit)
        .toList()
}
