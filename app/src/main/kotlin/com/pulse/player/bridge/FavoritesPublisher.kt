package com.pulse.player.bridge

import com.pulse.player.data.LibraryRepository
import com.pulse.player.util.EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Favorites are stored in Room, so they survive closing the app, restarting the
 * phone, and a full library rescan. This helper is the single place that
 * broadcasts them, so the UI, the notification and the library view always
 * agree on the same list.
 */
object FavoritesPublisher {

    suspend fun publish(repository: LibraryRepository) {
        val uids = withContext(Dispatchers.IO) { repository.favoriteUids() }
        EventBus.emit(BridgeProtocol.EVENT_FAVORITES, "uids" to JSONArray(uids))
    }
}
