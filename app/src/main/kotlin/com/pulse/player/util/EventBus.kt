package com.pulse.player.util

import com.pulse.player.bridge.BridgeEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * Fan-out point for everything the app wants to tell the web UI.
 *
 * Producers (playback service, scanner, repositories) emit here without knowing
 * whether a UI exists. MainActivity is the only consumer and forwards events
 * into the WebView; while the app is in the background events are simply
 * dropped, and the UI asks for a fresh snapshot when it comes back.
 */
object EventBus {

    private val _events = MutableSharedFlow<BridgeEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<BridgeEvent> = _events.asSharedFlow()

    fun emit(type: String, payload: JSONObject = JSONObject(), requestId: String? = null) {
        _events.tryEmit(BridgeEvent(type, payload, requestId))
    }

    fun emit(type: String, vararg pairs: Pair<String, Any?>, requestId: String? = null) {
        val payload = JSONObject()
        pairs.forEach { (key, value) ->
            when (value) {
                null -> payload.put(key, JSONObject.NULL)
                is JSONObject -> payload.put(key, value)
                is org.json.JSONArray -> payload.put(key, value)
                else -> payload.put(key, value)
            }
        }
        emit(type, payload, requestId)
    }
}
