package com.adsamcik.streamferry.playback.proxy

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.adsamcik.streamferry.playback.MediaSessionController
import com.adsamcik.streamferry.playback.PlaybackNotificationFactory
import com.adsamcik.streamferry.source.api.DiagnosticSink
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Which Android service-start API accepted a foreground-service request. */
enum class ProxyForegroundStartPath {
    START_SERVICE,
    START_FOREGROUND_SERVICE,
}

/**
 * Result of requesting the proxy foreground service. This only says Android accepted the request;
 * [ProxyForegroundLatch] separately confirms the service actually entered the foreground.
 */
sealed interface ProxyForegroundStartRequestResult {
    data class Requested(val path: ProxyForegroundStartPath) : ProxyForegroundStartRequestResult
    data class Rejected(val cause: Exception) : ProxyForegroundStartRequestResult
}

/**
 * Foreground service that keeps the in-RAM proxy + active stream alive ONLY during user-visible
 * playback (§3), and hosts the **playback-controls** media notification.
 *
 * FGS-type decision record (verified against the Android foreground-service-types docs):
 *   type = mediaPlayback. The service exists to sustain an ongoing, user-initiated media stream to
 *   an external Cast/DLNA renderer. mediaPlayback is the documented type for "continuing audio or
 *   video playback" and is the closest first-class fit. See docs/PROXY_DESIGN.md for the rationale.
 *
 * The notification (MediaStyle, with play/pause/seek/stop) and the `MediaSession` are owned by
 * [MediaSessionController]; this service foregrounds with that notification and forwards media-button
 * / notification-action intents to it. The service is NOT started from boot and stops on
 * stop/error/cancel/session expiry.
 */
class ProxyPlaybackService : Service() {

    private val controller: MediaSessionController?
        get() = (application as? PlaybackServiceOwner)?.playbackServiceDependencies()?.controller

    private val logger: DiagnosticSink?
        get() = (application as? PlaybackServiceOwner)?.playbackServiceDependencies()?.logger

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        logger?.event("service", "Playback service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger?.event("service", "Playback service command ${intent?.action?.substringAfterLast('.')} id=$startId")
        when (intent?.action) {
            ACTION_START -> startForegroundPlayback(
                intent.getLongExtra(EXTRA_FOREGROUND_LATCH_TOKEN, NO_FOREGROUND_LATCH_TOKEN),
                startId,
            )
            ACTION_STOP -> stopSelfSafely(startId)
            PlaybackNotificationFactory.ACTION_PLAY,
            PlaybackNotificationFactory.ACTION_PAUSE,
            PlaybackNotificationFactory.ACTION_STOP,
            PlaybackNotificationFactory.ACTION_FFWD,
            PlaybackNotificationFactory.ACTION_RWND,
            -> controller?.dispatch(intent.action)
            else -> {
                // Any other delivery (a null/unknown action, or a redelivered startForegroundService()
                // intent) must STILL satisfy the start-foreground contract before we bail out, or the
                // system raises ForegroundServiceDidNotStartInTimeException.
                enterForeground(fallbackNotification())
                stopSelfSafely(startId)
            }
        }
        // Do NOT auto-restart if the system kills us with no pending intent.
        return START_NOT_STICKY
    }

    private fun startForegroundPlayback(foregroundLatchToken: Long, startId: Int) {
        // The system gives a service started with startForegroundService() only a few seconds to call
        // startForeground(). On a real Cast/DLNA connection the main thread is busy with the SDK
        // handshake, so do the cheapest possible thing FIRST: foreground with a dependency-free
        // fallback notification, WITHOUT touching the lazily-constructed MediaSessionController (which
        // builds a MediaSession + a full MediaStyle notification). Only THEN upgrade to the rich
        // playback-controls notification. Missing this deadline crashed on some devices (e.g. Galaxy
        // S24 / Android 16) with ForegroundServiceDidNotStartInTimeException.
        enterForeground(fallbackNotification())
        logForegroundLatency()
        val c = controller ?: run { stopSelfSafely(startId); return }
        try {
            acquireLocks()
        } catch (error: Exception) {
            logger?.e(TAG, "Playback CPU lock unavailable; refusing unprotected playback", error)
            stopSelfSafely(startId)
            return
        }
        activeInstance = this
        if (foregroundLatchToken != NO_FOREGROUND_LATCH_TOKEN) {
            ProxyForegroundLatch.signalForegrounded(foregroundLatchToken)
        }
        logger?.event("service", "Playback foreground ownership confirmed id=$startId cpuLock=${wakeLock?.isHeld == true}")
        runCatching { enterForeground(c.currentNotification()) }
    }

