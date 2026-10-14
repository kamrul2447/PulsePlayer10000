package com.pulse.player.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.pulse.player.BuildConfig
import com.pulse.player.ads.AdConfig
import com.pulse.player.ads.AdRepository
import com.pulse.player.data.LibraryRepository
import com.pulse.player.data.SettingsRepository
import com.pulse.player.media.ArtworkRepository
import com.pulse.player.media.MediaStoreScanner
import com.pulse.player.media.ReviewCandidate
import com.pulse.player.media.ReviewRepository
import com.pulse.player.player.MediaItemFactory
import com.pulse.player.search.MusicSearchRepository
import com.pulse.player.search.SearchOutcome
import com.pulse.player.util.EventBus
import com.pulse.player.util.isNetworkAvailable
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Things only an Activity can do (permissions, intents, window flags). */
interface ActivityBridge {
    fun requestMusicPermission()
    fun openAppSettings()
    fun requestDelete(trackUid: String, contentUri: String)
    fun openExternalUrl(url: String)
    fun openAppSpace()
    fun addAppSpace()
    fun keepScreenAwake(enabled: Boolean)
    fun applyTheme(resolvedTheme: String)
}

/**
 * Executes every command the web UI sends.
 *
 * The UI is deliberately dumb: it sends *intent*, never state. For example
 * "play these 30 uids starting at #7" is one command; the service decides the
 * queue order, shuffle behaviour and what to do at the end. That is what keeps
 * the notification, lock screen, mini player and library in sync.
 */
class CommandDispatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controllerProvider: () -> MediaController?,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val artwork: ArtworkRepository,
    private val scanner: MediaStoreScanner,
    private val review: ReviewRepository,
    private val search: MusicSearchRepository,
    private val activity: ActivityBridge
) {

    private var scanJob: Job? = null
    private var searchJob: Job? = null

    /* Commands originate in a WebView, so an exception inside a lifecycle
       coroutine must become an error event, not an uncaught exception on the
       main thread that kills the app. */
    private val commandExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("CommandDispatcher", "Background command failed", throwable)
        EventBus.emit(
            BridgeProtocol.EVENT_ERROR,
            "code" to "command",
            "message" to (throwable.message ?: "Background operation failed")
        )
    }

    private fun logStep(step: String, t: Throwable) {
        android.util.Log.e("CommandDispatcher", "Startup step failed: $step", t)
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(context = commandExceptionHandler, block = block)

    /* ═══════════════════════════ entry point ═══════════════════════════ */

    fun handle(cmd: String, payload: JSONObject, requestId: String?) {
        try {
            when (cmd) {
                BridgeProtocol.CMD_READY -> onReady(requestId)
                BridgeProtocol.CMD_REQUEST_PERMISSION -> activity.requestMusicPermission()
                BridgeProtocol.CMD_OPEN_APP_SETTINGS -> activity.openAppSettings()
                BridgeProtocol.CMD_SCAN_LIBRARY -> scanLibrary(payload.optBoolean("full", true))
                BridgeProtocol.CMD_ARCHIVE_TRACK -> archiveTrack(payload)
                BridgeProtocol.CMD_RESTORE_TRACK -> restoreTrack(payload)
                BridgeProtocol.CMD_DELETE_TRACK -> deleteTrack(payload)
                BridgeProtocol.CMD_APPROVE_REVIEW -> approveReview(payload.optString("uid"))
                BridgeProtocol.CMD_REJECT_REVIEW -> rejectReview(payload.optString("uid"))

                BridgeProtocol.CMD_PLAY_TRACKS -> playTracks(payload)
                BridgeProtocol.CMD_PLAY_REMOTE -> playRemote(payload)
                BridgeProtocol.CMD_PLAY_ARCHIVED -> playArchived(payload)
                BridgeProtocol.CMD_PLAY -> withPlayer { it.play() }
                BridgeProtocol.CMD_PAUSE -> withPlayer { it.pause() }
                BridgeProtocol.CMD_TOGGLE_PLAY -> togglePlay()
                BridgeProtocol.CMD_NEXT -> withPlayer { it.seekToNextMediaItem() }
                BridgeProtocol.CMD_PREV -> withPlayer { it.seekToPreviousMediaItem() }
                BridgeProtocol.CMD_SEEK -> withPlayer { it.seekTo(payload.optLong("positionMs", 0L)) }
                BridgeProtocol.CMD_SEEK_BY -> withPlayer {
                    it.seekTo((it.currentPosition + payload.optLong("deltaMs", 0L)).coerceAtLeast(0L))
                }
                BridgeProtocol.CMD_SET_VOLUME -> withPlayer {
                    it.volume = payload.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f)
                }
                BridgeProtocol.CMD_SET_SHUFFLE -> withPlayer {
                    it.shuffleModeEnabled = payload.optBoolean("enabled", false)
                }
                BridgeProtocol.CMD_SET_REPEAT -> withPlayer {
                    it.repeatMode = MediaItemFactory.repeatToMedia3(payload.optInt("mode", 0))
                }
                BridgeProtocol.CMD_CYCLE_REPEAT -> withPlayer {
                    val next = (MediaItemFactory.repeatFromMedia3(it.repeatMode) + 1) % 3
                    it.repeatMode = MediaItemFactory.repeatToMedia3(next)
                }
                BridgeProtocol.CMD_ENQUEUE_NEXT -> enqueueNext(payload)
                BridgeProtocol.CMD_CLEAR_QUEUE -> clearQueue()
                BridgeProtocol.CMD_RESTORE_QUEUE -> restoreQueue()

                BridgeProtocol.CMD_SET_FAVORITE -> setFavorite(payload)

                BridgeProtocol.CMD_START_SLEEP_TIMER -> sendSleepTimer(payload.optLong("durationMs", 0L))
                BridgeProtocol.CMD_CANCEL_SLEEP_TIMER -> sendSleepTimer(0L)

                BridgeProtocol.CMD_SET_SETTING -> setSetting(payload)
                BridgeProtocol.CMD_THEME_CHANGED -> activity.applyTheme(payload.optString("resolved", "dark"))

                BridgeProtocol.CMD_SEARCH_ONLINE -> searchOnline(payload.optString("query"))
                BridgeProtocol.CMD_CANCEL_SEARCH -> searchJob?.cancel()
                BridgeProtocol.CMD_LOAD_YOUTUBE_PLAYLIST -> loadYouTubePlaylist(payload.optString("url"))
                BridgeProtocol.CMD_PLAY_REMOTE_QUEUE -> playRemoteQueue(payload)
                BridgeProtocol.CMD_PLAY_QUEUE_INDEX -> playQueueIndex(payload.optInt("index", -1))

                BridgeProtocol.CMD_CLEAR_ART_CACHE -> launchSafe { artwork.clear() }
                BridgeProtocol.CMD_KEEP_AWAKE -> activity.keepScreenAwake(payload.optBoolean("enabled", false))
                BridgeProtocol.CMD_OPEN_AD -> openAd(payload)
                BridgeProtocol.CMD_OPEN_APP_SPACE -> activity.openAppSpace()
                BridgeProtocol.CMD_ADD_APP_SPACE -> activity.addAppSpace()
                BridgeProtocol.CMD_LOG -> Unit   // JS console → logcat, intentionally quiet
                else -> Unit
            }
        } catch (t: Throwable) {
            EventBus.emit(
                BridgeProtocol.EVENT_ERROR,
                "code" to "command",
                "message" to (t.message ?: "Command failed")
            )
        }
    }

    /* ════════════════════════════ startup ═════════════════════════════ */

    private fun onReady(requestId: String?) {
        launchSafe {
            val settingsMap = settings.current()
            applySearchSettings(settingsMap)
            EventBus.emit(
                BridgeProtocol.EVENT_READY,
                "appVersion" to BuildConfig.PULSE_VERSION,
                "appId" to BuildConfig.APPLICATION_ID,
                "protocol" to BridgeProtocol.VERSION,
                "settings" to settingsJson(settingsMap),
                "online" to context.isNetworkAvailable(),
                requestId = requestId
            )
            EventBus.emit(
                BridgeProtocol.EVENT_ONLINE_STATUS,
                "available" to context.isNetworkAvailable()
            )
            // Library FIRST. Nothing below may be allowed to stop the song list from
            // reaching the UI: every step is isolated so one failure cannot skip the rest.
            runCatching { publishLibrary() }.onFailure { logStep("publishLibrary(initial)", it) }
            runCatching { AdRepository.publish() }.onFailure { logStep("ads", it) }
            runCatching { FavoritesPublisher.publish(library) }.onFailure { logStep("favorites", it) }
            runCatching { publishArchive() }.onFailure { logStep("archive", it) }
            runCatching { publishReviewQueue() }.onFailure { logStep("review", it) }

            // Remove stale non-song rows created by older scanner versions before
            // the UI paints cached data. This never deletes the real files.
            runCatching { library.purgeKnownNonSongs() }.onFailure { logStep("purge", it) }

            // Publish again after the purge so the UI never keeps stale rows.
            runCatching { publishLibrary() }.onFailure { logStep("publishLibrary(after purge)", it) }
            // …then refresh from MediaStore in the background.
            if (settingsMap["scanOnStart"] as? Boolean != false) {
                delay(120)
                scanLibrary(full = false, silent = true)
            }
        }
    }

    suspend fun publishLibrary() {
        // The scanner's classification is authoritative. Do not narrow the
        // bridge back to mediaType==audio here, because a genuine music-video
        // MP4 can be stored as video/* by MediaStore while still being a
        // playable audio-first song in Pulse. Hidden rows stay out of the UI.
        val tracks = library.loadTracks().filter { track ->
            !track.hidden &&
                (track.isMusic || track.mimeType.startsWith("audio/", true) ||
                    (track.mimeType.startsWith("video/", true) && track.mediaType == "audio"))
        }
        val array = JSONArray()
        tracks.forEach { array.put(it.toJson()) }
        EventBus.emit(BridgeProtocol.EVENT_LIBRARY, "tracks" to array)
    }

    private fun publishReviewQueue() {
        val array = JSONArray()
        review.getAll().forEach { array.put(it.toJson()) }
        EventBus.emit(BridgeProtocol.EVENT_REVIEW_QUEUE, "items" to array)
    }

    private suspend fun publishArchive() {
        val items = JSONArray()
        library.archived().forEach { item ->
            items.put(JSONObject().apply {
                put("archiveKey", item.archiveKey)
                put("provider", item.provider)
                put("trackId", item.trackId)
                put("localUid", item.localUid ?: JSONObject.NULL)
                put("contentUri", item.contentUri ?: JSONObject.NULL)
                put("title", item.title)
                put("artist", item.artist)
                put("album", item.album)
                put("artworkUrl", item.artworkUrl ?: JSONObject.NULL)
                if (item.provider == "local") {
                    put("streamUrl", item.contentUri ?: JSONObject.NULL)
                } else {
                    put(
                        "streamUrl",
                        item.streamUrl?.takeIf { it.isNotBlank() }
                            ?: search.streamUrl(item.provider, item.trackId)
                            ?: JSONObject.NULL
                    )
                }
                put("durationMs", item.durationMs)
                put("archivedAt", item.archivedAt)
            })
        }
        EventBus.emit(BridgeProtocol.EVENT_ARCHIVE, "items" to items)
    }

    /** Full snapshot for a UI that just (re)connected. */
    suspend fun publishSnapshot() {
        // The song list does not depend on the player connection. Send it even when the
        // MediaController has not connected yet; otherwise the Library stays empty until
        // something else (like starting playback) triggers another snapshot.
        runCatching { publishLibrary() }.onFailure { logStep("publishLibrary(snapshot)", it) }
        try {
            val controller = controllerProvider() ?: return
            val uids = ArrayList<String>(controller.mediaItemCount)
            val queueRows = JSONArray()
            for (i in 0 until controller.mediaItemCount) {
                val item = controller.getMediaItemAt(i)
                uids.add(item.mediaId)
                val metadata = item.mediaMetadata
                val extras = metadata.extras
                queueRows.put(JSONObject().apply {
                    put("uid", item.mediaId)
                    put("title", metadata.title?.toString().orEmpty())
                    put("artist", metadata.artist?.toString().orEmpty())
                    put("album", metadata.albumTitle?.toString().orEmpty())
                    put("artworkUrl", extras?.getString("pulse.artworkUrl") ?: JSONObject.NULL)
                    put("source", extras?.getString("pulse.source") ?: "local")
                    put("provider", extras?.getString("pulse.provider") ?: JSONObject.NULL)
                    put("trackId", extras?.getString("pulse.trackId") ?: JSONObject.NULL)
                    put("durationMs", if (i == controller.currentMediaItemIndex &&
                        controller.duration != androidx.media3.common.C.TIME_UNSET) controller.duration else 0L)
                })
            }
            EventBus.emit(
                BridgeProtocol.EVENT_QUEUE,
                "uids" to JSONArray(uids),
                "items" to queueRows,
                "currentUid" to controller.currentMediaItem?.mediaId
            )
            val current = controller.currentMediaItem
            val meta = current?.mediaMetadata
            val extras = meta?.extras
            val source = extras?.getString("pulse.source") ?: "local"
            EventBus.emit(
                BridgeProtocol.EVENT_STATE,
                "uid" to current?.mediaId,
                "source" to source,
                "provider" to extras?.getString("pulse.provider"),
                "trackId" to extras?.getString("pulse.trackId"),
                "title" to meta?.title?.toString(),
                "artist" to meta?.artist?.toString(),
                "album" to meta?.albumTitle?.toString(),
                "artworkUrl" to extras?.getString("pulse.artworkUrl"),
                "playing" to controller.isPlaying,
                "position" to controller.currentPosition.coerceAtLeast(0L),
                "duration" to controller.duration.let { if (it == androidx.media3.common.C.TIME_UNSET) 0L else it },
                "buffered" to controller.bufferedPosition.coerceAtLeast(0L),
                "buffering" to (controller.playbackState == Player.STATE_BUFFERING),
                "volume" to controller.volume.coerceIn(0f, 1f),
                "shuffle" to controller.shuffleModeEnabled,
                "repeat" to MediaItemFactory.repeatFromMedia3(controller.repeatMode),
                "error" to JSONObject.NULL
            )
            FavoritesPublisher.publish(library)
            publishArchive()
            publishReviewQueue()
        } catch (t: Throwable) {
            android.util.Log.e("CommandDispatcher", "Snapshot failed", t)
            EventBus.emit(
                BridgeProtocol.EVENT_ERROR,
                "code" to "snapshot",
                "message" to (t.message ?: "Could not refresh player state")
            )
        }
    }

    /* ════════════════════════════ library ═════════════════════════════ */

    fun scanLibrary(full: Boolean, silent: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = launchSafe {
            if (!silent) {
                EventBus.emit(BridgeProtocol.EVENT_SCAN, "state" to "started", "found" to 0, "scanned" to 0, "total" to 0)
            }
            val currentSettings = settings.current()
            val hideShort = currentSettings["hideShortClips"] as? Boolean ?: true
            val entities = withContext(Dispatchers.IO) {
                scanner.scan(
                    onProgress = { read ->
                        EventBus.emit(
                            BridgeProtocol.EVENT_SCAN,
                            "state" to "progress",
                            "found" to read,
                            "scanned" to read,
                            "total" to 0
                        )
                    },
                    minDurationMs = if (hideShort) MediaStoreScanner.MIN_DURATION_MS else 0L
                )
            }
            // A successful but transiently empty MediaStore result must never erase
            // an existing cache on a normal startup/background refresh. An explicit
            // full rescan can still reconcile an actually empty library.
            if (!full && entities.isEmpty() && !library.isEmpty()) {
                throw IllegalStateException("MediaStore returned no playable music; the existing library was preserved.")
            }

            // Only remove stale video rows when video permission is available, otherwise
            // a user who denied READ_MEDIA_VIDEO could lose previously indexed MP4 songs.
            if (scanner.canScanMusicVideos()) library.clearPreviouslyIndexedVideos()
            val result = library.reconcile(entities)
            settings.markScanned()

            EventBus.emit(
                BridgeProtocol.EVENT_SCAN,
                "state" to "finished",
                "found" to result.tracks.size,
                "scanned" to result.tracks.size,
                "total" to result.tracks.size
            )
            publishLibrary()
            FavoritesPublisher.publish(library)
            publishReviewQueue()
            if (!silent && result.added > 0) {
                EventBus.emit(
                    BridgeProtocol.EVENT_TOAST,
                    "message" to if (result.added == 1) "1 new song found" else "${result.added} new songs found"
                )
            }
        }
    }

    /* ════════════════════════════ playback ════════════════════════════ */

    private fun playTracks(payload: JSONObject) {
        val uids = payload.optJSONArray("uids")?.toStringList() ?: return
        val index = payload.optInt("index", 0).coerceIn(0, (uids.size - 1).coerceAtLeast(0))
        launchSafe {
            val tracks = withContext(Dispatchers.IO) { library.tracksByUids(uids) }
            if (tracks.isEmpty()) return@launchSafe
            val controller = controllerProvider() ?: return@launchSafe
            val items = tracks.map { MediaItemFactory.fromTrack(it) }
            // Some uids may no longer exist; find the tapped song by id, not by position.
            val wantedUid = uids.getOrNull(index)
            val startIndex = tracks.indexOfFirst { it.uid == wantedUid }.let { if (it < 0) 0 else it }
            controller.setMediaItems(items, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    private fun togglePlay() {
        val controller = controllerProvider() ?: return
        if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount == 0) {
            launchSafe {
                val tracks = library.loadTracks()
                if (tracks.isEmpty()) return@launchSafe
                val items = tracks.map { MediaItemFactory.fromTrack(it) }
                controller.setMediaItems(items, 0, 0L)
                controller.prepare()
                controller.play()
            }
            return
        }
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    private fun enqueueNext(payload: JSONObject) {
        val uids = payload.optJSONArray("uids")?.toStringList() ?: return
        launchSafe {
            val tracks = withContext(Dispatchers.IO) { library.tracksByUids(uids) }
            val controller = controllerProvider() ?: return@launchSafe
            tracks.forEachIndexed { i, track ->
                controller.addMediaItem(controller.currentMediaItemIndex + 1 + i, MediaItemFactory.fromTrack(track))
            }
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Added to queue")
        }
    }

    private fun clearQueue() {
        withPlayer { controller ->
            val index = controller.currentMediaItemIndex
            val keep = if (index >= 0 && index < controller.mediaItemCount) {
                controller.getMediaItemAt(index)
            } else null
            controller.clearMediaItems()
            if (keep != null) {
                controller.addMediaItem(keep)
                controller.seekTo(0, controller.currentPosition)
            }
        }
    }

    private fun restoreQueue() {
        launchSafe {
            val snapshot = settings.resumeSnapshot() ?: return@launchSafe
            val tracks = withContext(Dispatchers.IO) { library.tracksByUids(snapshot.uids) }
            if (tracks.isEmpty()) return@launchSafe
            val controller = controllerProvider() ?: return@launchSafe
            // Never replace a queue the user already has.
            if (controller.mediaItemCount > 0) return@launchSafe
            val items = tracks.map { MediaItemFactory.fromTrack(it) }
            controller.setMediaItems(items, snapshot.index.coerceIn(0, items.lastIndex), snapshot.positionMs)
            controller.shuffleModeEnabled = snapshot.shuffle
            controller.repeatMode = MediaItemFactory.repeatToMedia3(snapshot.repeat)
            controller.volume = snapshot.volume.coerceIn(0f, 1f)
            controller.prepare()
            // Paused on purpose: reopening the app must not blast audio.
            controller.playWhenReady = false
        }
    }

    /** Online catalogue result. YouTube = placeholder URI resolved lazily by Media3; others resolved here. */
    private fun playRemote(payload: JSONObject) {
        val json = payload.optJSONObject("item") ?: return
        val item = buildRemoteYouTubeItem(json) ?: return
        launchSafe {
            val controller = controllerProvider() ?: return@launchSafe
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        }
    }

    /** Load all playlist rows first; pressing Play playlist then queues them in order. */
    private fun loadYouTubePlaylist(url: String) {
        searchJob?.cancel()
        if (url.isBlank()) return
        searchJob = launchSafe {
            try {
                val playlist = withContext(Dispatchers.IO) { search.loadYouTubePlaylist(url, 1000) }
                val rows = JSONArray()
                playlist.tracks.forEach { rows.put(it.toJson()) }
                EventBus.emit(
                    BridgeProtocol.EVENT_PLAYLIST_RESULTS,
                    "items" to rows,
                    "title" to playlist.title,
                    "url" to url,
                    "count" to playlist.tracks.size
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                EventBus.emit(
                    BridgeProtocol.EVENT_SEARCH_ERROR,
                    "message" to (t.message ?: "Could not load this YouTube playlist")
                )
            }
        }
    }

    private fun playQueueIndex(index: Int) {
        withPlayer { controller ->
            if (index in 0 until controller.mediaItemCount) {
                controller.seekTo(index, 0L)
                controller.play()
            }
        }
    }

    private fun playRemoteQueue(payload: JSONObject) {
        val rows = payload.optJSONArray("items") ?: return
        val items = ArrayList<MediaItem>(rows.length())
        for (index in 0 until rows.length()) {
            val json = rows.optJSONObject(index) ?: continue
            buildRemoteYouTubeItem(json)?.let(items::add)
        }
        if (items.isEmpty()) {
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "This playlist contains no playable videos")
            return
        }
        launchSafe {
            val controller = controllerProvider() ?: return@launchSafe
            controller.setMediaItems(items, 0, 0L)
            controller.prepare()
            controller.play()
        }
    }

    private fun buildRemoteYouTubeItem(json: JSONObject): MediaItem? {
        val provider = json.optString("provider", "youtube").ifBlank { "youtube" }
        if (!provider.equals("youtube", ignoreCase = true)) {
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Online playback currently uses YouTube")
            return null
        }
        val id = normalizeYouTubeId(json.optString("id").ifBlank { json.optString("streamUrl") })
        if (id.isBlank()) {
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "This link does not contain a valid YouTube video")
            return null
        }
        val art = json.optStringOrNull("artworkUrl")
        return MediaItem.Builder()
            .setMediaId("remote:youtube:$id")
            .setUri(Uri.parse("pulse-yt://$id"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(json.optString("title", "Unknown title"))
                    .setArtist(json.optString("artist", "YouTube"))
                    .setAlbumTitle(json.optString("album", "YouTube"))
                    .setArtworkUri(art?.takeIf { it.startsWith("https://") }?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setExtras(Bundle().apply {
                        putString("pulse.source", "online")
                        putString("pulse.provider", "youtube")
                        putString("pulse.trackId", id)
                        putString("pulse.artworkUrl", art)
                        putLong("pulse.durationMs", json.optLong("durationMs", 0L).coerceAtLeast(0L))
                        putBoolean("pulse.autoplayRelated", true)
                    })
                    .build()
            )
            .build()
    }

    private fun normalizeYouTubeId(raw: String): String {
        val value = raw.trim().removePrefix("pulse-yt://")
        val candidates = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})").find(value)?.groupValues?.getOrNull(1),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(value)?.groupValues?.getOrNull(1),
            Regex("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})").find(value)?.groupValues?.getOrNull(1),
            value.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun playArchived(payload: JSONObject) {
        val provider = payload.optString("provider", "local").ifBlank { "local" }
        val trackId = payload.optString("trackId").trim()
        if (trackId.isBlank()) return
        launchSafe {
            if (provider == "local") {
                val track = library.trackByUid(trackId) ?: return@launchSafe
                val controller = controllerProvider() ?: return@launchSafe
                controller.setMediaItem(MediaItemFactory.fromTrack(track))
                controller.prepare()
                controller.play()
                return@launchSafe
            }
            val archive = library.archived().firstOrNull { it.provider == provider && it.trackId == trackId }
                ?: return@launchSafe
            val stream = archive.streamUrl?.takeIf { it.isNotBlank() }
                ?: search.streamUrl(provider, trackId).orEmpty()
            if (stream.isBlank()) {
                EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Online track is no longer streamable")
                return@launchSafe
            }
            val controller = controllerProvider() ?: return@launchSafe
            val item = MediaItem.Builder()
                .setMediaId("remote:$provider:$trackId")
                .setUri(Uri.parse(stream))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(archive.title)
                        .setArtist(archive.artist)
                        .setAlbumTitle(archive.album)
                        .setArtworkUri(archive.artworkUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                        .setIsPlayable(true)
                        .setExtras(Bundle().apply {
                            putString("pulse.source", "online")
                            putString("pulse.provider", provider)
                            putString("pulse.trackId", trackId)
                            putString("pulse.artworkUrl", archive.artworkUrl)
                        })
                        .build()
                )
                .build()
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        }
    }

    private fun deleteTrack(payload: JSONObject) {
        val uid = payload.optStringOrNull("uid")?.trim().orEmpty()
        if (uid.isBlank()) return
        launchSafe {
            val track = withContext(Dispatchers.IO) { library.trackByUid(uid) } ?: return@launchSafe
            val uri = track.contentUri.trim()
            if (uri.isBlank()) {
                EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "This track has no local file reference")
                return@launchSafe
            }

            // Stop the current item before removing its MediaStore entry.
            withPlayer { controller ->
                if (controller.currentMediaItem?.mediaId == uid) {
                    controller.stop()
                    controller.clearMediaItems()
                }
            }

            val deleted = runCatching {
                context.contentResolver.delete(Uri.parse(uri), null, null) > 0
            }.getOrElse { false }

            if (deleted) {
                library.removeTrack(uid)
                publishLibrary()
                FavoritesPublisher.publish(library)
                EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Deleted from this device")
                return@launchSafe
            }

            // Android 11+ requires user confirmation for many files that this app
            // did not create. The Activity launches MediaStore's official delete UI.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                activity.requestDelete(uid, uri)
            } else {
                EventBus.emit(
                    BridgeProtocol.EVENT_ERROR,
                    "code" to "delete",
                    "message" to "Android did not allow Pulse Player to delete this file."
                )
            }
        }
    }

    fun finalizeDeletedTrack(uid: String) {
        if (uid.isBlank()) return
        launchSafe {
            val removed = library.removeTrack(uid)
            if (removed) {
                publishLibrary()
                FavoritesPublisher.publish(library)
                EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Deleted from this device")
            } else {
                scanLibrary(full = true)
            }
        }
    }

    private fun archiveTrack(payload: JSONObject) {
        val source = payload.optString("source", "local")
        launchSafe {
            if (source == "online") {
                val provider = payload.optString("provider", "audius").ifBlank { "audius" }
                val id = payload.optString("trackId").trim()
                if (id.isBlank()) return@launchSafe
                library.archiveOnline(
                    provider = provider,
                    trackId = id,
                    title = payload.optString("title", "Unknown title"),
                    artist = payload.optString("artist", "Unknown artist"),
                    album = payload.optString("album", ""),
                    artworkUrl = payload.optStringOrNull("artworkUrl"),
                    durationMs = payload.optLong("durationMs", 0L),
                    streamUrl = payload.optStringOrNull("streamUrl")
                )
            } else {
                val uid = payload.optString("uid").trim()
                val track = library.trackByUid(uid) ?: return@launchSafe
                library.archiveLocal(track)
            }
            publishArchive()
            publishLibrary()
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Archived")
        }
    }

    private fun restoreTrack(payload: JSONObject) {
        val provider = payload.optString("provider", "local")
        val trackId = payload.optString("trackId").trim()
        if (trackId.isBlank()) return
        launchSafe {
            library.restore(provider, trackId)
            publishArchive()
            publishLibrary()
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Restored")
        }
    }

    private fun approveReview(uid: String) {
        if (uid.isBlank()) return
        launchSafe {
            val candidate = review.getAll().firstOrNull { it.uid == uid } ?: return@launchSafe
            val now = System.currentTimeMillis()
            val entity = com.pulse.player.data.TrackEntity(
                uid = candidate.uid,
                mediaStoreId = candidate.mediaStoreId,
                title = candidate.title,
                artist = candidate.artist,
                album = candidate.album,
                albumId = "manual:${candidate.album.lowercase()}",
                artistId = "manual:${candidate.artist.lowercase()}",
                durationMs = candidate.durationMs,
                year = 0,
                trackNumber = 0,
                sizeBytes = candidate.sizeBytes,
                mimeType = "audio/manual",
                displayName = candidate.displayName,
                path = candidate.path,
                contentUri = candidate.contentUri,
                dateAdded = now / 1000L,
                dateModified = now / 1000L,
                artKey = candidate.artKey,
                lastScannedAt = now,
                mediaType = "audio",
                isMusic = true,
                folder = candidate.folder,
                hidden = false
            )
            library.upsert(listOf(entity))
            review.remove(uid)
            publishLibrary()
            publishReviewQueue()
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Added to Songs")
        }
    }

    private fun rejectReview(uid: String) {
        if (uid.isBlank()) return
        review.remove(uid)
        publishReviewQueue()
        EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Removed from review")
    }

    /* ═══════════════════════════ favorites ════════════════════════════ */

    private fun setFavorite(payload: JSONObject) {
        val uid = payload.optStringOrNull("uid") ?: return
        val favorite = payload.optBoolean("favorite", true)
        launchSafe {
            library.favorites.set(uid, favorite)
            FavoritesPublisher.publish(library)
        }
        // Keep the media session (and any Wear/Auto controller) informed.
        sendCustomCommand(
            BridgeProtocol.SESSION_TOGGLE_FAVORITE,
            Bundle().apply {
                putString("uid", uid)
                putBoolean("favorite", favorite)
            }
        )
    }

    /* ══════════════════════════ sleep timer ═══════════════════════════ */

    private fun sendSleepTimer(durationMs: Long) {
        sendCustomCommand(
            if (durationMs > 0) BridgeProtocol.SESSION_SET_SLEEP_TIMER else BridgeProtocol.SESSION_CANCEL_SLEEP_TIMER,
            Bundle().apply { putLong("durationMs", durationMs) }
        )
    }

    /* ════════════════════════════ settings ════════════════════════════ */

    private fun setSetting(payload: JSONObject) {
        val key = payload.optStringOrNull("key") ?: return
        val value = payload.opt("value")
        launchSafe {
            when (value) {
                is Boolean -> settings.setBoolean(key, value)
                is String -> settings.setString(key, value)
                is Number -> settings.setString(key, value.toString())
                else -> Unit
            }
            if (key == "onlineSearchEnabled") search.setEnabled(value == true)
            EventBus.emit(BridgeProtocol.EVENT_SETTINGS, settingsJson(settings.current()))
        }
    }

    private fun applySearchSettings(map: Map<String, Any?>) {
        search.setEnabled(map["onlineSearchEnabled"] as? Boolean != false)
    }

    /** Legacy API keys/endpoints are omitted from the UI and no longer used. */
    private fun settingsJson(map: Map<String, Any?>): JSONObject = JSONObject().apply {
        val retired = setOf(
            "apiEndpoints", "youtubeApiKey", "audiusApiKey",
            "apiAudiusEnabled", "apiYoutubeEnabled"
        )
        map.forEach { (key, value) ->
            if (key !in retired) put(key, value ?: JSONObject.NULL)
        }
    }

    /* ═════════════════════════════ search ═════════════════════════════ */

    private fun searchOnline(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = launchSafe {
            when (val outcome = search.search(query)) {
                SearchOutcome.Disabled,
                SearchOutcome.Offline,
                SearchOutcome.Empty -> EventBus.emit(
                    BridgeProtocol.EVENT_SEARCH_RESULTS,
                    "items" to JSONArray(),
                    "provider" to search.providerName()
                )
                is SearchOutcome.Failed -> EventBus.emit(
                    BridgeProtocol.EVENT_SEARCH_ERROR,
                    "message" to outcome.message
                )
                is SearchOutcome.Results -> {
                    val array = JSONArray()
                    outcome.items.forEach { array.put(it.toJson()) }
                    EventBus.emit(
                        BridgeProtocol.EVENT_SEARCH_RESULTS,
                        "items" to array,
                        "provider" to search.providerName()
                    )
                }
            }
        }
    }

    /* ═══════════════════════════ future ads ═══════════════════════════ */

    private fun openAd(payload: JSONObject) {
        val url = payload.optStringOrNull("url") ?: return
        if (!AdConfig.ENABLED) return          // inactive: no click handling at all
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        activity.openExternalUrl(url)
    }

    /* ════════════════════════════ helpers ═════════════════════════════ */

    private inline fun withPlayer(crossinline block: (MediaController) -> Unit) {
        val controller = controllerProvider() ?: return
        block(controller)
    }

    private fun sendCustomCommand(action: String, args: Bundle) {
        val controller = controllerProvider() ?: return
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i)
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }
}
