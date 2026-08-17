package com.adsamcik.streamferry.playback

import android.content.Context
import com.adsamcik.streamferry.playback.proxy.ProxyForegroundLatch
import com.adsamcik.streamferry.playback.proxy.ProxyForegroundStartPath
import com.adsamcik.streamferry.playback.proxy.ProxyForegroundStartRequestResult
import com.adsamcik.streamferry.playback.proxy.ProxyPlaybackService

/**
 * Authoritative result of asking the proxy playback service to enter the foreground. A proxy URL must
 * not be created or loaded until this is [Foregrounded]: the service owns the locks that keep the
 * phone-hosted stream alive while the app is backgrounded.
 */
sealed interface ForegroundServiceStartResult {
    /** The service accepted the request and has called `startForeground()` for this exact request. */
    data class Foregrounded(val path: ProxyForegroundStartPath) : ForegroundServiceStartResult

    /** Android rejected both the normal service start and its foreground-service fallback. */
    data class StartRejected(val cause: Exception) : ForegroundServiceStartResult

    /** The start request was accepted but the service did not foreground before the safe deadline. */
    data class ConfirmationTimedOut(val timeoutMs: Long) : ForegroundServiceStartResult
}

/**
 * Starts/stops the user-visible playback foreground service (§3). Abstracted so the playback engine
 * doesn't depend on Android `Service` plumbing directly.
 */
interface PlaybackServiceController {
    fun stop()

    /**
     * Request the service and suspend until it has actually called `startForeground()`. The engine must
     * await this BEFORE the renderer connect handshake so the `startForegroundService()` deadline can't
     * be missed behind main-thread work. A timeout/rejection is terminal for this playback attempt; the
     * caller must not expose a proxy URL or load media on a renderer.
     */
    suspend fun startAndAwaitForegrounded(
        timeoutMs: Long = DEFAULT_FOREGROUND_TIMEOUT_MS,
    ): ForegroundServiceStartResult

    companion object {
        /** Comfortably under the ~5 s start-foreground deadline; foregrounding on a quiet main thread is near-instant. */
        const val DEFAULT_FOREGROUND_TIMEOUT_MS = 4_000L
    }
}

class AndroidPlaybackServiceController(context: Context) : PlaybackServiceController {
    private val appContext = context.applicationContext

    override fun stop() = ProxyPlaybackService.stop(appContext)

    override suspend fun startAndAwaitForegrounded(timeoutMs: Long): ForegroundServiceStartResult {
        // Arm the latch BEFORE asking Android to start the service. The request token is carried in the
        // intent, so a delayed onStartCommand from an earlier attempt cannot falsely confirm a newer one.
        val requestToken = ProxyForegroundLatch.arm()
        return when (val request = ProxyPlaybackService.start(appContext, requestToken)) {
            is ProxyForegroundStartRequestResult.Rejected -> {
                ProxyForegroundLatch.cancel(requestToken)
                ForegroundServiceStartResult.StartRejected(request.cause)
            }
            is ProxyForegroundStartRequestResult.Requested -> {
                val foregrounded = try {
                    ProxyForegroundLatch.await(requestToken, timeoutMs)
                } finally {
                    // The confirmation is one-shot. Clear it even on coroutine cancellation so no stale
                    // service delivery can satisfy a later attempt's barrier.
                    ProxyForegroundLatch.cancel(requestToken)
                }
                if (foregrounded) {
                    ForegroundServiceStartResult.Foregrounded(request.path)
                } else {
                    ForegroundServiceStartResult.ConfirmationTimedOut(timeoutMs)
                }
            }
        }
    }
}