    /**
     * One-time (per start cycle) telemetry recording how long the deadline-critical `startForeground()`
     * took relative to the [start] request, and which start path was used. The ~5 s start-in-time deadline
     * only bites the `startForegroundService()` (backgrounded) path, so surface that path — and any near-miss
     * on the foreground path — in the exported diagnostics; keep the normal fast foreground case to debug
     * logcat only. This is the primary breadcrumb for any future `ForegroundServiceDidNotStartInTimeException`.
     */
    private fun logForegroundLatency() {
        val startedAt = startRequestedElapsedMs
        if (startedAt <= 0L) return
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val path = if (startedFromBackground) "startForegroundService (backgrounded; deadline applies)"
        else "startService (foreground; deadline-free)"
        val msg = "Proxy FGS foregrounded ${elapsedMs}ms after start via $path"
        if (startedFromBackground || elapsedMs >= FOREGROUND_LATENCY_WARN_MS) logger?.w("playback", msg)
        else logger?.d(TAG, msg)
    }

    private fun enterForeground(
        notification: Notification,
    ) {
        startForegroundCompat(
            PlaybackNotificationFactory.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /** Cheap, dependency-free notification used to enter the foreground within the deadline (then upgraded). */
    private fun fallbackNotification(): Notification = PlaybackNotificationFactory.fallback(this)

    private fun stopSelfSafely(startId: Int) {
        // An old STOP must not demote a newer START already queued by Android. Destruction
        // releases the locks; do not call stopForeground/releaseLocks before this guard.
        val stopped = stopSelfResult(startId)
        logger?.event("service", "Playback service stop id=$startId accepted=$stopped")
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        releaseLocks()
        logger?.event("service", "Playback service destroyed; locks released")
        super.onDestroy()
    }

    /**
     * Keep the CPU awake while serving the TV, including with the screen off. The Wi-Fi latency lock
     * is an additional screen-on optimization; it cannot guarantee screen-off radio behavior.
     * Idempotent; both locks are released in [releaseLocks].
     */
    @Suppress("WakelockTimeout") // intentionally held for the whole casting session, released on stop.
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = checkNotNull(getSystemService(PowerManager::class.java))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_TAG:cpu")
                .apply { setReferenceCounted(false); acquire() }
        }
        check(wakeLock?.isHeld == true) { "CPU wake lock was not acquired" }
        if (wifiLock == null) {
            runCatching {
                // This optimization is active only in the foreground with the screen on.
                // Screen-off playback relies on the foreground service and CPU lock, not
                // a promise that this Wi-Fi lock bypasses Android's power-management policy.
                val mode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(mode, "$WAKE_TAG:wifi")
                    ?.apply { setReferenceCounted(false); acquire() }
            }.onFailure { logger?.w(TAG, "Wi-Fi latency optimization unavailable", it) }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        @Volatile private var activeInstance: ProxyPlaybackService? = null
        fun isForegroundActive(): Boolean = activeInstance != null
        private const val WAKE_TAG = "StreamFerry:ProxyPlayback"
        private const val TAG = "ProxyPlaybackService"
        const val ACTION_START = "com.adsamcik.streamferry.action.START"
        const val ACTION_STOP = "com.adsamcik.streamferry.action.STOP"
        private const val EXTRA_FOREGROUND_LATCH_TOKEN = "com.adsamcik.streamferry.extra.FOREGROUND_LATCH_TOKEN"
        private const val NO_FOREGROUND_LATCH_TOKEN = -1L

        /** Foreground-latency (ms) at/above which we surface a near-miss warning in the exported diagnostics. */
        private const val FOREGROUND_LATENCY_WARN_MS = 2_000L

        /** [SystemClock.elapsedRealtime] when [start] last requested a start; read by logForegroundLatency(). */
        @Volatile private var startRequestedElapsedMs: Long = 0L

        /** Whether the last [start] fell back to the deadline-bound startForegroundService() (app backgrounded). */
        @Volatile private var startedFromBackground: Boolean = false

        fun start(
            context: Context,
            foregroundLatchToken: Long,
        ): ProxyForegroundStartRequestResult {
            val i = Intent(context, ProxyPlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FOREGROUND_LATCH_TOKEN, foregroundLatchToken)
            startRequestedElapsedMs = SystemClock.elapsedRealtime()
            val logger = (context.applicationContext as? PlaybackServiceOwner)
                ?.playbackServiceDependencies()?.logger
            // Prefer a plain startService(): when the app is in the foreground (always true for a
            // user-initiated play), it is NOT subject to the ~5 s startForegroundService() ->
            // startForeground() deadline, so the service can foreground the instant the main thread frees
            // up — no ForegroundServiceDidNotStartInTimeException even if the main thread is briefly busy.
            // Fall back to startForegroundService() only when the app is backgrounded (e.g. a screen-off
            // auto-reconnect), where startService() is disallowed; there the deadline applies and the
            // engine's foreground barrier (startAndAwaitForegrounded) plus onStartCommand-foregrounds-first keep us
            // inside it. See docs/PROXY_DESIGN.md.
            val initialFailure = try {
                checkNotNull(context.startService(i)) { "Android did not accept proxy service start" }
                null
            } catch (e: Exception) {
                e
            }
            if (initialFailure == null) {
                startedFromBackground = false
                logger?.d(TAG, "Proxy FGS start via startService() (app foreground; no start-in-time deadline)")
                return ProxyForegroundStartRequestResult.Requested(ProxyForegroundStartPath.START_SERVICE)
            }

            // BackgroundServiceStartNotAllowedException (API 31+) / IllegalStateException: app is in the
            // background — must use the deadline-bound foreground-service start instead. Unlike the old
            // best-effort path, failure here is returned to the engine and aborts playback before a proxy
            // session or renderer load can be created.
            logger?.w(
                "playback",
                "Proxy FGS start fell back to startForegroundService() (app backgrounded; ~5s deadline applies)",
                initialFailure,
            )
            val fallbackFailure = try {
                checkNotNull(context.startForegroundService(i)) { "Android did not accept proxy foreground-service start" }
                null
            } catch (e: Exception) {
                e
            }
            return if (fallbackFailure == null) {
                startedFromBackground = true
                ProxyForegroundStartRequestResult.Requested(ProxyForegroundStartPath.START_FOREGROUND_SERVICE)
            } else {
                startedFromBackground = false
                logger?.e("playback", "Proxy foreground-service start was rejected; playback will not continue", fallbackFailure)
                ProxyForegroundStartRequestResult.Rejected(fallbackFailure)
            }
        }

        fun stop(context: Context) {
            // Synchronously revoke Android's started-service ownership before the next start.
            // Queuing ACTION_STOP via startService can create a service merely to stop it,
            // then destroy a replacement START which has already confirmed its latch.
            context.stopService(Intent(context, ProxyPlaybackService::class.java))
        }
    }
}

data class PlaybackServiceDependencies(
    val controller: MediaSessionController,
    val logger: DiagnosticSink,
)

/** Implemented by the application composition root; the playback module never imports the app. */
interface PlaybackServiceOwner {
    fun playbackServiceDependencies(): PlaybackServiceDependencies?
}

/**
 * One-shot barrier that lets the playback engine wait until [ProxyPlaybackService] has actually called
 * `startForeground()`. The engine arms it immediately before `startForegroundService()` and then blocks
 * on it BEFORE kicking off the Cast/DLNA connect handshake — which saturates the **main thread** and, if
 * it (or the media load) runs before `onStartCommand` gets a turn, makes the service miss the ~5 s
 * start-foreground deadline and crash with `ForegroundServiceDidNotStartInTimeException` (observed on a
 * Galaxy S24 / Android 16). Satisfying the obligation on a quiet main thread first closes that race; the
 * handshake is then free to saturate the main thread. See docs/PROXY_DESIGN.md.
 */
internal object ProxyForegroundLatch {
    private data class Pending(val token: Long, val signal: CompletableDeferred<Unit>)

    private val lock = Any()
    private val nextToken = AtomicLong(0L)
    private var pending: Pending? = null

    /** Arm a fresh latch and return its token; supersedes any previous pending confirmation. */
    fun arm(): Long = synchronized(lock) {
        val token = nextToken.incrementAndGet()
        pending = Pending(token, CompletableDeferred())
        token
    }

    /**
     * Called by the service the instant `startForeground()` has run. Only the matching start request can
     * complete the barrier, which prevents a delayed delivery from an older service start from confirming
     * a newer playback attempt.
     */
    fun signalForegrounded(token: Long) {
        synchronized(lock) { pending?.takeIf { it.token == token }?.signal?.complete(Unit) }
    }

    /** Stop accepting a confirmation for [token]. Safe after a success, timeout, or cancellation. */
    fun cancel(token: Long) {
        synchronized(lock) {
            if (pending?.token == token) pending = null
        }
    }

    /** Suspend until the matching service start foregrounds or [timeoutMs] elapses. */
    suspend fun await(token: Long, timeoutMs: Long): Boolean {
        val signal = synchronized(lock) { pending?.takeIf { it.token == token }?.signal } ?: return false
        return withTimeoutOrNull(timeoutMs) { signal.await() } != null
    }
}
