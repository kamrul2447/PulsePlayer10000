package com.pulse.player.bridge

import android.webkit.JavascriptInterface
import com.pulse.player.util.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The (only) object exposed to JavaScript, injected as `window.PulseNative`.
 *
 * A single `postMessage(String)` method is used on purpose:
 *   • the reflection surface stays tiny (one method to keep in ProGuard),
 *   • every message is validated in one place,
 *   • new commands can be added without touching the WebView wiring.
 *
 * Commands never return a value synchronously — they are dispatched on the main
 * thread and the result arrives later as a native event, which keeps the UI
 * thread free.
 */
class JsBridge(
    private val scope: CoroutineScope,
    private val handler: CommandHandler
) {

    fun interface CommandHandler {
        fun onCommand(cmd: String, payload: org.json.JSONObject, requestId: String?)
    }

    @JavascriptInterface
    fun postMessage(raw: String) {
        val message = BridgeProtocol.parse(raw)
        if (message == null) {
            EventBus.emit(
                BridgeProtocol.EVENT_ERROR,
                "code" to "bridge",
                "message" to "Malformed message"
            )
            return
        }
        if (message.version > BridgeProtocol.VERSION) {
            // Newer web bundle than app: tell the UI instead of misbehaving.
            EventBus.emit(
                BridgeProtocol.EVENT_ERROR,
                "code" to "protocol",
                "message" to "Web bundle is newer than the app"
            )
        }
        scope.launch(Dispatchers.Main) {
            handler.onCommand(message.cmd, message.payload, message.requestId)
        }
    }

    /** Convenience for the web layer: is the native side alive? */
    @JavascriptInterface
    fun ping(): String = "pong"

    /** Honest answer for the "Advertisements (future)" settings row. */
    @JavascriptInterface
    fun adsEnabled(): Boolean = com.pulse.player.ads.AdConfig.ENABLED
}
