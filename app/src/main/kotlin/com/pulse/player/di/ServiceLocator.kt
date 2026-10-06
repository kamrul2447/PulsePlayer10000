package com.pulse.player.di

import android.content.Context
import com.pulse.player.data.LibraryRepository
import com.pulse.player.data.SettingsRepository
import com.pulse.player.media.ArtworkRepository
import com.pulse.player.media.MediaStoreScanner
import com.pulse.player.media.ReviewRepository
import com.pulse.player.search.MusicSearchRepository

/**
 * Hand-written dependency holder.
 *
 * A full DI framework (Hilt/Dagger) would add annotation processing, generated
 * code and build time for a graph this small. Keeping it explicit also makes
 * the ownership rules obvious: everything here is an application-scoped
 * singleton, and the playback service / activity only ever *use* them.
 */
object ServiceLocator {

    private var appContext: Context? = null

    @Volatile
    private var library: LibraryRepository? = null

    @Volatile
    private var settings: SettingsRepository? = null

    @Volatile
    private var artwork: ArtworkRepository? = null

    @Volatile
    private var scanner: MediaStoreScanner? = null

    @Volatile
    private var search: MusicSearchRepository? = null

    @Volatile
    private var review: ReviewRepository? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun context(): Context =
        appContext ?: throw IllegalStateException("ServiceLocator.init() was never called")

    fun library(context: Context = context()): LibraryRepository =
        library ?: synchronized(this) { library ?: LibraryRepository.get(context).also { library = it } }

    fun settings(context: Context = context()): SettingsRepository =
        settings ?: synchronized(this) { settings ?: SettingsRepository(context.applicationContext).also { settings = it } }

    fun artwork(context: Context = context()): ArtworkRepository =
        artwork ?: synchronized(this) { artwork ?: ArtworkRepository(context.applicationContext).also { artwork = it } }

    fun scanner(context: Context = context()): MediaStoreScanner =
        scanner ?: synchronized(this) { scanner ?: MediaStoreScanner(context.applicationContext).also { scanner = it } }

    fun review(context: Context = context()): ReviewRepository =
        review ?: synchronized(this) { review ?: ReviewRepository(context.applicationContext).also { review = it } }

    fun search(context: Context = context()): MusicSearchRepository =
        search ?: synchronized(this) {
            search ?: MusicSearchRepository { context().applicationContext }.also { search = it }
        }
}
