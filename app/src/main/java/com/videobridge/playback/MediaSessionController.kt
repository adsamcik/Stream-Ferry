package com.videobridge.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.videobridge.R
import com.videobridge.core.metadata.MetadataSanitizer
import com.videobridge.playback.proxy.ProxyPlaybackService

/**
 * Bridges the [PlaybackEngine] to the Android media framework so the user gets real **playback
 * controls**: a `MediaStyle` notification (play/pause, rewind, fast-forward, stop), lock-screen
 * controls, hardware media-button support, and — via a [VolumeProvider] — phone volume keys that
 * control the TV's volume. Transport callbacks route to the engine (which commands the Cast/DLNA
 * renderer); the TV still only ever receives the phone proxy URL.
 *
 * Uses only framework APIs (no extra dependency). The session is created once and made active only
 * while playback is in progress.
 */
class MediaSessionController(
    context: Context,
    private val transport: Transport,
    private val contentIntent: PendingIntent?,
) {
    interface Transport {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSeekTo(positionSeconds: Long)
        fun onSkip(deltaSeconds: Long)
        fun onSetVolume(level: Float)
        fun onAdjustVolume(direction: Int)
    }

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    private val session = MediaSession(appContext, "JellyfinBridge").apply {
        // Bind callbacks to the main Looper explicitly: the 1-arg setCallback() would otherwise use
        // the *constructing* thread's Looper, which is null when this controller is first created off
        // the main thread (e.g. from a Dispatchers.IO flow collector) -> NullPointerException.
        setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = transport.onPlay()
                override fun onPause() = transport.onPause()
                override fun onStop() = transport.onStop()
                override fun onSeekTo(pos: Long) = transport.onSeekTo(pos / 1000)
                override fun onFastForward() = transport.onSkip(SKIP_SECONDS)
                override fun onRewind() = transport.onSkip(-SKIP_SECONDS)
            },
            Handler(Looper.getMainLooper()),
        )
        setPlaybackToRemote(volumeProvider())
    }

    val token: MediaSession.Token get() = session.sessionToken

    @Volatile
    private var lastNotification: Notification = buildNotification("Video Bridge", "Preparing…", playing = false)

    private fun volumeProvider(): VolumeProvider =
        object : VolumeProvider(VOLUME_CONTROL_RELATIVE, MAX_VOLUME, MAX_VOLUME) {
            override fun onAdjustVolume(direction: Int) {
                currentVolume = (currentVolume + direction).coerceIn(0, MAX_VOLUME)
                transport.onAdjustVolume(direction)
            }
            override fun onSetVolumeTo(volume: Int) {
                currentVolume = volume.coerceIn(0, MAX_VOLUME)
                transport.onSetVolume(currentVolume.toFloat() / MAX_VOLUME)
            }
        }

    /** Reflect the latest engine status into the session + notification. */
    fun update(status: PlaybackStatus?) {
        if (status == null) {
            session.setPlaybackState(stateOf(PlaybackState.STATE_STOPPED, 0, false))
            session.isActive = false
            return
        }
        ensureChannel()
        session.isActive = true
        val title = MetadataSanitizer.receiverTitle(status.title)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "${status.targetName} · ${status.protocolName}")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (status.durationSeconds ?: 0L) * 1000)
                .build(),
        )
        val playerState = when {
            status.isBuffering -> PlaybackState.STATE_BUFFERING
            status.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(stateOf(playerState, status.positionSeconds * 1000, status.isPlaying))
        lastNotification = buildNotification(
            title = title,
            text = "${status.targetName} · ${if (status.isBuffering) "buffering" else if (status.isPlaying) "playing" else "paused"}",
            playing = status.isPlaying,
        )
        runCatching { notificationManager.notify(NOTIF_ID, lastNotification) }
    }

    /** The current notification (the foreground service uses it for `startForeground`). */
    fun currentNotification(): Notification {
        ensureChannel()
        return lastNotification
    }

    /** Route a notification/media action (sent to the service) to the engine. */
    fun dispatch(action: String?) {
        when (action) {
            ACTION_PLAY -> transport.onPlay()
            ACTION_PAUSE -> transport.onPause()
            ACTION_STOP -> transport.onStop()
            ACTION_FFWD -> transport.onSkip(SKIP_SECONDS)
            ACTION_RWND -> transport.onSkip(-SKIP_SECONDS)
        }
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun stateOf(state: Int, positionMs: Long, playing: Boolean): PlaybackState =
        PlaybackState.Builder()
            .setState(state, positionMs, if (playing) 1f else 0f)
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND,
            )
            .build()

    private fun buildNotification(title: String, text: String, playing: Boolean): Notification {
        val playPause = if (playing) {
            action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
        } else {
            action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY)
        }
        val style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(playing)
            // Keep server-provided title metadata off a locked device while preserving transport controls.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setStyle(style)
            .addAction(action(android.R.drawable.ic_media_rew, "Rewind", ACTION_RWND))
            .addAction(playPause)
            .addAction(action(android.R.drawable.ic_media_ff, "Forward", ACTION_FFWD))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
        contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun action(iconRes: Int, title: String, action: String): Notification.Action {
        val intent = Intent(appContext, ProxyPlaybackService::class.java).setAction(action)
        val pi = PendingIntent.getService(
            appContext, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(appContext, iconRes), title, pi).build()
    }

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, appContext.getString(R.string.notif_channel_playback), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIF_ID = 1001
        private const val MAX_VOLUME = 20
        private const val SKIP_SECONDS = 30L

        const val ACTION_PLAY = "com.videobridge.media.PLAY"
        const val ACTION_PAUSE = "com.videobridge.media.PAUSE"
        const val ACTION_STOP = "com.videobridge.media.STOP"
        const val ACTION_FFWD = "com.videobridge.media.FFWD"
        const val ACTION_RWND = "com.videobridge.media.RWND"
    }
}
