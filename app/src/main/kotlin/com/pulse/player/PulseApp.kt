package com.pulse.player

import android.app.Application
import com.pulse.player.ads.AdRepository
import com.pulse.player.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Kept deliberately tiny: the heavy work (database, scanner, player) is created
 * lazily by [ServiceLocator] when something actually needs it, so cold start
 * stays fast on low-end phones.
 */
class PulseApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // Warms the database and the artwork cache off the main thread.
        appScope.launch {
            ServiceLocator.library(this@PulseApp)
            ServiceLocator.artwork(this@PulseApp)
        }

        // The advertisement layer is architecturally present but switched off:
        // this call only publishes "disabled" to the UI and performs no I/O.
        appScope.launch { AdRepository.init() }

        // Events emitted before a UI exists are dropped on purpose: MainActivity
        // asks the service for a full snapshot as soon as the page is ready.
    }
}
