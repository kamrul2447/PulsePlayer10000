package com.pulse.player.player

import android.os.SystemClock
import com.pulse.player.bridge.BridgeProtocol
import com.pulse.player.data.SettingsRepository
import com.pulse.player.util.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sleep timer that lives inside the playback service, so it keeps running when
 * the UI is closed or the screen is off.
 *
 * Guarantees:
 *  • The countdown is based on [SystemClock.elapsedRealtime] — it cannot be
 *    fooled by the user changing the clock.
 *  • The end time is persisted, so the timer survives a service restart.
 *  • It only ever pauses *our* playback ([onFire] is wired to the ExoPlayer
 *    owned by PulsePlaybackService). Another app's audio is never touched.
 */
class SleepTimerController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository
) {

    private var job: Job? = null
    private var endsAtRealtime: Long = 0L
    private var durationMs: Long = 0L

    var onFire: (() -> Unit)? = null

    fun start(durationMillis: Long) {
        cancel(clearPersisted = false)
        if (durationMillis <= 0) return
        durationMs = durationMillis
        endsAtRealtime = SystemClock.elapsedRealtime() + durationMillis
        scope.launch { settings.saveSleepTimer(System.currentTimeMillis() + durationMillis, durationMillis) }
        tick()
    }

    fun cancel(clearPersisted: Boolean = true) {
        job?.cancel()
        job = null
        endsAtRealtime = 0L
        durationMs = 0L
        if (clearPersisted) {
            scope.launch { settings.clearSleepTimer() }
        }
        publish(active = false)
    }

    /** Re-arms a timer that was running before the service was restarted. */
    fun restore() {
        scope.launch {
            val (endEpoch, duration) = settings.sleepTimer()
            if (endEpoch <= 0L) return@launch
            val remaining = endEpoch - System.currentTimeMillis()
            if (remaining <= 0L) {
                settings.clearSleepTimer()
                return@launch
            }
            durationMs = duration
            endsAtRealtime = SystemClock.elapsedRealtime() + remaining
            tick()
        }
    }

    fun isActive(): Boolean = endsAtRealtime > 0L

    fun remainingMs(): Long =
        if (endsAtRealtime <= 0L) 0L else (endsAtRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun tick() {
        job?.cancel()
        job = scope.launch {
            while (endsAtRealtime > 0L) {
                val remaining = remainingMs()
                if (remaining <= 0L) break
                publish(active = true)
                delay(if (remaining > 60_000L) 1_000L else 250L)
            }
            if (endsAtRealtime > 0L) {
                // Timer really finished (not cancelled).
                endsAtRealtime = 0L
                durationMs = 0L
                publish(active = false)
                scope.launch { settings.clearSleepTimer() }
                onFire?.invoke()
            }
        }
    }

    private fun publish(active: Boolean) {
        EventBus.emit(
            BridgeProtocol.EVENT_SLEEP,
            "active" to active,
            "endsAt" to if (active) System.currentTimeMillis() + remainingMs() else 0L,
            "remainingMs" to remainingMs(),
            "durationMs" to durationMs
        )
    }
}
