package com.pulse.player.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/* ═══════════════════════════════ entities ═══════════════════════════════ */

/**
 * Local cache of everything the media scanner found.
 *
 * The library is stored (rather than re-queried on every launch) so the UI can
 * paint instantly and so play counts / "recently added" survive rescans. The
 * scanner reconciles this table with MediaStore on each scan.
 */
@Entity(
    tableName = "tracks",
    indices = [Index("albumId"), Index("artistId"), Index("dateAdded"), Index("isMusic"), Index("mediaType")]
)
data class TrackEntity(
    @androidx.room.PrimaryKey val uid: String,
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val durationMs: Long,
    val year: Int,
    val trackNumber: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val displayName: String,
    val path: String,
    val contentUri: String,
    val dateAdded: Long,
    val dateModified: Long,
    val artKey: String?,
    val lastScannedAt: Long,
    val mediaType: String = "audio",
    val isMusic: Boolean = true,
    val folder: String = "",
    val hidden: Boolean = false
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @androidx.room.PrimaryKey val uid: String,
    val addedAt: Long
)


@Entity(
    tableName = "archives",
    indices = [Index(value = ["provider", "trackId"], unique = true)]
)
data class ArchiveEntity(
    @androidx.room.PrimaryKey val archiveKey: String,
    val provider: String,
    val trackId: String,
    val localUid: String?,
    val contentUri: String?,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val streamUrl: String?,
    val durationMs: Long,
    val archivedAt: Long
)

@Entity(tableName = "play_stats")
data class PlayStatEntity(
    @androidx.room.PrimaryKey val uid: String,
    val playCount: Int,
    val lastPlayedAt: Long
)

/* ═════════════════════════════════ DAOs ═════════════════════════════════ */

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE uid IN (:uids)")
    suspend fun getByUids(uids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): TrackEntity?

    @Query("UPDATE tracks SET hidden = :hidden WHERE uid = :uid")
    suspend fun setHidden(uid: String, hidden: Boolean)

    @Query("UPDATE tracks SET isMusic = :isMusic, mediaType = :mediaType WHERE uid = :uid")
    suspend fun setClassification(uid: String, isMusic: Boolean, mediaType: String)

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE uid NOT IN (:keepUids)")
    suspend fun deleteMissing(keepUids: List<String>): Int

    @Query("DELETE FROM tracks")
    suspend fun clear()

    @Query("DELETE FROM tracks WHERE uid = :uid")
    suspend fun deleteByUid(uid: String): Int

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /** Uids that already exist — used to compute the "removed" count on scan. */
    @Query("SELECT uid FROM tracks")
    suspend fun allUids(): List<String>

    /** Removes stale rows that older builds accidentally surfaced as songs.
     *  This never touches the user's actual files. The next MediaStore scan can
     *  re-add a legitimate track if it passes the new classifier.
     */
    @Query("""
        DELETE FROM tracks
        WHERE isMusic = 0
           OR mediaType = 'video'
           OR lower(path) LIKE '%/dcim/%'
           OR lower(path) LIKE '%/camera/%'
           OR lower(path) LIKE '%/screenrecord/%'
           OR lower(path) LIKE '%/screen recorder/%'
           OR lower(path) LIKE '%/whatsapp/video/%'
           OR lower(path) LIKE '%/movies/%'
           OR lower(path) LIKE '%/recordings/%'
           OR lower(path) LIKE '%/android/data/%'
           OR lower(path) LIKE '%/android/obb/%'
           OR lower(path) LIKE '%/streamingassets/%'
           OR lower(path) LIKE '%/assetbundles/%'
           OR lower(displayName) LIKE 'bgm%'
           OR lower(displayName) LIKE 'sfx%'
           OR lower(displayName) LIKE '%sound_effect%'
           OR lower(displayName) LIKE '%voiceover%'
           OR lower(displayName) LIKE '%dialogue%'
    """)
    suspend fun purgeKnownNonSongs(): Int

    @Query("DELETE FROM tracks WHERE mimeType LIKE 'video/%'")
    suspend fun clearPreviouslyIndexedVideos(): Int
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT uid FROM favorites")
    suspend fun uids(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE uid = :uid")
    suspend fun remove(uid: String)

    @Query("SELECT COUNT(*) FROM favorites WHERE uid = :uid")
    suspend fun contains(uid: String): Int
}


@Dao
interface ArchiveDao {
    @Query("SELECT * FROM archives ORDER BY archivedAt DESC")
    fun observeAll(): Flow<List<ArchiveEntity>>

    @Query("SELECT * FROM archives ORDER BY archivedAt DESC")
    suspend fun getAll(): List<ArchiveEntity>

    @Query("SELECT * FROM archives WHERE archiveKey = :archiveKey LIMIT 1")
    suspend fun getByKey(archiveKey: String): ArchiveEntity?

    @Query("SELECT * FROM archives WHERE provider = :provider AND trackId = :trackId LIMIT 1")
    suspend fun getByProviderTrack(provider: String, trackId: String): ArchiveEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArchiveEntity)

    @Query("DELETE FROM archives WHERE provider = :provider AND trackId = :trackId")
    suspend fun remove(provider: String, trackId: String)

    @Query("SELECT archiveKey FROM archives")
    suspend fun keys(): List<String>
}

@Dao
interface PlayStatDao {
    @Query("SELECT * FROM play_stats")
    suspend fun getAll(): List<PlayStatEntity>

    @Query("SELECT * FROM play_stats WHERE uid = :uid LIMIT 1")
    suspend fun get(uid: String): PlayStatEntity?

    @Upsert
    suspend fun upsert(entity: PlayStatEntity)

    @Transaction
    suspend fun registerPlay(uid: String, playedAt: Long) {
        val existing = get(uid)
        upsert(
            PlayStatEntity(
                uid = uid,
                playCount = (existing?.playCount ?: 0) + 1,
                lastPlayedAt = playedAt
            )
        )
    }
}

/* ═══════════════════════════════ database ═══════════════════════════════ */

@Database(
    entities = [TrackEntity::class, FavoriteEntity::class, PlayStatEntity::class, ArchiveEntity::class],
    version = 3,
    exportSchema = true
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playStatDao(): PlayStatDao
    abstract fun archiveDao(): ArchiveDao
}
