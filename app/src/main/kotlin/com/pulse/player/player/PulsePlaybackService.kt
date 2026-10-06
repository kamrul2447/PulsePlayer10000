package com.pulse.player.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pulse.player.bridge.BridgeProtocol
import com.pulse.player.bridge.FavoritesPublisher
import com.pulse.player.data.LibraryRepository
import com.pulse.player.data.SettingsRepository
import com.pulse.player.di.ServiceLocator
import com.pulse.player.ui.MainActivity
import com.pulse.player.util.EventBus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * THE authoritative player.
 *
 * Everything that can outlive the UI lives here:
 *   • the single ExoPlayer instance (no <audio> element anywhere)
 *   • a MediaSession → notification, lock screen, Bluetooth, Android Auto,
 *     headset buttons, Google Assistant
 *   • audio focus + "becoming noisy" (headphones unplugged) handling
 *   • the sleep timer, which must keep counting while the app is backgrounded
 *   • persistence of the queue and the last position
 *
 * The web UI never plays audio; it sends intents and renders the state
 * published from this service. That is why the notification, the mini player,
 * the full player and the library can never disagree.
 */
@UnstableApi
class PulsePlaybackService : MediaSessionService() {

    // A media service must survive non-fatal persistence/controller failures.
    // SupervisorJob alone does not prevent an uncaught child coroutine exception
    // from reaching the main thread and taking down the whole process.
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("PulsePlaybackService", "Background player task failed", throwable)
        runCatching {
            EventBus.emit(
                BridgeProtocol.EVENT_ERROR,
                "code" to "service",
                "message" to (throwable.message ?: "A background player task failed")
            )
        }
    }
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + serviceExceptionHandler
    )

    private lateinit var player: ExoPlayer
    private lateinit var repository: LibraryRepository
    private lateinit var settings: SettingsRepository
    private lateinit var sleepTimer: SleepTimerController

    private var mediaSession: MediaSession? = null
    private var positionJob: kotlinx.coroutines.Job? = null
    private var consecutiveErrors = 0

    /* ═══════════════════════════ lifecycle ═══════════════════════════ */

    override fun onCreate() {
        super.onCreate()
        repository = ServiceLocator.library(this)
        settings = ServiceLocator.settings(this)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Pause when headphones/BT disconnect — normal Android behaviour.
            .setHandleAudioBecomingNoisy(true)
            // Keep CPU + Wi-Fi awake while playing, so streamed tracks also survive screen-off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Pulse is an audio-first player. Music-video MP4 files are intentionally
        // played without a video renderer, which reduces decoder work, heat and
        // the chance of an OEM video codec taking down playback. The audio track
        // remains fully playable.
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }.onFailure {
            android.util.Log.w("PulsePlaybackService", "Could not disable video track", it)
        }

        player.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivityIntent())
            .setCallback(SessionCallback())
            .build()

        // Keep Media3's native notification so Android 13+ System UI, lock-screen
        // controls and Bluetooth controls remain authoritative. Give it a Pulse
        // branded monochrome status-bar icon. Android System UI owns the actual
        // notification animation/progress on modern Android, so we do not fake a
        // continuously animated RemoteViews notification.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId("pulse_playback")
            .setChannelName(com.pulse.player.R.string.channel_playback_name)
            .build()
        notificationProvider.setSmallIcon(com.pulse.player.R.drawable.ic_pulse_notification)
        setMediaNotificationProvider(notificationProvider)

        sleepTimer = SleepTimerController(serviceScope, settings)
        sleepTimer.onFire = { pausePlaybackFromSleepTimer() }
        sleepTimer.restore()

        startPositionBroadcast()
        restoreQueueIfNeeded()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        positionJob?.cancel()
        // Persisted on purpose: a service restart re-arms the timer from disk.
        if (::sleepTimer.isInitialized) sleepTimer.cancel(clearPersisted = false)
        if (::player.isInitialized) {
            // State is persisted on every meaningful player transition. Do not
            // launch another coroutine here because the scope is about to die.
            player.removeListener(playerListener)
            player.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately NOT stopping playback: the user pressed Home or swiped the
        // app away, and music should keep going. Media3 will stop the service by
        // itself once playback is finished and nothing is connected.
        super.onTaskRemoved(rootIntent)
    }

    /* ═══════════════════════ session callbacks ═══════════════════════ */

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ConnectionResult {
            val sessionCommands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(BridgeProtocol.SESSION_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(BridgeProtocol.SESSION_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(BridgeProtocol.SESSION_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(BridgeProtocol.SESSION_STOP, Bundle.EMPTY))
                .build()

            return ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                BridgeProtocol.SESSION_TOGGLE_FAVORITE -> {
                    val uid = args.getString("uid") ?: currentUid()
                    if (uid != null) {
                        val fav = args.getBoolean("favorite", true)
                        serviceScope.launch {
                            runCatching {
                                repository.favorites.set(uid, fav)
                                FavoritesPublisher.publish(repository)
                                EventBus.emit(
                                    BridgeProtocol.EVENT_TOAST,
                                    "message" to if (fav) "Added to favorites" else "Removed from favorites"
                                )
                            }.onFailure {
                                android.util.Log.e("PulsePlaybackService", "Favorite update failed", it)
                                EventBus.emit(BridgeProtocol.EVENT_ERROR, "code" to "favorite", "message" to "Could not update favorite")
                            }
                        }
                    }
                }

                BridgeProtocol.SESSION_SET_SLEEP_TIMER -> {
                    val ms = args.getLong("durationMs", 0L)
                    if (ms > 0) sleepTimer.start(ms)
                }

                BridgeProtocol.SESSION_CANCEL_SLEEP_TIMER -> sleepTimer.cancel()

                BridgeProtocol.SESSION_STOP -> {
                    player.stop()
                    player.clearMediaItems()
                    stopSelf()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Lets any controller (notification, Bluetooth, Wear OS, Android Auto)
         * request playback by uid alone — the service resolves it from Room.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> = serviceScope.future(Dispatchers.IO) {
            mediaItems.map { item ->
                val hasUri = item.localConfiguration?.uri != null &&
                    item.localConfiguration?.uri != android.net.Uri.EMPTY
                if (hasUri) return@map item
                val track = repository.trackByUid(item.mediaId) ?: return@map item
                MediaItemFactory.fromTrack(track)
            }
        }
    }

    /* ═══════════════════════ state broadcasting ═══════════════════════ */

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val uid = mediaItem?.mediaId
            if (uid != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                serviceScope.launch {
                    runCatching { repository.registerPlay(uid) }
                        .onFailure { android.util.Log.e("PulsePlaybackService", "Play-count update failed", it) }
                }
            }
            runCatching { savePlaybackState() }
            runCatching { publishState() }
            runCatching { publishQueue() }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrors = 0
            runCatching { savePlaybackState() }
            runCatching { publishState() }
            if (isPlaying) startPositionBroadcast()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                runCatching { publishState() }
            }
            if (playbackState == Player.STATE_ENDED) runCatching { savePlaybackState() }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            runCatching { savePlaybackState() }
            runCatching { publishState() }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            runCatching { savePlaybackState() }
            runCatching { publishState() }
        }

        override fun onVolumeChanged(volume: Float) {
            runCatching { publishState() }
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e(
                "PulsePlaybackService",
                "Media3 playback error: code=${error.errorCodeName}, message=${error.message}",
                error
            )
            EventBus.emit(
                BridgeProtocol.EVENT_ERROR,
                "code" to "playback",
                "message" to (error.message ?: "This file could not be played")
            )
            // After an error ExoPlayer is IDLE. Skipping to the next item is NOT
            // enough: it must be prepared again, otherwise playback silently
            // stops (the "music sometimes just stops" symptom). Give up only
            // after several failures in a row so an all-bad queue cannot loop.
            consecutiveErrors++
            runCatching {
                if (player.hasNextMediaItem() && consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    player.pause()
                    consecutiveErrors = 0
                }
            }.onFailure { android.util.Log.e("PulsePlaybackService", "Could not recover from playback error", it) }
            runCatching { publishState() }
        }
    }

    /** Position snapshots at 2 Hz while playing (the UI interpolates between). */
    private fun startPositionBroadcast() {
        if (positionJob?.isActive == true) return
        positionJob = serviceScope.launch {
            while (isActive && player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                publishState()
                delay(POSITION_INTERVAL_MS)
            }
        }
    }

    private fun publishState() {
        if (!::player.isInitialized) return
        runCatching {
        val mediaItem = player.currentMediaItem
        val meta = mediaItem?.mediaMetadata
        val extras = meta?.extras
        val source = extras?.getString("pulse.source") ?: "local"
        EventBus.emit(
            BridgeProtocol.EVENT_STATE,
            "uid" to currentUid(),
            "source" to source,
            "provider" to extras?.getString("pulse.provider"),
            "trackId" to extras?.getString("pulse.trackId"),
            "title" to meta?.title?.toString(),
            "artist" to meta?.artist?.toString(),
            "album" to meta?.albumTitle?.toString(),
            "artworkUrl" to extras?.getString("pulse.artworkUrl"),
            "playing" to (player.playWhenReady && player.playbackState != Player.STATE_ENDED),
            "position" to player.currentPosition.coerceAtLeast(0L),
            "duration" to player.duration.let { if (it == C.TIME_UNSET) 0L else it },
            "buffered" to player.bufferedPosition.coerceAtLeast(0L),
            "buffering" to (player.playbackState == Player.STATE_BUFFERING),
            "volume" to player.volume.coerceIn(0f, 1f),
            "shuffle" to player.shuffleModeEnabled,
            "repeat" to MediaItemFactory.repeatFromMedia3(player.repeatMode),
            "error" to JSONObject.NULL
        )
        }.onFailure {
            android.util.Log.w("PulsePlaybackService", "Could not publish player state", it)
        }
    }

    private fun publishQueue() {
        if (!::player.isInitialized) return
        runCatching {
        val uids = ArrayList<String>(player.mediaItemCount)
        for (i in 0 until player.mediaItemCount) {
            uids.add(player.getMediaItemAt(i).mediaId)
        }
        EventBus.emit(
            BridgeProtocol.EVENT_QUEUE,
            "uids" to org.json.JSONArray(uids),
            "currentUid" to currentUid()
        )
        }.onFailure {
            android.util.Log.w("PulsePlaybackService", "Could not publish player queue", it)
        }
    }

    /* ═══════════════════════ persistence ═════════════════════════════ */

    private fun savePlaybackState() {
        if (!::player.isInitialized || !::settings.isInitialized) return
        serviceScope.launch {
            runCatching {
                val uids = ArrayList<String>(player.mediaItemCount)
                for (i in 0 until player.mediaItemCount) uids.add(player.getMediaItemAt(i).mediaId)
                settings.savePlaybackState(
                    uids = uids,
                    index = player.currentMediaItemIndex.coerceAtLeast(0),
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    shuffle = player.shuffleModeEnabled,
                    repeat = MediaItemFactory.repeatFromMedia3(player.repeatMode),
                    volume = player.volume.coerceIn(0f, 1f)
                )
            }.onFailure {
                android.util.Log.e("PulsePlaybackService", "Playback state persistence failed", it)
            }
        }
    }

    /**
     * Rebuilds the last queue on a cold start — but *paused*, at the saved
     * position. Pulse Player never surprises the user by starting audio on its
     * own (that is the behaviour the original product had and it is the polite
     * behaviour for a music player).
     */
    private fun restoreQueueIfNeeded() {
        serviceScope.launch {
            runCatching {
                val resumeOnOpen = settings.current()["resumeOnOpen"] as? Boolean ?: true
                if (!resumeOnOpen) return@runCatching
                val snapshot = settings.resumeSnapshot() ?: return@runCatching
                val tracks = repository.tracksByUids(snapshot.uids)
                if (tracks.isEmpty()) return@runCatching

                // The restore runs asynchronously. If the user already started
                // something while the database was being read, restoring now
                // would replace their queue and pause their music.
                if (player.mediaItemCount > 0 || player.playWhenReady) return@runCatching
                val items = tracks.map { MediaItemFactory.fromTrack(it) }
                val index = snapshot.index.coerceIn(0, items.lastIndex)
                player.setMediaItems(items, index, snapshot.positionMs)
                player.shuffleModeEnabled = snapshot.shuffle
                player.repeatMode = MediaItemFactory.repeatToMedia3(snapshot.repeat)
                player.volume = snapshot.volume.coerceIn(0f, 1f)
                player.prepare()
                player.playWhenReady = false

                publishQueue()
                publishState()
            }.onFailure {
                android.util.Log.e("PulsePlaybackService", "Could not restore playback queue", it)
            }
        }
    }

    /* ═════════════════════════ helpers ══════════════════════════════ */

    private fun pausePlaybackFromSleepTimer() {
        // Only stop if we are the ones playing — never kill another app's audio.
        if (player.playWhenReady) {
            player.pause()
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Sleep timer ended")
        }
    }

    private fun currentUid(): String? = player.currentMediaItem?.mediaId

    private fun sessionActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val SESSION_ID = "PulsePlayerSession"
        private const val POSITION_INTERVAL_MS = 500L
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
