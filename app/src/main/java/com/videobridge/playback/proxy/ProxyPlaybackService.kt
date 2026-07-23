package com.videobridge.playback.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.videobridge.R
import com.videobridge.app.JellyfinBridgeApplication
import com.videobridge.app.startForegroundCompat
import com.videobridge.logging.DiagnosticsLogger
import com.videobridge.playback.MediaSessionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

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
        get() = (application as? JellyfinBridgeApplication)?.container?.mediaSessionController

    private val logger: DiagnosticsLogger?
        get() = (application as? JellyfinBridgeApplication)?.container?.logger

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundPlayback()
            ACTION_STOP -> stopSelfSafely()
            MediaSessionController.ACTION_PLAY,
            MediaSessionController.ACTION_PAUSE,
            MediaSessionController.ACTION_STOP,
            MediaSessionController.ACTION_FFWD,
            MediaSessionController.ACTION_RWND,
            -> controller?.dispatch(intent.action)
            else -> {
                // Any other delivery (a null/unknown action, or a redelivered startForegroundService()
                // intent) must STILL satisfy the start-foreground contract before we bail out, or the
                // system raises ForegroundServiceDidNotStartInTimeException.
                enterForeground(fallbackNotification())
                stopSelfSafely()
            }
        }
        // Do NOT auto-restart if the system kills us with no pending intent.
        return START_NOT_STICKY
    }

    private fun startForegroundPlayback() {
        // The system gives a service started with startForegroundService() only a few seconds to call
        // startForeground(). On a real Cast/DLNA connection the main thread is busy with the SDK
        // handshake, so do the cheapest possible thing FIRST: foreground with a dependency-free
        // fallback notification, WITHOUT touching the lazily-constructed MediaSessionController (which
        // builds a MediaSession + a full MediaStyle notification). Only THEN upgrade to the rich
        // playback-controls notification. Missing this deadline crashed on some devices (e.g. Galaxy
        // S24 / Android 16) with ForegroundServiceDidNotStartInTimeException.
        enterForeground(fallbackNotification())
        logForegroundLatency()
        val c = controller ?: run { stopSelfSafely(); return } // nothing to keep alive
        acquireLocks()
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

    private fun enterForeground(notification: Notification) {
        startForegroundCompat(MediaSessionController.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // Release the playback engine, which armed this latch and is deliberately blocked BEFORE the
        // (main-thread-saturating) renderer handshake until the startForegroundService() obligation is met.
        ProxyForegroundLatch.signalForegrounded()
    }

    /** Cheap, dependency-free notification used to enter the foreground within the deadline (then upgraded). */
    private fun fallbackNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(MediaSessionController.CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(MediaSessionController.CHANNEL_ID, getString(R.string.notif_channel_playback), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, MediaSessionController.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_playing))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun stopSelfSafely() {
        // The PlaybackSessionCoordinator owns proxy.stop(); here we release our wake/Wi-Fi locks and
        // leave the foreground state.
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    /**
     * Acquire a partial CPU wake lock + a high-performance Wi-Fi lock for the duration of active
     * casting, so the phone keeps serving the proxy stream to the TV with the screen off / app
     * backgrounded. The phone is a media *server* here (it plays no local audio), so nothing else holds
     * the CPU/Wi-Fi awake — without these, Doze CPU-idle and Wi-Fi power-save stall the byte stream the
     * TV pulls from the in-RAM proxy. Idempotent; both are released in [releaseLocks].
     */
    @Suppress("WakelockTimeout") // intentionally held for the whole casting session, released on stop.
    private fun acquireLocks() {
        if (wakeLock == null) {
            runCatching {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_TAG:cpu")
                    ?.apply { setReferenceCounted(false); acquire() }
            }
        }
        if (wifiLock == null) {
            runCatching {
                // Keep Wi-Fi associated + awake while casting. On API 34+ (our min is 34) the low-latency
                // / no-power-save *tuning* only applies with the screen ON — there is no public API to
                // force Wi-Fi out of power-save with the screen off — but holding the lock still keeps the
                // radio up so it doesn't sleep/disassociate when the device idles; the partial wake lock
                // above covers the CPU. Together they materially improve screen-off casting reliability.
                val mode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(mode, "$WAKE_TAG:wifi")
                    ?.apply { setReferenceCounted(false); acquire() }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val WAKE_TAG = "JellyfinBridge:ProxyPlayback"
        private const val TAG = "ProxyPlaybackService"
        const val ACTION_START = "com.videobridge.action.START"
        const val ACTION_STOP = "com.videobridge.action.STOP"

        /** Foreground-latency (ms) at/above which we surface a near-miss warning in the exported diagnostics. */
        private const val FOREGROUND_LATENCY_WARN_MS = 2_000L

        /** [SystemClock.elapsedRealtime] when [start] last requested a start; read by logForegroundLatency(). */
        @Volatile private var startRequestedElapsedMs: Long = 0L

        /** Whether the last [start] fell back to the deadline-bound startForegroundService() (app backgrounded). */
        @Volatile private var startedFromBackground: Boolean = false

        fun start(context: Context) {
            val i = Intent(context, ProxyPlaybackService::class.java).setAction(ACTION_START)
            startRequestedElapsedMs = SystemClock.elapsedRealtime()
            val logger = (context.applicationContext as? JellyfinBridgeApplication)?.container?.logger
            // Prefer a plain startService(): when the app is in the foreground (always true for a
            // user-initiated play), it is NOT subject to the ~5 s startForegroundService() ->
            // startForeground() deadline, so the service can foreground the instant the main thread frees
            // up — no ForegroundServiceDidNotStartInTimeException even if the main thread is briefly busy.
            // Fall back to startForegroundService() only when the app is backgrounded (e.g. a screen-off
            // auto-reconnect), where startService() is disallowed; there the deadline applies and the
            // engine's foreground barrier (awaitForegrounded) plus onStartCommand-foregrounds-first keep us
            // inside it. See docs/PROXY_DESIGN.md.
            try {
                context.startService(i)
                startedFromBackground = false
                logger?.d(TAG, "Proxy FGS start via startService() (app foreground; no start-in-time deadline)")
            } catch (e: Exception) {
                // BackgroundServiceStartNotAllowedException (API 31+) / IllegalStateException: app is in
                // the background — must use the deadline-bound foreground-service start instead.
                startedFromBackground = true
                logger?.w("playback", "Proxy FGS start fell back to startForegroundService() (app backgrounded; ~5s deadline applies)")
                runCatching { context.startForegroundService(i) }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, ProxyPlaybackService::class.java).setAction(ACTION_STOP))
            }
        }
    }
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
    @Volatile private var signal: CompletableDeferred<Unit>? = null

    /** Arm a fresh latch just before `startForegroundService()`; supersedes any previous one. */
    fun arm() { signal = CompletableDeferred() }

    /** Called by the service the instant `startForeground()` has run (idempotent; safe to call again). */
    fun signalForegrounded() { signal?.complete(Unit) }

    /** Suspend until the service foregrounds or [timeoutMs] elapses; true if it foregrounded in time. */
    suspend fun await(timeoutMs: Long): Boolean {
        val s = signal ?: return true
        return withTimeoutOrNull(timeoutMs) { s.await() } != null
    }
}
