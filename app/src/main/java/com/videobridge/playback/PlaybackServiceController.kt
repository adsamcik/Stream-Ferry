package com.videobridge.playback

import android.content.Context
import com.videobridge.playback.proxy.ProxyForegroundLatch
import com.videobridge.playback.proxy.ProxyPlaybackService

/**
 * Starts/stops the user-visible playback foreground service (§3). Abstracted so the playback engine
 * doesn't depend on Android `Service` plumbing directly.
 */
interface PlaybackServiceController {
    fun start()
    fun stop()

    /**
     * Suspend until the freshly-[start]ed service has actually called `startForeground()`, or
     * [timeoutMs] elapses. The engine awaits this BEFORE the renderer connect handshake so the
     * `startForegroundService()` deadline can't be missed behind main-thread work (see
     * `docs/PROXY_DESIGN.md`). Best-effort: returns `false` on timeout and the caller proceeds anyway.
     */
    suspend fun awaitForegrounded(timeoutMs: Long = DEFAULT_FOREGROUND_TIMEOUT_MS): Boolean

    companion object {
        /** Comfortably under the ~5 s start-foreground deadline; foregrounding on a quiet main thread is near-instant. */
        const val DEFAULT_FOREGROUND_TIMEOUT_MS = 4_000L
    }
}

class AndroidPlaybackServiceController(context: Context) : PlaybackServiceController {
    private val appContext = context.applicationContext

    override fun start() {
        // Arm the latch BEFORE startForegroundService() so the barrier exists when the service signals.
        ProxyForegroundLatch.arm()
        ProxyPlaybackService.start(appContext)
    }

    override fun stop() = ProxyPlaybackService.stop(appContext)

    override suspend fun awaitForegrounded(timeoutMs: Long): Boolean = ProxyForegroundLatch.await(timeoutMs)
}
