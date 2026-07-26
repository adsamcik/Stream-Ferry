package com.adsamcik.streamferry.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.adsamcik.streamferry.R
import com.adsamcik.streamferry.core.metadata.MetadataSanitizer

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

    private val session = MediaSession(appContext, "StreamFerry").apply {
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
                override fun onCustomAction(action: String, extras: android.os.Bundle?) = dispatch(action)
            },
            Handler(Looper.getMainLooper()),
        )
        contentIntent?.let(::setSessionActivity)
        setPlaybackToRemote(volumeProvider())
    }

    val token: MediaSession.Token get() = session.sessionToken

    private val notificationFactory = PlaybackNotificationFactory(appContext, session.sessionToken, contentIntent)

    @Volatile
    private var lastNotification: Notification = notificationFactory.build(
        title = appContext.getString(R.string.app_name),
        targetName = null,
        phase = PlaybackNotificationFactory.Phase.PREPARING,
    )

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
        session.isActive = true
        val title = MetadataSanitizer.receiverTitle(status.title)
        val targetName = MetadataSanitizer.normalize(status.targetName, MAX_DEVICE_NAME_UTF8_BYTES)
            .ifBlank { appContext.getString(R.string.notif_your_tv) }
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, targetName)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, targetName)
            .apply {
                status.durationSeconds
                    ?.takeIf { it > 0L }
                    ?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it * 1000) }
            }
            .build()
        session.setMetadata(metadata)
        val playerState = when {
            status.isBuffering -> PlaybackState.STATE_BUFFERING
            status.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(stateOf(playerState, status.positionSeconds * 1000, status.isPlaying))
        val phase = when {
            status.isBuffering -> PlaybackNotificationFactory.Phase.BUFFERING
            status.isPlaying -> PlaybackNotificationFactory.Phase.PLAYING
            else -> PlaybackNotificationFactory.Phase.PAUSED
        }
        lastNotification = notificationFactory.build(
            title = title,
            targetName = targetName,
            phase = phase,
        )
        runCatching { notificationManager.notify(PlaybackNotificationFactory.NOTIFICATION_ID, lastNotification) }
    }

    /** The current notification (the foreground service uses it for `startForeground`). */
    fun currentNotification(): Notification {
        PlaybackNotificationFactory.ensureChannel(appContext)
        return lastNotification
    }

    /** Route a notification/media action (sent to the service) to the engine. */
    fun dispatch(action: String?) {
        when (action) {
            PlaybackNotificationFactory.ACTION_PLAY -> transport.onPlay()
            PlaybackNotificationFactory.ACTION_PAUSE -> transport.onPause()
            PlaybackNotificationFactory.ACTION_STOP -> transport.onStop()
            PlaybackNotificationFactory.ACTION_FFWD -> transport.onSkip(SKIP_SECONDS)
            PlaybackNotificationFactory.ACTION_RWND -> transport.onSkip(-SKIP_SECONDS)
        }
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun stateOf(state: Int, positionMs: Long, playing: Boolean): PlaybackState =
        notificationFactory.addSystemControls(
            PlaybackState.Builder()
                .setState(
                    state,
                    positionMs.coerceAtLeast(0L),
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND,
                ),
        ).build()

    companion object {
        private const val MAX_VOLUME = 20
        private const val SKIP_SECONDS = 30L
        private const val MAX_DEVICE_NAME_UTF8_BYTES = 160
    }
}
