package com.pulse.player.bridge

import com.pulse.player.util.optStringOrNull
import org.json.JSONObject

/**
 * The complete JS ⇄ native contract, in one file.
 *
 * JS  → native : `PulseNative.postMessage(JSON)`  (see [JsBridge])
 * native → JS  : `PulseBridge.onNativeEvent(JSON)` (see web/js/core/bridge.js)
 *
 * Every message is `{ v:1, type|cmd, payload:{}, requestId? }`.
 * Keeping the names here means a typo is a compile error instead of a silent
 * mismatch between the web UI and the app.
 */
object BridgeProtocol {

    const val VERSION = 1

    /* ───────────────────────────── events (native → JS) ────────────────── */

    const val EVENT_READY = "ready"
    const val EVENT_PERMISSION = "permission"
    const val EVENT_SCAN = "scan"
    const val EVENT_LIBRARY = "library"
    const val EVENT_ARCHIVE = "archive"
    const val EVENT_REVIEW_QUEUE = "reviewQueue"
    const val EVENT_FAVORITES = "favorites"
    const val EVENT_QUEUE = "queue"
    const val EVENT_STATE = "state"
    const val EVENT_POSITION = "position"
    const val EVENT_SLEEP = "sleep"
    const val EVENT_SETTINGS = "settings"
    const val EVENT_ONLINE_STATUS = "onlineStatus"
    const val EVENT_SEARCH_RESULTS = "searchResults"
    const val EVENT_SEARCH_ERROR = "searchError"
    const val EVENT_ADS = "ads"
    const val EVENT_TOAST = "toast"
    const val EVENT_ERROR = "error"
    const val EVENT_REPLY = "reply"

    /* ───────────────────────────── commands (JS → native) ──────────────── */

    const val CMD_READY = "ready"
    const val CMD_REQUEST_PERMISSION = "requestPermission"
    const val CMD_OPEN_APP_SETTINGS = "openAppSettings"
    const val CMD_SCAN_LIBRARY = "scanLibrary"
    const val CMD_ARCHIVE_TRACK = "archiveTrack"
    const val CMD_RESTORE_TRACK = "restoreTrack"
    const val CMD_DELETE_TRACK = "deleteTrack"
    const val CMD_APPROVE_REVIEW = "approveReview"
    const val CMD_REJECT_REVIEW = "rejectReview"

    const val CMD_PLAY_TRACKS = "playTracks"
    const val CMD_PLAY_REMOTE = "playRemote"
    const val CMD_PLAY_ARCHIVED = "playArchived"
    const val CMD_PLAY = "play"
    const val CMD_PAUSE = "pause"
    const val CMD_TOGGLE_PLAY = "togglePlay"
    const val CMD_NEXT = "next"
    const val CMD_PREV = "prev"
    const val CMD_SEEK = "seek"
    const val CMD_SEEK_BY = "seekBy"
    const val CMD_SET_VOLUME = "setVolume"
    const val CMD_SET_SHUFFLE = "setShuffle"
    const val CMD_SET_REPEAT = "setRepeat"
    const val CMD_CYCLE_REPEAT = "cycleRepeat"
    const val CMD_ENQUEUE_NEXT = "enqueueNext"
    const val CMD_CLEAR_QUEUE = "clearQueue"
    const val CMD_RESTORE_QUEUE = "restoreQueue"

    const val CMD_SET_FAVORITE = "setFavorite"

    const val CMD_START_SLEEP_TIMER = "startSleepTimer"
    const val CMD_CANCEL_SLEEP_TIMER = "cancelSleepTimer"

    const val CMD_SET_SETTING = "setSetting"
    const val CMD_THEME_CHANGED = "themeChanged"

    const val CMD_SEARCH_ONLINE = "searchOnline"
    const val CMD_CANCEL_SEARCH = "cancelSearch"

    const val CMD_CLEAR_ART_CACHE = "clearArtCache"
    const val CMD_KEEP_AWAKE = "keepAwake"
    const val CMD_OPEN_AD = "openAd"
    const val CMD_OPEN_APP_SPACE = "openAppSpace"
    const val CMD_ADD_APP_SPACE = "addAppSpace"
    const val CMD_PLAYER_OPENED = "playerOpened"
    const val CMD_PLAYER_CLOSED = "playerClosed"
    const val CMD_LOG = "log"

    /* ───────────────────────────── custom session commands ─────────────── */

    const val SESSION_TOGGLE_FAVORITE = "com.pulse.player.TOGGLE_FAVORITE"
    const val SESSION_SET_SLEEP_TIMER = "com.pulse.player.SET_SLEEP_TIMER"
    const val SESSION_CANCEL_SLEEP_TIMER = "com.pulse.player.CANCEL_SLEEP_TIMER"
    const val SESSION_STOP = "com.pulse.player.STOP"

    /* ───────────────────────────── helpers ─────────────────────────────── */

    fun event(type: String, payload: JSONObject = JSONObject(), requestId: String? = null): JSONObject =
        JSONObject().apply {
            put("v", VERSION)
            put("type", type)
            put("payload", payload)
            if (requestId != null) put("requestId", requestId)
        }

    /** Parses an incoming message; returns null when it is unusable. */
    fun parse(raw: String?): BridgeMessage? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val cmd = json.optString("cmd").takeIf { it.isNotBlank() } ?: return null
            BridgeMessage(
                version = json.optInt("v", VERSION),
                cmd = cmd,
                payload = json.optJSONObject("payload") ?: JSONObject(),
                requestId = json.optStringOrNull("requestId")
            )
        } catch (t: Throwable) {
            null
        }
    }
}

data class BridgeMessage(
    val version: Int,
    val cmd: String,
    val payload: JSONObject,
    val requestId: String?
)

/** Event waiting to be delivered to the WebView. */
data class BridgeEvent(
    val type: String,
    val payload: JSONObject = JSONObject(),
    val requestId: String? = null
) {
    fun toJson(): JSONObject = BridgeProtocol.event(type, payload, requestId)
}
