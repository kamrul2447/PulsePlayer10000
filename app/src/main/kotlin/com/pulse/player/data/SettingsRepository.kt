package com.pulse.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_settings")

/**
 * User preferences + the resume snapshot.
 *
 * DataStore is used instead of SharedPreferences because every write is
 * transactional and off the main thread, which matters when the playback
 * service saves the queue while the UI is being scrolled.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val RESUME_ON_OPEN = booleanPreferencesKey("resume_on_open")
        val SCAN_ON_START = booleanPreferencesKey("scan_on_start")
        val HIDE_SHORT_CLIPS = booleanPreferencesKey("hide_short_clips")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val ONLINE_SEARCH_ENABLED = booleanPreferencesKey("online_search_enabled")
        val LOCAL_SEARCH_ENABLED = booleanPreferencesKey("local_search_enabled")
        val USER_API_ENDPOINTS = stringPreferencesKey("user_api_endpoints")
        val YOUTUBE_API_KEY = stringPreferencesKey("youtube_api_key")
        val INCLUDE_MUSIC_VIDEOS_OLD = booleanPreferencesKey("include_music_videos")
        val VOLUME = longPreferencesKey("volume_bits")          // Float.toRawBits
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = intPreferencesKey("repeat")
        val QUEUE = stringSetPreferencesKey("queue_uids")
        val QUEUE_INDEX = intPreferencesKey("queue_index")
        val QUEUE_POSITION = longPreferencesKey("queue_position")
        val SLEEP_END = longPreferencesKey("sleep_end_epoch_ms")
        val SLEEP_DURATION = longPreferencesKey("sleep_duration_ms")
        val LAST_SCAN = longPreferencesKey("last_scan_epoch_ms")
        val SETTINGS_VERSION = intPreferencesKey("settings_version")
    }

    /* ─────────────────────────── settings ─────────────────────────── */

    val settings: Flow<Map<String, Any?>> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            mapOf(
                "theme" to (p[Keys.THEME] ?: "dark"),
                "reduceMotion" to (p[Keys.REDUCE_MOTION] ?: false),
                "resumeOnOpen" to (p[Keys.RESUME_ON_OPEN] ?: true),
                "scanOnStart" to (p[Keys.SCAN_ON_START] ?: true),
                "hideShortClips" to (p[Keys.HIDE_SHORT_CLIPS] ?: true),
                "keepScreenAwake" to (p[Keys.KEEP_SCREEN_AWAKE] ?: false),
                "onlineSearchEnabled" to (p[Keys.ONLINE_SEARCH_ENABLED] ?: false),
                "localSearchEnabled" to (p[Keys.LOCAL_SEARCH_ENABLED] ?: true),
                "apiEndpoints" to (p[Keys.USER_API_ENDPOINTS] ?: "[]"),
                "youtubeApiKey" to (p[Keys.YOUTUBE_API_KEY] ?: ""),
            )
        }

    suspend fun current(): Map<String, Any?> {
        val p = context.settingsDataStore.data.first()
        val version = p[Keys.SETTINGS_VERSION] ?: 0
        if (version < 6) {
            context.settingsDataStore.edit { prefs ->
                // v6 removes the old video-library setting. Pulse is now strictly
                // song-only; the old preference is intentionally discarded.
                prefs.remove(Keys.INCLUDE_MUSIC_VIDEOS_OLD)
                prefs[Keys.SETTINGS_VERSION] = 6
            }
        }
        return settings.first()
    }

    suspend fun setString(key: String, value: String) {
        val prefKey = when (key) {
            "theme" -> Keys.THEME
            "apiEndpoints" -> Keys.USER_API_ENDPOINTS
            "youtubeApiKey" -> Keys.YOUTUBE_API_KEY
            else -> stringPreferencesKey(key)
        }
        context.settingsDataStore.edit { it[prefKey] = value }
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        val prefKey = when (key) {
            "reduceMotion" -> Keys.REDUCE_MOTION
            "resumeOnOpen" -> Keys.RESUME_ON_OPEN
            "scanOnStart" -> Keys.SCAN_ON_START
            "hideShortClips" -> Keys.HIDE_SHORT_CLIPS
            "keepScreenAwake" -> Keys.KEEP_SCREEN_AWAKE
            "onlineSearchEnabled" -> Keys.ONLINE_SEARCH_ENABLED
            "localSearchEnabled" -> Keys.LOCAL_SEARCH_ENABLED
            else -> booleanPreferencesKey(key)
        }
        context.settingsDataStore.edit { it[prefKey] = value }
    }

    /* ───────────────────── playback state snapshot ─────────────────── */

    data class ResumeSnapshot(
        val uids: List<String>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeat: Int,
        val volume: Float
    )

    suspend fun savePlaybackState(
        uids: List<String>,
        index: Int,
        positionMs: Long,
        shuffle: Boolean,
        repeat: Int,
        volume: Float
    ) {
        context.settingsDataStore.edit { p ->
            p[Keys.QUEUE] = uids.toSet()
            p[Keys.QUEUE_INDEX] = index
            p[Keys.QUEUE_POSITION] = positionMs
            p[Keys.SHUFFLE] = shuffle
            p[Keys.REPEAT] = repeat
            p[Keys.VOLUME] = volume.toRawBits().toLong()
        }
    }

    suspend fun resumeSnapshot(): ResumeSnapshot? {
        val p = context.settingsDataStore.data.first()
        val uids = p[Keys.QUEUE]?.toList() ?: return null
        if (uids.isEmpty()) return null
        return ResumeSnapshot(
            uids = uids,
            index = p[Keys.QUEUE_INDEX] ?: 0,
            positionMs = p[Keys.QUEUE_POSITION] ?: 0L,
            shuffle = p[Keys.SHUFFLE] ?: false,
            repeat = p[Keys.REPEAT] ?: 0,
            volume = Float.fromBits((p[Keys.VOLUME] ?: 1f.toRawBits().toLong()).toInt())
        )
    }

    /* ───────────────────────── sleep timer ───────────────────────── */

    suspend fun saveSleepTimer(endsAtEpochMs: Long, durationMs: Long) {
        context.settingsDataStore.edit { p ->
            p[Keys.SLEEP_END] = endsAtEpochMs
            p[Keys.SLEEP_DURATION] = durationMs
        }
    }

    suspend fun clearSleepTimer() {
        context.settingsDataStore.edit { p ->
            p[Keys.SLEEP_END] = 0L
            p[Keys.SLEEP_DURATION] = 0L
        }
    }

    suspend fun sleepTimer(): Pair<Long, Long> {
        val p = context.settingsDataStore.data.first()
        return (p[Keys.SLEEP_END] ?: 0L) to (p[Keys.SLEEP_DURATION] ?: 0L)
    }

    /* ─────────────────────────── scanning ─────────────────────────── */

    suspend fun lastScanAt(): Long = context.settingsDataStore.data.first()[Keys.LAST_SCAN] ?: 0L

    suspend fun markScanned() {
        context.settingsDataStore.edit { it[Keys.LAST_SCAN] = System.currentTimeMillis() }
    }
}
