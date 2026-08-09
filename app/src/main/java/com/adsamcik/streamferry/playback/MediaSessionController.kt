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
import kotlin.math.roundToInt

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
    @Volatile private var remoteVolumeEnabled = false
    @Volatile private var playPauseEnabled = false
    @Volatile private var skipEnabled = false
    @Volatile private var timelineSeekEnabled = false
    private val remoteVolumeProvider = volumeProvider()

    private val session = MediaSession(appContext, "StreamFerry").apply {
        // Bind callbacks to the main Looper explicitly: the 1-arg setCallback() would otherwise use
        // the *constructing* thread's Looper, which is null when this controller is first created off
        // the main thread (e.g. from a Dispatchers.IO flow collector) -> NullPointerException.
        setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() { if (playPauseEnabled) transport.onPlay() }
                override fun onPause() { if (playPauseEnabled) transport.onPause() }
                override fun onStop() = transport.onStop()
                override fun onSeekTo(pos: Long) { if (timelineSeekEnabled) transport.onSeekTo(pos / 1000) }
                override fun onFastForward() { if (skipEnabled) transport.onSkip(SKIP_SECONDS) }
                override fun onRewind() { if (skipEnabled) transport.onSkip(-SKIP_SECONDS) }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) = dispatch(action)
            },
            Handler(Looper.getMainLooper()),
        )
        contentIntent?.let(::setSessionActivity)
        setPlaybackToRemote(remoteVolumeProvider)
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
                if (!remoteVolumeEnabled) return
                currentVolume = (currentVolume + direction).coerceIn(0, MAX_VOLUME)
                transport.onAdjustVolume(direction)
            }
            override fun onSetVolumeTo(volume: Int) {
                if (!remoteVolumeEnabled) return
                currentVolume = volume.coerceIn(0, MAX_VOLUME)
                transport.onSetVolume(currentVolume.toFloat() / MAX_VOLUME)
            }
        }

    /** Reflect the latest engine status into the session + notification. */
    fun update(status: PlaybackStatus?) {
        val controls = status?.let { PlaybackControlPolicy.evaluate(it.phase, it.durationSeconds) }
        playPauseEnabled = controls?.canPlayPause == true
        skipEnabled = controls?.canSkip == true
        timelineSeekEnabled = controls?.canSeekTimeline == true
        remoteVolumeEnabled = status?.volumeSupported == true
        if (remoteVolumeEnabled) {
            remoteVolumeProvider.setCurrentVolume((status!!.volume * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME))
        }
        if (status == null) {
            session.setPlaybackState(stateOf(PlaybackState.STATE_STOPPED, 0, false, canPlayPause = false, canSkip = false, canSeek = false))
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
            controls?.isTransitioning == true -> PlaybackState.STATE_CONNECTING
            status.isBuffering -> PlaybackState.STATE_BUFFERING
            status.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            stateOf(
                playerState,
                status.positionSeconds * 1000,
                status.isPlaying,
                canPlayPause = playPauseEnabled,
                canSkip = skipEnabled,
                canSeek = timelineSeekEnabled,
            ),
        )
        val phase = when {
            controls?.isTransitioning == true -> PlaybackNotificationFactory.Phase.PREPARING
            status.isBuffering -> PlaybackNotificationFactory.Phase.BUFFERING
            status.isPlaying -> PlaybackNotificationFactory.Phase.PLAYING
            else -> PlaybackNotificationFactory.Phase.PAUSED
        }
        lastNotification = notificationFactory.build(
            title = title,
            targetName = targetName,
            phase = phase,
            canPlayPause = playPauseEnabled,
            canSkip = skipEnabled,
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
            PlaybackNotificationFactory.ACTION_PLAY -> if (playPauseEnabled) transport.onPlay()
            PlaybackNotificationFactory.ACTION_PAUSE -> if (playPauseEnabled) transport.onPause()
            PlaybackNotificationFactory.ACTION_STOP -> transport.onStop()
            PlaybackNotificationFactory.ACTION_FFWD -> if (skipEnabled) transport.onSkip(SKIP_SECONDS)
            PlaybackNotificationFactory.ACTION_RWND -> if (skipEnabled) transport.onSkip(-SKIP_SECONDS)
        }
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun stateOf(
        state: Int,
        positionMs: Long,
        playing: Boolean,
        canPlayPause: Boolean = false,
        canSkip: Boolean = false,
        canSeek: Boolean = false,
    ): PlaybackState =
        notificationFactory.addSystemControls(
            PlaybackState.Builder()
                .setState(
                    state,
                    positionMs.coerceAtLeast(0L),
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .setActions(playbackActions(canPlayPause, canSkip, canSeek)),
            canSkip = canSkip,
        ).build()

    private fun playbackActions(canPlayPause: Boolean, canSkip: Boolean, canSeek: Boolean): Long {
        var actions = PlaybackState.ACTION_STOP
        if (canPlayPause) {
            actions = actions or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        }
        if (canSkip) actions = actions or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        return actions
    }

    companion object {
        private const val MAX_VOLUME = 20
        private const val SKIP_SECONDS = 30L
        private const val MAX_DEVICE_NAME_UTF8_BYTES = 160
    }
}
