package com.adsamcik.streamferry.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.adsamcik.streamferry.R
import com.adsamcik.streamferry.playback.proxy.ProxyPlaybackService

/**
 * The single definition of Stream Ferry's playback notification.
 *
 * Android 13+ derives its media carousel from [PlaybackState], while [Notification.MediaStyle]
 * remains the correct notification-shell contract. Keeping both representations here prevents the
 * lock screen, notification shade, and hardware-controller actions from drifting apart.
 */
internal class PlaybackNotificationFactory(
    context: Context,
    private val sessionToken: MediaSession.Token,
    private val contentIntent: PendingIntent?,
) {
    enum class Phase {
        PREPARING,
        BUFFERING,
        PLAYING,
        PAUSED,
    }

    private val appContext = context.applicationContext

    init {
        ensureChannel(appContext)
    }

    fun build(
        title: String,
        targetName: String?,
        phase: Phase,
    ): Notification {
        val playing = phase == Phase.PLAYING
        val playPause = if (playing) {
            action(R.drawable.ic_notification_pause, R.string.pause, ACTION_PAUSE, REQUEST_PAUSE)
        } else {
            action(R.drawable.ic_notification_play, R.string.play, ACTION_PLAY, REQUEST_PLAY)
        }
        val style = Notification.MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return baseBuilder(appContext)
            .setContentTitle(title)
            .setContentText(statusText(targetName, phase))
            .setStyle(style)
            .addAction(
                action(
                    R.drawable.ic_notification_rewind,
                    R.string.notif_rewind_30,
                    ACTION_RWND,
                    REQUEST_REWIND,
                ),
            )
            .addAction(playPause)
            .addAction(
                action(
                    R.drawable.ic_notification_forward,
                    R.string.notif_forward_30,
                    ACTION_FFWD,
                    REQUEST_FORWARD,
                ),
            )
            .addAction(action(R.drawable.ic_notification_stop, R.string.stop, ACTION_STOP, REQUEST_STOP))
            .apply { contentIntent?.let(::setContentIntent) }
            .build()
    }

    /**
     * Adds the ordered custom controls consumed by Android's modern media carousel. Play/pause and
     * seeking remain standard session actions; rewind, forward, and stop occupy the remaining slots.
     */
    fun addSystemControls(builder: PlaybackState.Builder): PlaybackState.Builder =
        builder
            .addCustomAction(
                customAction(ACTION_RWND, R.string.notif_rewind_30, R.drawable.ic_notification_rewind),
            )
            .addCustomAction(
                customAction(ACTION_FFWD, R.string.notif_forward_30, R.drawable.ic_notification_forward),
            )
            .addCustomAction(customAction(ACTION_STOP, R.string.stop, R.drawable.ic_notification_stop))

    private fun statusText(targetName: String?, phase: Phase): String {
        if (phase == Phase.PREPARING || targetName.isNullOrBlank()) {
            return appContext.getString(R.string.notif_preparing)
        }
        val textRes = when (phase) {
            Phase.BUFFERING -> R.string.notif_buffering_on
            Phase.PLAYING -> R.string.notif_playing_on
            Phase.PAUSED -> R.string.notif_paused_on
            Phase.PREPARING -> error("Handled above")
        }
        return appContext.getString(textRes, targetName)
    }

    private fun action(
        iconRes: Int,
        titleRes: Int,
        action: String,
        requestCode: Int,
    ): Notification.Action {
        val intent = Intent(appContext, ProxyPlaybackService::class.java).setAction(action)
        val pendingIntent = PendingIntent.getService(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(appContext, iconRes),
            appContext.getString(titleRes),
            pendingIntent,
        ).build()
    }

    private fun customAction(action: String, titleRes: Int, iconRes: Int): PlaybackState.CustomAction =
        PlaybackState.CustomAction.Builder(action, appContext.getString(titleRes), iconRes).build()

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.adsamcik.streamferry.media.PLAY"
        const val ACTION_PAUSE = "com.adsamcik.streamferry.media.PAUSE"
        const val ACTION_STOP = "com.adsamcik.streamferry.media.STOP"
        const val ACTION_FFWD = "com.adsamcik.streamferry.media.FFWD"
        const val ACTION_RWND = "com.adsamcik.streamferry.media.RWND"

        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_STOP = 3
        private const val REQUEST_FORWARD = 4
        private const val REQUEST_REWIND = 5

        /**
         * Deadline-safe notification used before the media session is lazily constructed. It shares
         * all shell behavior with the rich notification but intentionally has no session dependency.
         */
        fun fallback(context: Context): Notification {
            val appContext = context.applicationContext
            ensureChannel(appContext)
            return baseBuilder(appContext)
                .setContentTitle(appContext.getString(R.string.app_name))
                .setContentText(appContext.getString(R.string.notif_preparing))
                .build()
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_playback_description)
                    setShowBadge(false)
                },
            )
        }

        private fun baseBuilder(context: Context): Notification.Builder =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_stream_ferry)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }
}
