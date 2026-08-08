package com.adsamcik.streamferry.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.adsamcik.streamferry.R
import com.adsamcik.streamferry.app.StreamFerryApplication
import com.adsamcik.streamferry.app.startForegroundCompat
import com.adsamcik.streamferry.data.download.MediaDownloader.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while downloads are active and shows a progress
 * notification (§5 download exception). The download coroutines continue running on [AppContainer.ioScope];
 * this service only prevents process death and owns the "dataSync" FGS type.
 *
 * The service self-stops as soon as all downloads finish or are cancelled.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopService()
                return START_NOT_STICKY // an explicit stop must NOT be auto-restarted
            }
            else -> {
                // ACTION_START, or a null intent when the system restarts this sticky service after the
                // process was killed in the background. Satisfy the start-foreground contract FIRST (same
                // pattern as ProxyPlaybackService), then resume any persisted downloads and observe.
                enterForeground(buildNotification(1, null))
                startObservingWithResume()
            }
        }
        // START_STICKY: if the OS kills the process while a download is active, recreate the service
        // (with a null intent) so resumePending(owner) can continue the active account's `.part` file.
        return START_STICKY
    }

    private fun startObservingWithResume() {
        val container = (application as? StreamFerryApplication)?.container
        val downloader = container?.downloader
        // The downloader uses the active Jellyfin client, which can authenticate only one account at a
        // time. Never resurrect an arbitrary persisted owner from a sticky service restart: wait until
        // the app has restored a live, identity-verified session and resume that exact account only.
        val user = container?.authRepository?.currentUser?.value
        if (downloader == null || user == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val owner = DownloadOwner(serverId = user.serverId, userId = user.userId)
        // Each download enqueue re-sends ACTION_START; keep exactly ONE observer (cancel any prior)
        // so we don't accumulate N collectors racing the notification/stop logic.
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            // Re-enqueue persisted-but-unfinished downloads BEFORE observing, so the first emitted
            // state reflects them and the idle check below can't stop the service prematurely.
            runCatching { downloader.resumePending(owner) }
                .onFailure {
                    container.logger.w("download", "resumePending failed", it)
                }
            downloader.states.collect { states ->
                val active = states.count { (identity, state) ->
                    identity.owner == owner && (state is DownloadState.Queued || state is DownloadState.Running)
                }
                if (active == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    cancel() // stop collecting; onDestroy will cancel the scope
                } else {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(active, aggregateFraction(states, owner)))
                }
            }
        }
    }

    /** Android 14+ short-service timeout (unused type here, but override defensively). */
    override fun onTimeout(startId: Int) = handleTimeout()

    /** Android 15+ typed-FGS timeout: the cumulative dataSync runtime cap was reached. */
    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    private fun handleTimeout() {
        // The system capped this foreground service's runtime. Stop cleanly — the `.part` files and the
        // persisted queue mean every unfinished download resumes automatically on the next app open.
        runCatching {
            (application as? StreamFerryApplication)?.logger
                ?.event("download", "Download service paused by system runtime limit; will resume later")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground(notification: Notification) {
        startForegroundCompat(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun buildNotification(active: Int, fraction: Float?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val text = if (active == 1) "Downloading 1 item\u2026" else "Downloading $active items\u2026"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
        if (fraction != null) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun aggregateFraction(states: Map<DownloadIdentity, DownloadState>, owner: DownloadOwner): Float? {
        val fractions = states
            .filterKeys { it.owner == owner }
            .values
            .filterIsInstance<DownloadState.Running>()
            .mapNotNull { it.fraction }
        return if (fractions.isEmpty()) null else fractions.average().toFloat()
    }

    private fun stopService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val ACTION_START = "com.adsamcik.streamferry.action.DOWNLOAD_START"
        private const val ACTION_STOP = "com.adsamcik.streamferry.action.DOWNLOAD_STOP"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_STOP))
        }
    }
}
